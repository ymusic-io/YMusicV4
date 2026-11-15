//! One app process per data dir, shared with the JVM app (`SingleInstance.kt`): the first launch
//! holds `instance.lock` and listens on a loopback port; a later launch hands its request (files,
//! links, a player command) over and exits. The port file carries a random token, so only a process
//! that can read the data dir is listened to. The lock and the wire are the JVM app's, so whichever
//! of the two apps starts second defers to the running one.

use std::ffi::OsString;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use futures::channel::mpsc::{UnboundedReceiver, UnboundedSender};
use gpui_kit::Global;
use ym_common::file_uri::to_file_uri;
use ym_common::log::Logger;

/// ~9 s: outlasts a quitting instance's wind-down, which holds the lock but takes nothing.
const HANDOFF_ATTEMPTS: u32 = 60;
const HANDOFF_RETRY: Duration = Duration::from_millis(150);
const HANDOFF_TIMEOUT: Duration = Duration::from_secs(2);
const WAKE_TIMEOUT: Duration = Duration::from_millis(500);
/// How long a plain launch the JVM app took watches for it to quit.
const TAKEOVER_WINDOW: Duration = Duration::from_secs(5);
/// Starts this app's port-file token. The JVM app's is bare hex and both send the token back
/// verbatim, so a launch can tell which app it handed over to.
const TOKEN_PREFIX: &str = "rust-";
const MAX_REQUEST_BYTES: u64 = 1 << 20;
/// Not a JVM command: the JVM app reads the request and drops it, so it never takes links as files.
const LINKS_COMMAND: &str = "Links";

static ACCEPTING: AtomicBool = AtomicBool::new(true);
/// Held until the process exits. A POSIX record lock goes with the first descriptor of the file this
/// process closes, so nothing else may open `instance.lock`.
static HELD_LOCK: OnceLock<File> = OnceLock::new();
static LISTENER: OnceLock<(u16, Logger)> = OnceLock::new();

/// What a launch asks of the app: bring the window back, or a player task (the Windows Jump List).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    Show,
    TogglePlay,
    Stop,
    Quit,
}

impl Command {
    /// `(command, --player= argument, the JVM's InstanceCommand name on the wire)`.
    const ALL: [(Command, &'static str, &'static str); 4] = [
        (Command::Show, "show", "Show"),
        (Command::TogglePlay, "toggle", "TogglePlay"),
        (Command::Stop, "stop", "Stop"),
        (Command::Quit, "quit", "Quit"),
    ];

    fn from_arg(arg: &str) -> Option<Self> {
        Self::ALL.iter().find(|(_, known, _)| *known == arg).map(|(command, _, _)| *command)
    }

    /// The launch argument that asks a running app for this command.
    #[cfg_attr(not(target_os = "windows"), allow(dead_code))]
    pub fn player_arg(self) -> String {
        let name = Self::ALL.iter().find(|(command, _, _)| *command == self).map_or("show", |(_, arg, _)| arg);
        format!("--player={name}")
    }

    fn wire_name(self) -> &'static str {
        Self::ALL.iter().find(|(command, _, _)| *command == self).map_or("Show", |(_, _, name)| name)
    }

    fn from_wire_name(name: &str) -> Option<Self> {
        Self::ALL.iter().find(|(_, _, known)| *known == name).map(|(command, _, _)| *command)
    }
}

/// A launch's or an OS open event's command, media files and outside links.
#[derive(Debug, Clone, PartialEq)]
pub struct Request {
    pub command: Command,
    pub files: Vec<PathBuf>,
    pub links: Vec<String>,
}

impl Request {
    /// From the process arguments (without the program): `--player=toggle|stop|quit`, media file
    /// paths or `file://` URLs, and app / web links. Other flags (macOS's `-psn_…`) are ignored.
    pub fn from_args(args: impl IntoIterator<Item = OsString>) -> Self {
        let mut request = Self { command: Command::Show, files: Vec::new(), links: Vec::new() };
        for arg in args {
            let arg = arg.to_string_lossy().into_owned();
            if let Some(name) = arg.strip_prefix("--player=") {
                if let Some(command) = Command::from_arg(name) {
                    request.command = command;
                }
            } else if arg.starts_with('-') {
                continue;
            } else if is_link(&arg) {
                request.add_url(arg);
            } else if let Ok(path) = std::path::absolute(&arg)
                && path.is_file()
            {
                request.files.push(path);
            }
        }
        request
    }

