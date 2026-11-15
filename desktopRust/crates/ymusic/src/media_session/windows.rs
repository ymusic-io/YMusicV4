//! SMTC, behind the media flyout, the lock screen and the media keys (the JVM app's
//! `WindowsSmtcSession`). A Win32 app reaches it through `ISystemMediaTransportControlsInterop`,
//! bound here to a hidden window of its own: the app's may be hidden to the tray, and a click on the
//! flyout card activates the window the session belongs to, which this one turns into a raise. One
//! MTA thread owns that window, pumps its messages and makes every SMTC call; button presses arrive
//! on a WinRT pool thread and only forward a [`Command`].

use std::cell::RefCell;
use std::sync::mpsc;
use std::time::Duration;

use futures::channel::mpsc::UnboundedSender;
use windows::Foundation::{TimeSpan, TypedEventHandler, Uri};
use windows::Media::{
    MediaPlaybackStatus, MediaPlaybackType, PlaybackPositionChangeRequestedEventArgs, SystemMediaTransportControls,
    SystemMediaTransportControlsButton, SystemMediaTransportControlsButtonPressedEventArgs,
    SystemMediaTransportControlsDisplayUpdater, SystemMediaTransportControlsTimelineProperties,
};
use windows::Storage::Streams::{IRandomAccessStream, RandomAccessStreamReference};
use windows::Win32::Foundation::{HGLOBAL, HINSTANCE, HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::Com::StructuredStorage::CreateStreamOnHGlobal;
use windows::Win32::System::Com::{IStream, STREAM_SEEK_SET};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::System::WinRT::{
    BSOS_DEFAULT, CreateRandomAccessStreamOverStream, ISystemMediaTransportControlsInterop, RO_INIT_MULTITHREADED,
    RoInitialize, RoUninitialize,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetMessageW, MSG, PostQuitMessage,
    PostThreadMessageW, RegisterClassExW, TranslateMessage, UnregisterClassW, WA_INACTIVE, WM_ACTIVATE, WM_APP,
    WNDCLASSEXW, WS_EX_TOOLWINDOW, WS_POPUP,
};
use windows::core::{HSTRING, PCWSTR, w};
use ym_common::log::Logger;

use super::{Command, Snapshot};

const CLASS_NAME: PCWSTR = w!("YMusicMediaSession");
/// Posted to the SMTC thread: run the queued jobs.
const WM_RUN_JOBS: u32 = WM_APP + 1;
const START_TIMEOUT: Duration = Duration::from_secs(5);

enum Job {
    Publish(Snapshot),
    Quit,
}

pub(super) struct Backend {
    thread_id: u32,
    jobs: mpsc::Sender<Job>,
    log: Logger,
}

thread_local! {
    /// The SMTC thread's way to the app for the hidden window's activation.
    static RAISE: RefCell<Option<UnboundedSender<Command>>> = const { RefCell::new(None) };
}

impl Backend {
    pub(super) fn start(commands: UnboundedSender<Command>, log: Logger) -> Result<Self, String> {
        let (ready, started) = mpsc::channel();
        let (jobs, inbox) = mpsc::channel();
        let thread_log = log.clone();
        std::thread::Builder::new()
            .name("smtc".into())
            .spawn(move || run(commands, inbox, ready, thread_log))
            .map_err(|error| format!("the SMTC thread did not start: {error}"))?;
        let thread_id =
            started.recv_timeout(START_TIMEOUT).map_err(|_| "the SMTC thread did not answer".to_owned())??;
        Ok(Self { thread_id, jobs, log })
    }

    pub(super) fn update(&self, snapshot: Snapshot) {
        self.send(Job::Publish(snapshot));
    }

    fn send(&self, job: Job) {
        // The thread is gone only after it logged why.
        if self.jobs.send(job).is_err() {
            return;
        }
        // SAFETY: a plain post to the SMTC thread's queue.
        if let Err(error) = unsafe { PostThreadMessageW(self.thread_id, WM_RUN_JOBS, WPARAM(0), LPARAM(0)) } {
            self.log.warn("R90031 media session update not delivered to the SMTC thread", Some(&error));
        }
    }
}

impl Drop for Backend {
    fn drop(&mut self) {
        self.send(Job::Quit);
    }
}

fn run(
    commands: UnboundedSender<Command>,
    inbox: mpsc::Receiver<Job>,
    ready: mpsc::Sender<Result<u32, String>>,
    log: Logger,
) {
    // SAFETY: balanced by the RoUninitialize below, on this thread.
    if let Err(error) = unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
        // The caller may have stopped waiting; it reports the timeout itself.
        let _ = ready.send(Err(format!("RoInitialize: {error}")));
        return;
    }
    RAISE.with(|raise| *raise.borrow_mut() = Some(commands.clone()));
    match Session::open(commands) {
        Ok(mut session) => {
            // SAFETY: reads the calling thread's id.
            let _ = ready.send(Ok(unsafe { GetCurrentThreadId() }));
            pump(&mut session, &inbox, &log);
            session.close();
        }
        Err(error) => {
            let _ = ready.send(Err(format!("SMTC setup: {error}")));
        }
    }
    // SAFETY: pairs the RoInitialize above.
    unsafe { RoUninitialize() };
}

