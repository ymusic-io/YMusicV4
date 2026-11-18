//! SIGTERM and SIGINT (a logout, a shutdown, `kill`, Ctrl+C) quit as the app's Quit does, so the
//! quit hooks run. The handler only writes a byte to a pipe; a thread turns it into the quit.

use std::sync::atomic::{AtomicI32, Ordering};

use futures::channel::mpsc::{UnboundedReceiver, UnboundedSender, unbounded};
use gpui_kit::App;
use ym_common::log::Logger;

use crate::instance::{Command, Inbox, Request};

static SIGNAL_PIPE: AtomicI32 = AtomicI32::new(-1);
const SIGNALS: [libc::c_int; 2] = [libc::SIGTERM, libc::SIGINT];

extern "C" fn on_signal(_signal: libc::c_int) {
    let fd = SIGNAL_PIPE.load(Ordering::Relaxed);
    if fd < 0 {
        return;
    }
    // SAFETY: errno and write(2) are async-signal-safe; errno is restored for the interrupted code.
    unsafe {
        let errno = errno_location();
        let saved = *errno;
        libc::write(fd, [1u8].as_ptr().cast(), 1);
        *errno = saved;
    }
}

#[cfg(target_os = "linux")]
unsafe fn errno_location() -> *mut libc::c_int {
    // SAFETY: returns the calling thread's errno slot.
    unsafe { libc::__errno_location() }
}

#[cfg(target_os = "macos")]
unsafe fn errno_location() -> *mut libc::c_int {
    // SAFETY: returns the calling thread's errno slot.
    unsafe { libc::__error() }
}

/// Blocking: from here a termination signal arrives on the receiver once; a second one kills the
/// process as it would without this.
pub fn install(log: &Logger) -> std::io::Result<UnboundedReceiver<()>> {
    let mut fds = [0 as libc::c_int; 2];
    // SAFETY: plain libc calls on descriptors this function owns.
    let piped = unsafe {
        libc::pipe(fds.as_mut_ptr()) == 0
            && fds.iter().all(|fd| libc::fcntl(*fd, libc::F_SETFD, libc::FD_CLOEXEC) == 0)
            && libc::fcntl(fds[1], libc::F_SETFL, libc::O_NONBLOCK) == 0
    };
    if !piped {
        return Err(std::io::Error::last_os_error());
    }
    let (read_fd, write_fd) = (fds[0], fds[1]);
    let (sender, receiver) = unbounded();
    let thread_log = log.clone();
    std::thread::Builder::new().name("signals".into()).spawn(move || wait(read_fd, &sender, &thread_log))?;
    SIGNAL_PIPE.store(write_fd, Ordering::Relaxed);
    for signal in SIGNALS {
        // SAFETY: the handler only touches an atomic, errno and write(2).
        let installed = unsafe {
            let mut action: libc::sigaction = std::mem::zeroed();
            action.sa_sigaction = on_signal as extern "C" fn(libc::c_int) as libc::sighandler_t;
            action.sa_flags = libc::SA_RESTART;
            libc::sigemptyset(&mut action.sa_mask);
            libc::sigaction(signal, &action, std::ptr::null_mut()) == 0
        };
        if !installed {
            return Err(std::io::Error::last_os_error());
        }
    }
    Ok(receiver)
}

fn wait(read_fd: libc::c_int, sender: &UnboundedSender<()>, log: &Logger) {
    let mut byte = 0u8;
    loop {
        // SAFETY: reads one byte into a local from the pipe's read end, which this thread owns.
        let read = unsafe { libc::read(read_fd, (&mut byte as *mut u8).cast(), 1) };
        if read == 1 {
            break;
        }
        let error = std::io::Error::last_os_error();
        if read < 0 && error.kind() == std::io::ErrorKind::Interrupted {
            continue;
        }
        return log.warn("termination signals: the pipe closed, a signal no longer quits", Some(&error));
    }
    for signal in SIGNALS {
        // SAFETY: restores the default disposition.
        unsafe { libc::signal(signal, libc::SIG_DFL) };
    }
    log.info("termination signal: quitting");
    if sender.unbounded_send(()).is_err() {
        // SAFETY: the default disposition is back, so this ends the process as the signal would have.
        unsafe { libc::raise(libc::SIGTERM) };
    }
}

/// The quit a termination signal asks for: the shell's Quit (window bounds saved) once the shell
/// runs, before it (the unlock screen) a plain quit.
pub fn quit(inbox: &UnboundedSender<Request>, cx: &mut App) {
    let quit = Request { command: Command::Quit, files: Vec::new(), links: Vec::new() };
    if cx.has_global::<Inbox>() || inbox.unbounded_send(quit).is_err() {
        cx.quit();
    }
}