    /// From the URLs an OS open event carries (macOS `application:openURLs:`): files and links.
    pub fn from_urls(urls: Vec<String>) -> Self {
        let mut request = Self { command: Command::Show, files: Vec::new(), links: Vec::new() };
        for url in urls {
            request.add_url(url);
        }
        request
    }

    fn add_url(&mut self, url: String) {
        match url::Url::parse(&url) {
            Ok(parsed) if parsed.scheme() == "file" => {
                if let Ok(path) = parsed.to_file_path() {
                    self.files.push(path);
                }
            }
            Ok(_) => self.links.push(url),
            Err(_) => {}
        }
    }

    pub fn is_empty(&self) -> bool {
        self.command == Command::Show && self.files.is_empty() && self.links.is_empty()
    }

    /// The JVM app's request: the token, the command, then one `file://` URI per line in the JVM
    /// encoding (the JVM app matches them against its Library byte for byte).
    fn to_wire(&self, token: &str) -> Vec<String> {
        let files = self.files.iter().map(|path| to_file_uri(&path.to_string_lossy()));
        [token.to_owned(), self.command.wire_name().to_owned()].into_iter().chain(files).collect()
    }

    fn links_to_wire(&self, token: &str) -> Option<Vec<String>> {
        (!self.links.is_empty()).then(|| {
            [token.to_owned(), LINKS_COMMAND.to_owned()].into_iter().chain(self.links.iter().cloned()).collect()
        })
    }

    fn from_wire(text: &str, token: &str) -> Option<Self> {
        let mut lines = text.lines();
        if lines.next()? != token {
            return None;
        }
        let name = lines.next()?;
        let mut request = Self { command: Command::Show, files: Vec::new(), links: Vec::new() };
        if name == LINKS_COMMAND {
            request.links.extend(lines.filter(|line| is_link(line)).map(str::to_owned));
        } else {
            request.command = Command::from_wire_name(name)?;
            for line in lines {
                request.add_url(line.to_owned());
            }
        }
        Some(request)
    }
}

/// An app link or a web link (what the engine's `resolve-link` takes); never a bare path.
fn is_link(arg: &str) -> bool {
    url::Url::parse(arg).is_ok_and(|url| matches!(url.scheme(), "ymusic" | "http" | "https" | "file"))
}

/// Requests from later launches and OS open events, held until the shell takes them.
pub struct Inbox(pub UnboundedReceiver<Request>);

impl Global for Inbox {}

pub enum Claim {
    /// This process is the app instance; later launches' requests arrive on the channel given.
    Owner,
    /// The request went to the running instance; this process has nothing left to do.
    HandedOver,
}