fn pump(session: &mut Session, inbox: &mpsc::Receiver<Job>, log: &Logger) {
    let mut message = MSG::default();
    // SAFETY: the thread's own message loop; the window it pumps lives on this thread.
    while unsafe { GetMessageW(&mut message, None, 0, 0) }.as_bool() {
        if message.hwnd.is_invalid() && message.message == WM_RUN_JOBS {
            for job in inbox.try_iter() {
                match job {
                    Job::Publish(snapshot) => {
                        if let Err(error) = session.publish(&snapshot, log) {
                            log.warn("R90024 media session not published to SMTC", Some(&error));
                        }
                    }
                    // SAFETY: ends this thread's loop.
                    Job::Quit => unsafe { PostQuitMessage(0) },
                }
            }
            continue;
        }
        // SAFETY: a message this thread's queue handed over.
        unsafe {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
}

struct Session {
    window: HWND,
    instance: HINSTANCE,
    controls: SystemMediaTransportControls,
    updater: SystemMediaTransportControlsDisplayUpdater,
    timeline: SystemMediaTransportControlsTimelineProperties,
    button_token: i64,
    seek_token: i64,
    artwork: Option<String>,
}

impl Session {
    fn open(commands: UnboundedSender<Command>) -> windows::core::Result<Self> {
        // SAFETY: the module and window of this thread; the class outlives the window it registers.
        let (instance, window) = unsafe {
            let instance: HINSTANCE = GetModuleHandleW(None)?.into();
            let class = WNDCLASSEXW {
                cbSize: size_of::<WNDCLASSEXW>() as u32,
                lpfnWndProc: Some(window_proc),
                hInstance: instance,
                lpszClassName: CLASS_NAME,
                ..Default::default()
            };
            if RegisterClassExW(&class) == 0 {
                return Err(windows::core::Error::from_thread());
            }
            let window = CreateWindowExW(
                WS_EX_TOOLWINDOW,
                CLASS_NAME,
                w!("YMusic"),
                WS_POPUP,
                0,
                0,
                0,
                0,
                None,
                None,
                Some(instance),
                None,
            )?;
            (instance, window)
        };
        let interop = windows::core::factory::<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>()?;
        // SAFETY: the hidden window above, alive until `close`.
        let controls: SystemMediaTransportControls = unsafe { interop.GetForWindow(window) }?;
        controls.SetIsPlayEnabled(true)?;
        controls.SetIsPauseEnabled(true)?;
        controls.SetIsNextEnabled(true)?;
        controls.SetIsPreviousEnabled(true)?;
        let buttons = commands.clone();
        let button_token = controls.ButtonPressed(&TypedEventHandler::new(
            move |_, args: windows::core::Ref<'_, SystemMediaTransportControlsButtonPressedEventArgs>| {
                let command = match args.ok()?.Button()? {
                    SystemMediaTransportControlsButton::Play => Command::Play,
                    SystemMediaTransportControlsButton::Pause => Command::Pause,
                    SystemMediaTransportControlsButton::Next => Command::Next,
                    SystemMediaTransportControlsButton::Previous => Command::Previous,
                    _ => return Ok(()),
                };
                // The app outlives its session; at quit a press has nowhere to go.
                let _ = buttons.unbounded_send(command);
                Ok(())
            },
        ))?;
        let seek_token = controls.PlaybackPositionChangeRequested(&TypedEventHandler::new(
            move |_, args: windows::core::Ref<'_, PlaybackPositionChangeRequestedEventArgs>| {
                let position = Duration::from(args.ok()?.RequestedPlaybackPosition()?);
                let _ = commands.unbounded_send(Command::SeekTo(position.as_millis() as i64));
                Ok(())
            },
        ))?;
        let updater = controls.DisplayUpdater()?;
        let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
        Ok(Self { window, instance, controls, updater, timeline, button_token, seek_token, artwork: None })
    }

    fn publish(&mut self, snapshot: &Snapshot, log: &Logger) -> windows::core::Result<()> {
        if !snapshot.has_item {
            self.updater.ClearAll()?;
            self.updater.Update()?;
            self.artwork = None;
            self.controls.SetPlaybackStatus(MediaPlaybackStatus::Closed)?;
            return self.controls.SetIsEnabled(false);
        }
        self.controls.SetIsEnabled(true)?;
        let status = if snapshot.playing { MediaPlaybackStatus::Playing } else { MediaPlaybackStatus::Paused };
        self.controls.SetPlaybackStatus(status)?;
        self.updater.SetType(MediaPlaybackType::Music)?;
        let music = self.updater.MusicProperties()?;
        music.SetTitle(&HSTRING::from(snapshot.title.as_deref().unwrap_or_default()))?;
        music.SetArtist(&HSTRING::from(snapshot.artist.as_deref().unwrap_or_default()))?;
        if snapshot.artwork_url != self.artwork {
            self.artwork = snapshot.artwork_url.clone();
            // Cosmetic: a thumbnail that cannot be built must not hold back title and state.
            let thumbnail = snapshot.artwork_url.as_deref().map(thumbnail).transpose();
            match thumbnail {
                Ok(Some(reference)) => self.updater.SetThumbnail(&reference)?,
                Ok(None) => self.updater.SetThumbnail(None)?,
                Err(error) => log.warn(&format!("SMTC thumbnail not set: {error}"), None),
            }
        }
        self.updater.Update()?;
        let end = Duration::from_millis(snapshot.duration_ms.unwrap_or(0).max(0) as u64);
        let mut position = Duration::from_millis(snapshot.position_ms.max(0) as u64);
        if !end.is_zero() {
            position = position.min(end);
        }
        self.timeline.SetStartTime(TimeSpan::default())?;
        self.timeline.SetEndTime(TimeSpan::from(end))?;
        self.timeline.SetMinSeekTime(TimeSpan::default())?;
        self.timeline.SetMaxSeekTime(TimeSpan::from(end))?;
        self.timeline.SetPosition(TimeSpan::from(position))?;
        self.controls.UpdateTimelineProperties(&self.timeline)?;
        self.controls.SetPlaybackRate(f64::from(snapshot.speed))
    }

    /// Fire-and-forget teardown at exit: the process is going, so failures go unreported.
    fn close(self) {
        let _ = self.controls.RemoveButtonPressed(self.button_token);
        let _ = self.controls.RemovePlaybackPositionChangeRequested(self.seek_token);
        let _ = self.controls.SetIsEnabled(false);
        // SAFETY: this thread's window and class, destroyed once.
        unsafe {
            let _ = DestroyWindow(self.window);
            let _ = UnregisterClassW(CLASS_NAME, Some(self.instance));
        }
    }
}