#[derive(Debug, thiserror::Error)]
pub enum ClaimError {
    #[error("R96000 another process holds the instance lock but took no request")]
    NoAnswer(#[source] Option<std::io::Error>),
    #[error("R96001 the instance claim could not be set up")]
    Setup(#[source] std::io::Error),
}

/// Claims this process as the app instance for `dir`, or hands `request` to the running one.
pub fn claim(
    dir: &Path,
    request: &Request,
    sender: UnboundedSender<Request>,
    log: &Logger,
) -> Result<Claim, ClaimError> {
    std::fs::create_dir_all(dir).map_err(ClaimError::Setup)?;
    let lock_path = dir.join("instance.lock");
    let port_path = dir.join("instance.port");
    let mut last_error = None;
    for attempt in 0..HANDOFF_ATTEMPTS {
        if attempt > 0 {
            std::thread::sleep(HANDOFF_RETRY);
        }
        let lock = open_lock(&lock_path)?;
        if try_lock(&lock).map_err(ClaimError::Setup)? {
            return own(lock, &port_path, sender, log);
        }
        drop(lock);
        if attempt == 0 && request.command == Command::Show {
            share_foreground(log);
        }
        match hand_off(&port_path, request, log) {
            Ok(Holder::Jvm) if request.is_empty() => return outlast(&lock_path, &port_path, sender, log),
            Ok(_) => return Ok(Claim::HandedOver),
            Err(error) => last_error = Some(error),
        }
    }
    Err(ClaimError::NoAnswer(last_error))
}

/// A plain launch the JVM app took may have reached it quitting: its updater starts this version
/// while it winds down, and builds before the bridge release keep taking requests then. If the lock
/// frees within the window this process becomes the instance. This app stops taking requests before
/// it quits or relaunches, so a launch it takes never waits.
fn outlast(
    lock_path: &Path,
    port_path: &Path,
    sender: UnboundedSender<Request>,
    log: &Logger,
) -> Result<Claim, ClaimError> {
    let deadline = Instant::now() + TAKEOVER_WINDOW;
    while Instant::now() < deadline {
        std::thread::sleep(HANDOFF_RETRY);
        let lock = open_lock(lock_path)?;
        if try_lock(&lock).map_err(ClaimError::Setup)? {
            log.info("instance: the instance that took this launch quit; this one takes over");
            return own(lock, port_path, sender, log);
        }
    }
    Ok(Claim::HandedOver)
}

fn open_lock(lock_path: &Path) -> Result<File, ClaimError> {
    OpenOptions::new().create(true).truncate(false).write(true).open(lock_path).map_err(ClaimError::Setup)
}

fn own(lock: File, port_path: &Path, sender: UnboundedSender<Request>, log: &Logger) -> Result<Claim, ClaimError> {
    let _ = HELD_LOCK.set(lock);
    listen(port_path, sender, log.clone()).map_err(ClaimError::Setup)?;
    Ok(Claim::Owner)
}

/// The JVM's `FileChannel.tryLock()`: an exclusive POSIX record lock over the whole file. Linux keeps
/// `flock(2)` locks apart from these, so `File::try_lock` would not see the JVM app's lock there.
#[cfg(unix)]
fn try_lock(file: &File) -> std::io::Result<bool> {
    use std::os::fd::AsRawFd;
    // SAFETY: a zeroed `flock` is a valid value; the fields set below select the whole file.
    let mut region: libc::flock = unsafe { std::mem::zeroed() };
    region.l_type = libc::F_WRLCK as _;
    region.l_whence = libc::SEEK_SET as _;
    region.l_start = 0;
    region.l_len = 0;
    // SAFETY: a live descriptor and a pointer to an initialized `flock` for the call's duration.
    if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLK, &region) } == 0 {
        return Ok(true);
    }
    let error = std::io::Error::last_os_error();
    match error.raw_os_error() {
        Some(libc::EAGAIN | libc::EACCES) => Ok(false),
        _ => Err(error),
    }
}

/// `LockFileEx` over the whole file, which overlaps the JVM's `tryLock()` range.
#[cfg(windows)]
fn try_lock(file: &File) -> std::io::Result<bool> {
    match file.try_lock() {
        Ok(()) => Ok(true),
        Err(std::fs::TryLockError::WouldBlock) => Ok(false),
        Err(std::fs::TryLockError::Error(error)) => Err(error),
    }
}

/// Stops taking requests while the lock stays held until exit: the listener closes, so a launch
/// during the quit wind-down finds nobody listening and retries until this process is gone.
pub fn stop_accepting() {
    if !ACCEPTING.swap(false, Ordering::SeqCst) {
        return;
    }
    let Some((port, log)) = LISTENER.get() else { return };
    // The accept loop wakes on this connection, sees the flag and drops the listener.
    if let Err(error) = TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, *port)), WAKE_TIMEOUT) {
        log.warn("instance hand-off: the listener could not be woken to close", Some(&error));
    }
}