/// An http(s) thumbnail goes as a URI Windows fetches; a `file:` one (the cached art, JPEG) as a
/// stream, since `CreateFromUri` takes no `file:`.
fn thumbnail(url: &str) -> Result<RandomAccessStreamReference, String> {
    if !url.starts_with("file:") {
        let uri = Uri::CreateUri(&HSTRING::from(url)).map_err(|error| error.to_string())?;
        return RandomAccessStreamReference::CreateFromUri(&uri).map_err(|error| error.to_string());
    }
    let path = url::Url::parse(url).ok().and_then(|url| url.to_file_path().ok()).ok_or("not a file path")?;
    // The stream below takes the whole image; it is the art cache's small JPEG.
    let bytes = std::fs::read(&path).map_err(|error| format!("{}: {error}", path.display()))?;
    stream_reference(&bytes).map_err(|error| error.to_string())
}

fn stream_reference(bytes: &[u8]) -> windows::core::Result<RandomAccessStreamReference> {
    // SAFETY: a stream this function owns, written from `bytes` while they are borrowed.
    unsafe {
        let stream: IStream = CreateStreamOnHGlobal(HGLOBAL::default(), true)?;
        stream.Write(bytes.as_ptr().cast(), bytes.len() as u32, None).ok()?;
        stream.Seek(0, STREAM_SEEK_SET, None)?;
        let random: IRandomAccessStream = CreateRandomAccessStreamOverStream(&stream, BSOS_DEFAULT)?;
        RandomAccessStreamReference::CreateFromStream(&random)
    }
}

unsafe extern "system" fn window_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if message == WM_ACTIVATE && (wparam.0 & 0xFFFF) as u32 != WA_INACTIVE {
        RAISE.with(|raise| {
            if let Some(commands) = raise.borrow().as_ref() {
                let _ = commands.unbounded_send(Command::Raise);
            }
        });
    }
    // SAFETY: the default handling of this window's message.
    unsafe { DefWindowProcW(window, message, wparam, lparam) }
}