fn listen(port_path: &Path, sender: UnboundedSender<Request>, log: Logger) -> std::io::Result<()> {
    let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))?;
    let port = listener.local_addr()?.port();
    let hex: String = rand::random::<[u8; 16]>().iter().map(|byte| format!("{byte:02x}")).collect();
    let token = format!("{TOKEN_PREFIX}{hex}");
    let staged = port_path.with_extension("port.tmp");
    std::fs::write(&staged, format!("{port} {token}"))?;
    std::fs::rename(&staged, port_path)?;
    let _ = LISTENER.set((port, log.clone()));
    std::thread::Builder::new().name("single-instance".into()).spawn(move || {
        for stream in listener.incoming() {
            if !ACCEPTING.load(Ordering::SeqCst) {
                return;
            }
            let stream = match stream {
                Ok(stream) => stream,
                Err(error) => {
                    log.warn("instance hand-off: accept failed", Some(&error));
                    continue;
                }
            };
            match receive(stream, &token) {
                Ok(Some(request)) => {
                    if sender.unbounded_send(request).is_err() {
                        return;
                    }
                }
                Ok(None) => log.warn("instance hand-off: a request without the token was ignored", None),
                Err(error) => log.warn("instance hand-off failed", Some(&error)),
            }
        }
    })?;
    Ok(())
}

/// Reads to the sender's end of stream and answers with an orderly close, as the JVM app does.
fn receive(mut stream: TcpStream, token: &str) -> std::io::Result<Option<Request>> {
    stream.set_read_timeout(Some(HANDOFF_TIMEOUT))?;
    let mut bytes = Vec::new();
    (&mut stream).take(MAX_REQUEST_BYTES).read_to_end(&mut bytes)?;
    Ok(Request::from_wire(&String::from_utf8_lossy(&bytes), token))
}

/// Which app took a hand-off, by its port-file token.
enum Holder {
    Jvm,
    Rust,
}

/// The JVM app's request first (command and files), then the links in a request of their own.
fn hand_off(port_path: &Path, request: &Request, log: &Logger) -> std::io::Result<Holder> {
    let invalid = || std::io::Error::new(std::io::ErrorKind::InvalidData, "bad instance.port");
    let contents = std::fs::read_to_string(port_path)?;
    let (port, token) = contents.trim().split_once(' ').ok_or_else(invalid)?;
    let port: u16 = port.parse().map_err(|_| invalid())?;
    send_lines(port, &request.to_wire(token))?;
    if let Some(links) = request.links_to_wire(token)
        && let Err(error) = send_lines(port, &links)
    {
        log.warn("instance hand-off: the links did not reach the running instance", Some(&error));
    }
    Ok(if token.starts_with(TOKEN_PREFIX) { Holder::Rust } else { Holder::Jvm })
}

/// The receiver closes once it has read everything; a reset instead (a listener closing with the
/// request still queued) is an error, so the launch retries.
fn send_lines(port: u16, lines: &[String]) -> std::io::Result<()> {
    let mut stream = TcpStream::connect_timeout(&SocketAddr::from((Ipv4Addr::LOCALHOST, port)), HANDOFF_TIMEOUT)?;
    stream.set_read_timeout(Some(HANDOFF_TIMEOUT))?;
    let mut wire = lines.join("\n");
    wire.push('\n');
    stream.write_all(wire.as_bytes())?;
    stream.shutdown(std::net::Shutdown::Write)?;
    let mut rest = Vec::new();
    stream.take(MAX_REQUEST_BYTES).read_to_end(&mut rest)?;
    Ok(())
}

/// Windows lets only the process the user just launched take the foreground: pass that on.
#[cfg(target_os = "windows")]
fn share_foreground(log: &Logger) {
    use windows::Win32::UI::WindowsAndMessaging::{ASFW_ANY, AllowSetForegroundWindow};
    // SAFETY: a plain Win32 call without pointers.
    if let Err(error) = unsafe { AllowSetForegroundWindow(ASFW_ANY) } {
        log.info(&format!("AllowSetForegroundWindow refused: {error}"));
    }
}

#[cfg(not(target_os = "windows"))]
fn share_foreground(_log: &Logger) {}
