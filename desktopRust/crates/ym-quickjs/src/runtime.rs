use std::any::Any;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Weak};

use bytes::{Buf, Bytes, BytesMut};
use futures::future::LocalBoxFuture;
use tokio::sync::{mpsc, oneshot};

use crate::ffi;

/// Per-direction buffer bound. Must match `PB_CAP` / `PB_LOW` in quickjs_pipe.c.
pub const PIPE_CAP: usize = 64 * 1024;
const PIPE_LOW: usize = PIPE_CAP / 2;

pub type BoxError = Box<dyn std::error::Error + Send + Sync>;
/// Invoked and polled on the engine thread only, so the future it returns need not be `Send`.
pub type Handler = Arc<dyn Fn(Pipe) -> LocalBoxFuture<'static, Result<(), BoxError>> + Send + Sync>;
pub type LogHandler = Arc<dyn Fn(LogLevel, &str) + Send + Sync>;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PipeError {
    #[error("runtime closed")]
    RuntimeClosed,
    #[error("open(\"{0}\") refused: no JS handler")]
    Refused(String),
    #[error("{0}")]
    Reset(String),
    #[error("broken pipe: the peer closed its read end")]
    BrokenPipe,
    #[error("{0}")]
    SendClosed(&'static str),
    #[error("{0}")]
    Misuse(&'static str),
    #[error("{0}")]
    Js(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
    /// An unhandled promise rejection, surfaced by the C-side tracker.
    Rejection,
}

impl LogLevel {
    fn from_native(level: u32) -> Self {
        match level {
            0 => Self::Debug,
            1 => Self::Info,
            2 => Self::Warn,
            4 => Self::Rejection,
            _ => Self::Error,
        }
    }
}

type Job = Box<dyn FnOnce(&Rc<Engine>) + Send>;

enum Msg {
    Job(Job),
    Close(oneshot::Sender<()>),
}

#[derive(Default)]
struct Accepts {
    exact: HashMap<String, Handler>,
    prefix: HashMap<String, Handler>,
}

impl Accepts {
    fn lookup(&self, command: &str) -> Option<Handler> {
        if let Some(handler) = self.exact.get(command) {
            return Some(handler.clone());
        }
        self.prefix
            .iter()
            .filter(|(prefix, _)| command.starts_with(prefix.as_str()))
            .max_by_key(|(prefix, _)| prefix.len())
            .map(|(_, handler)| handler.clone())
    }
}

struct Shared {
    jobs: mpsc::UnboundedSender<Msg>,
    closed: AtomicBool,
    accepts: Mutex<Accepts>,
}

impl Shared {
    fn post(&self, job: impl FnOnce(&Rc<Engine>) + Send + 'static) -> bool {
        !self.closed.load(Ordering::Acquire) && self.jobs.send(Msg::Job(Box::new(job))).is_ok()
    }

    async fn call<R: Send + 'static>(
        &self,
        job: impl FnOnce(&Rc<Engine>) -> R + Send + 'static,
    ) -> Result<R, PipeError> {
        let (tx, rx) = oneshot::channel();
        if !self.post(move |engine| {
            let _ = tx.send(job(engine));
        }) {
            return Err(PipeError::RuntimeClosed);
        }
        rx.await.map_err(|_| PipeError::RuntimeClosed)
    }
}

/// A live [`QuickJsRuntime::on_accept`] registration.
pub struct AcceptRegistration {
    shared: Weak<Shared>,
    key: String,
    prefix: bool,
    handler: Handler,
}

impl AcceptRegistration {
    /// A no-op once a newer registration replaced this one.
    pub fn remove(self) {
        let Some(shared) = self.shared.upgrade() else { return };
        let mut accepts = shared.accepts.lock().unwrap();
        let map = if self.prefix { &mut accepts.prefix } else { &mut accepts.exact };
        if map.get(&self.key).is_some_and(|current| Arc::ptr_eq(current, &self.handler)) {
            map.remove(&self.key);
        }
    }
}

/// A QuickJS engine on its own thread, reached through multiplexed, flow-controlled [`Pipe`]s.
///
/// Every method is callable from any thread. Accept handlers run ON the engine thread, which
/// also runs all JS, every pipe and every timer: a handler must never block — move SQLite, file
/// and other blocking calls to `tokio::task::spawn_blocking`.
#[derive(Clone)]
pub struct QuickJsRuntime {
    shared: Arc<Shared>,
}

/// A handle that does not keep the runtime alive — what accept handlers capture, since the
/// runtime owns its handlers.
#[derive(Clone)]
pub struct WeakRuntime {
    shared: Weak<Shared>,
}

impl WeakRuntime {
    pub fn upgrade(&self) -> Option<QuickJsRuntime> {
        self.shared.upgrade().map(|shared| QuickJsRuntime { shared })
    }
}

impl QuickJsRuntime {
    /// Starts the engine thread and creates a bare engine (no script yet). Register handlers with
    /// [`Self::on_accept`], then boot the bundle with [`Self::eval_engine`]: the bundle's
    /// top-level code may already open host commands.
    pub fn new(app_version: i32, versions_json: Option<String>, on_log: Option<LogHandler>) -> Result<Self, PipeError> {
        let (jobs, inbox) = mpsc::unbounded_channel();
        let shared = Arc::new(Shared { jobs, closed: AtomicBool::new(false), accepts: Mutex::default() });
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let weak = Arc::downgrade(&shared);
        std::thread::Builder::new()
            .name("quickjs".into())
            .spawn(move || engine_thread(weak, app_version, versions_json, on_log, inbox, ready_tx))
            .map_err(|error| PipeError::Js(format!("engine thread: {error}")))?;
        ready_rx.recv().map_err(|_| PipeError::RuntimeClosed)??;
        let runtime = Self { shared };
        crate::timers::install(&runtime);
        Ok(runtime)
    }

    pub fn downgrade(&self) -> WeakRuntime {
        WeakRuntime { shared: Arc::downgrade(&self.shared) }
    }

    /// Serves pipes JS opens with exactly `command`, replacing any previous handler.
    pub fn on_accept<F, Fut>(&self, command: &str, handler: F) -> AcceptRegistration
    where
        F: Fn(Pipe) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<(), BoxError>> + 'static,
    {
        self.register(command, false, Arc::new(move |pipe| Box::pin(handler(pipe))))
    }

    /// Serves pipes whose command starts with `prefix`. Exact names win, then the longest prefix.
    pub fn on_accept_prefix<F, Fut>(&self, prefix: &str, handler: F) -> AcceptRegistration
    where
        F: Fn(Pipe) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<(), BoxError>> + 'static,
    {
        assert!(!prefix.is_empty(), "prefix must not be empty");
        self.register(prefix, true, Arc::new(move |pipe| Box::pin(handler(pipe))))
    }

    fn register(&self, key: &str, prefix: bool, handler: Handler) -> AcceptRegistration {
        let mut accepts = self.shared.accepts.lock().unwrap();
        let map = if prefix { &mut accepts.prefix } else { &mut accepts.exact };
        map.insert(key.to_owned(), handler.clone());
        AcceptRegistration { shared: Arc::downgrade(&self.shared), key: key.to_owned(), prefix, handler }
    }

    /// Evaluates the engine bundle. Call exactly once, before any [`Self::open_pipe`]: C captures
    /// the transport globals right after this eval.
    pub async fn eval_engine(&self, script: String) -> Result<(), PipeError> {
        let script = CString::new(script).map_err(|_| PipeError::Js("script contains NUL".into()))?;
        self.shared
            .call(move |engine| {
                let mut err: *mut c_char = ptr::null_mut();
                // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); the script outlives the call.
                let rc = unsafe { ffi::qjs_eval_engine(engine.native.get(), script.as_ptr(), &mut err) };
                if rc == 0 { Ok(()) } else { Err(PipeError::Js(take_c_string(err).unwrap_or_default())) }
            })
            .await?
    }

    /// [`Self::eval_engine`] from [`compile`] output.
    pub async fn eval_engine_bytecode(&self, bytecode: Vec<u8>) -> Result<(), PipeError> {
        self.shared
            .call(move |engine| {
                let mut err: *mut c_char = ptr::null_mut();
                // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); the bytecode outlives the call.
                let rc = unsafe {
                    ffi::qjs_eval_engine_bc(engine.native.get(), bytecode.as_ptr(), bytecode.len(), &mut err)
                };
                if rc == 0 { Ok(()) } else { Err(PipeError::Js(take_c_string(err).unwrap_or_default())) }
            })
            .await?
    }

    /// Evaluates `script`; returns the completion value's string form.
    pub async fn eval(&self, script: String, filename: String) -> Result<Option<String>, PipeError> {
        let script = CString::new(script).map_err(|_| PipeError::Js("script contains NUL".into()))?;
        let filename = CString::new(filename).map_err(|_| PipeError::Js("filename contains NUL".into()))?;
        self.shared
            .call(move |engine| {
                let mut is_error: c_int = 0;
                // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); both strings outlive the call.
                let out = unsafe {
                    ffi::qjs_eval_sync(engine.native.get(), script.as_ptr(), filename.as_ptr(), &mut is_error)
                };
                let text = take_c_string(out);
                if is_error != 0 { Err(PipeError::Js(text.unwrap_or_default())) } else { Ok(text) }
            })
            .await?
    }

    /// Opens a pipe to the JS handler for `command`; fails at the open when JS serves none.
    pub async fn open_pipe(&self, command: &str) -> Result<Pipe, PipeError> {
        let c_command = CString::new(command).map_err(|_| PipeError::Js("command contains NUL".into()))?;
        let (tx, rx) = oneshot::channel();
        let owned = command.to_owned();
        if !self.shared.post(move |engine| {
            let opened = engine.open_now(&c_command, &owned);
            if let Err(Ok(id)) = tx.send(opened) {
                // The caller stopped waiting after the open succeeded: nobody holds the handle.
                engine.release(id);
            }
        }) {
            return Err(PipeError::RuntimeClosed);
        }
        let id = rx.await.map_err(|_| PipeError::RuntimeClosed)??;
        Ok(Pipe { id, command: command.to_owned(), shared: self.shared.clone(), served: false })
    }

    /// Opens `command` with no arguments and returns the peer's whole response, which must have
    /// completed synchronously inside the open (C pumps microtasks in every JS entry). The boot
    /// path for the bundle's `"init"`; a handler that parks is a bundle defect.
    pub async fn open_read_now(&self, command: &str) -> Result<String, PipeError> {
        let c_command = CString::new(command).map_err(|_| PipeError::Js("command contains NUL".into()))?;
        let owned = command.to_owned();
        self.shared
            .call(move |engine| {
                let id = engine.open_now(&c_command, &owned)?;
                engine.close_write_now(id, None);
                let result = engine.take_completed(id, &owned);
                engine.release(id);
                result
            })
            .await?
    }

    /// Opens `"event"` to JS with `name` as the body. No-op when closed or the bundle has no handler.
    pub fn fire_event(&self, name: &str) {
        let runtime = self.clone();
        let body = Bytes::copy_from_slice(name.as_bytes());
        self.shared.post(move |_| {
            tokio::task::spawn_local(async move {
                let Ok(pipe) = runtime.open_pipe("event").await else { return };
                if pipe.send(body).await.is_ok() {
                    let _ = pipe.close_write(None).await;
                }
                let _ = pipe.close_read().await;
            });
        });
    }

    /// Resolves once no pipe is open in either direction (everything in flight is a pipe).
    pub async fn await_idle(&self) {
        if let Ok(Some(rx)) = self.shared.call(|engine| engine.idle_waiter()).await {
            let _ = rx.await;
        }
    }

    /// Binds the `:mse` host and vtable. Call before [`Self::eval_engine`]. JS calls straight into
    /// the host, so the engine keeps `owner` until its C engine is freed.
    ///
    /// # Safety
    /// `host` and `vtable` must stay valid as long as `owner` lives.
    pub async unsafe fn attach_mse_host(
        &self,
        host: usize,
        vtable: usize,
        owner: Arc<dyn Any + Send + Sync>,
    ) -> Result<(), PipeError> {
        self.shared
            .call(move |engine| {
                // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); `owner` keeps both pointers valid and the engine holds it until after qjs_close.
                unsafe { ffi::qjs_set_mse_host(engine.native.get(), host as *mut c_void, vtable as *const c_void) };
                engine.mse_owner.replace(Some(owner));
            })
            .await
    }

    /// Fails everything parked, frees the engine and ends its thread. Idempotent.
    pub async fn close(&self) {
        if self.shared.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        let (tx, rx) = oneshot::channel();
        if self.shared.jobs.send(Msg::Close(tx)).is_ok() {
            let _ = rx.await;
        }
    }

    pub fn is_closed(&self) -> bool {
        self.shared.closed.load(Ordering::Acquire)
    }
}

/// Compiles `script` to bytecode with per-function source text stripped (line numbers kept).
pub fn compile(script: &str, filename: &str) -> Result<Vec<u8>, PipeError> {
    let script = CString::new(script).map_err(|_| PipeError::Js("script contains NUL".into()))?;
    let filename = CString::new(filename).map_err(|_| PipeError::Js("filename contains NUL".into()))?;
    let mut len = 0usize;
    let mut err: *mut c_char = ptr::null_mut();
    // SAFETY: both strings are NUL-terminated and outlive the call; the out-pointers are valid.
    let out = unsafe { ffi::qjs_compile(script.as_ptr(), filename.as_ptr(), &mut len, &mut err) };
    if out.is_null() {
        return Err(PipeError::Js(take_c_string(err).unwrap_or_default()));
    }
    // SAFETY: a non-null result is a malloc'd buffer of `len` bytes.
    let bytes = unsafe { std::slice::from_raw_parts(out, len) }.to_vec();
    // SAFETY: freed once, by the allocator that made it.
    unsafe { ffi::qjs_free(out.cast()) };
    Ok(bytes)
}

/// Copies out and frees a string the C engine returned (malloc'd and NUL-terminated, or null).
fn take_c_string(raw: *mut c_char) -> Option<String> {
    if raw.is_null() {
        return None;
    }
    // SAFETY: every caller passes such a string straight from the C engine.
    let text = unsafe { CStr::from_ptr(raw) }.to_string_lossy().into_owned();
    // SAFETY: freed once, by the allocator that made it.
    unsafe { ffi::qjs_free(raw.cast()) };
    Some(text)
}

/// One end pair of a logical pipe: read what the peer writes, write what the peer reads.
/// Dropping it without closing resets the write side and abandons the read side.
pub struct Pipe {
    id: u32,
    command: String,
    shared: Arc<Shared>,
    /// A served pipe is released by its accept wrapper, after the handler's result is known.
    served: bool,
}

impl std::fmt::Debug for Pipe {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Pipe").field("id", &self.id).field("command", &self.command).finish()
    }
}

enum ReceiveStart {
    Ready(Option<Bytes>),
    Parked(oneshot::Receiver<Result<Option<Bytes>, PipeError>>),
}

enum WriteStep {
    Progress(usize),
    Park(usize, oneshot::Receiver<Result<(), PipeError>>),
}

impl Pipe {
    pub fn command(&self) -> &str {
        &self.command
    }

    /// Up to `max` bytes, or `None` at the peer's clean END. A peer RESET is an error, every time.
    pub async fn receive(&self, max: usize) -> Result<Option<Bytes>, PipeError> {
        let id = self.id;
        match self.shared.call(move |engine| engine.begin_receive(id, max)).await?? {
            ReceiveStart::Ready(chunk) => Ok(chunk),
            ReceiveStart::Parked(rx) => rx.await.map_err(|_| PipeError::RuntimeClosed)?,
        }
    }

    /// Reads until the peer's END.
    pub async fn read_to_end(&self) -> Result<BytesMut, PipeError> {
        let mut out = BytesMut::new();
        while let Some(chunk) = self.receive(PIPE_CAP).await? {
            out.extend_from_slice(&chunk);
        }
        Ok(out)
    }

    /// Exactly `len` bytes; `Ok(None)` when the peer ENDs first.
    pub async fn read_exact(&self, len: usize) -> Result<Option<BytesMut>, PipeError> {
        let mut out = BytesMut::with_capacity(len);
        while out.len() < len {
            match self.receive(len - out.len()).await? {
                Some(chunk) => out.extend_from_slice(&chunk),
                None => return Ok(None),
            }
        }
        Ok(Some(out))
    }

    /// Resolves at the peer's END or RESET, discarding anything it still writes.
    pub async fn wait_end(&self) {
        while let Ok(Some(_)) = self.receive(PIPE_CAP).await {}
    }

    /// Writes all of `data`, parking at the peer's buffer cap until it drains. An aborted send
    /// (error or dropped future) resets the stream: the peer can never misparse a truncated write.
    pub async fn send(&self, data: impl Into<Bytes>) -> Result<(), PipeError> {
        let mut data: Bytes = data.into();
        if data.is_empty() {
            return Ok(());
        }
        let id = self.id;
        self.shared.call(move |engine| engine.begin_send(id)).await??;
        let mut guard = SendGuard { id, shared: &self.shared, finished: false };
        while !data.is_empty() {
            let chunk = data.clone();
            match self.shared.call(move |engine| engine.write_step(id, &chunk)).await?? {
                WriteStep::Progress(written) => data.advance(written),
                WriteStep::Park(written, drained) => {
                    data.advance(written);
                    drained.await.map_err(|_| PipeError::RuntimeClosed)??;
                }
            }
        }
        guard.finished = true;
        self.shared.call(move |engine| engine.end_send(id)).await
    }

    /// Closes the write side: `None` is a clean END, `Some(reason)` a RESET the peer's read throws.
    pub async fn close_write(&self, reason: Option<String>) -> Result<(), PipeError> {
        let id = self.id;
        match self.shared.call(move |engine| engine.close_write_now(id, reason)).await {
            Err(PipeError::RuntimeClosed) => Ok(()),
            other => other,
        }
    }

    /// Abandons the read side; the peer's next write gets EPIPE.
    pub async fn close_read(&self) -> Result<(), PipeError> {
        let id = self.id;
        match self.shared.call(move |engine| engine.close_read_now(id)).await {
            Err(PipeError::RuntimeClosed) => Ok(()),
            other => other,
        }
    }

    /// Teardown of a failed served pipe: abandon reading, RESET with `reason`.
    pub async fn abort(&self, reason: String) {
        let _ = self.close_read().await;
        let _ = self.close_write(Some(reason)).await;
    }
}

impl Drop for Pipe {
    fn drop(&mut self) {
        if !self.served {
            let id = self.id;
            self.shared.post(move |engine| engine.release(id));
        }
    }
}

struct SendGuard<'a> {
    id: u32,
    shared: &'a Shared,
    finished: bool,
}

impl Drop for SendGuard<'_> {
    fn drop(&mut self) {
        if !self.finished {
            let id = self.id;
            self.shared.post(move |engine| engine.abort_send(id));
        }
    }
}

struct Reader {
    max: usize,
    tx: oneshot::Sender<Result<Option<Bytes>, PipeError>>,
}

#[derive(Default)]
struct PipeState {
    inbound: BytesMut,
    write_ended: bool,
    reset_reason: Option<String>,
    read_closed: bool,
    peer_blocked: bool,
    reader: Option<Reader>,
    sending: bool,
    drain_credit: bool,
    peer_read_closed: bool,
    we_ended: bool,
    writer: Option<oneshot::Sender<Result<(), PipeError>>>,
}

impl PipeState {
    fn finished(&self) -> bool {
        self.we_ended && (self.write_ended || self.read_closed)
    }
}

#[derive(Default)]
struct State {
    pipes: HashMap<u32, PipeState>,
    next_id: u32,
    idle_waiters: Vec<oneshot::Sender<()>>,
}

/// Engine-thread confined. Never hold a `state` borrow across an `ffi::qjs_*` call: every one of
/// them can run JS, which re-enters [`inbound`] synchronously.
struct Engine {
    /// The C engine, live until `teardown` nulls it. Only jobs and pipe tasks reach it, and those
    /// run on this thread's loop, which ends at teardown.
    native: Cell<*mut ffi::QjsEngine>,
    /// What keeps the attached MSE host alive; released only after `teardown` closed `native`.
    mse_owner: RefCell<Option<Arc<dyn Any + Send + Sync>>>,
    state: RefCell<State>,
    shared: Weak<Shared>,
    on_log: Option<LogHandler>,
}

fn engine_thread(
    shared: Weak<Shared>,
    app_version: i32,
    versions_json: Option<String>,
    on_log: Option<LogHandler>,
    mut inbox: mpsc::UnboundedReceiver<Msg>,
    ready: std::sync::mpsc::Sender<Result<(), PipeError>>,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(error) => {
            let _ = ready.send(Err(PipeError::Js(format!("engine runtime: {error}"))));
            return;
        }
    };
    let local = tokio::task::LocalSet::new();
    local.block_on(&runtime, async move {
        let engine = Rc::new(Engine {
            native: Cell::new(ptr::null_mut()),
            mse_owner: RefCell::new(None),
            state: RefCell::new(State { next_id: 1, ..State::default() }),
            shared,
            on_log,
        });
        let versions = versions_json.and_then(|json| CString::new(json).ok());
        // SAFETY: `versions` outlives the call; `engine` outlives the C engine, which teardown closes
        // before this block drops it, so `inbound` always finds it at `user`.
        let native = unsafe {
            ffi::qjs_create(
                app_version,
                versions.as_ref().map_or(ptr::null(), |json| json.as_ptr()),
                inbound,
                Rc::as_ptr(&engine) as *mut c_void,
            )
        };
        if native.is_null() {
            let _ = ready.send(Err(PipeError::Js("qjs_create failed".into())));
            return;
        }
        engine.native.set(native);
        let _ = ready.send(Ok(()));
        while let Some(msg) = inbox.recv().await {
            match msg {
                Msg::Job(job) => job(&engine),
                Msg::Close(ack) => {
                    engine.teardown();
                    let _ = ack.send(());
                    return;
                }
            }
        }
        engine.teardown();
    });
}

unsafe extern "C" fn inbound(user: *mut c_void, op: c_int, id: u32, data: *const u8, len: usize) -> c_int {
    // SAFETY: `user` is the Engine handed to qjs_create, alive until the C engine is closed.
    let engine = unsafe { &*(user as *const Engine) };
    // SAFETY: C passes `len` bytes at `data`, valid through this call (quickjs_native.h).
    let bytes: &[u8] = if data.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(data, len) } };
    catch_unwind(AssertUnwindSafe(|| match op {
        ffi::OP_OPEN => engine.on_open(id, &String::from_utf8_lossy(bytes)),
        ffi::OP_WRITE => engine.on_write(id, bytes),
        ffi::OP_CLOSE_WRITE => {
            let reason = (!data.is_null()).then(|| String::from_utf8_lossy(bytes).into_owned());
            engine.on_close_write(id, reason);
            0
        }
        ffi::OP_CLOSE_READ => {
            engine.on_close_read(id);
            0
        }
        ffi::OP_DRAIN => {
            engine.on_drain(id);
            0
        }
        ffi::OP_LOG => {
            if let Some(on_log) = &engine.on_log {
                on_log(LogLevel::from_native(id), &String::from_utf8_lossy(bytes));
            }
            0
        }
        _ => -1,
    }))
    .unwrap_or(-1)
}

impl Engine {
    fn on_open(&self, id: u32, command: &str) -> c_int {
        let Some(shared) = self.shared.upgrade() else { return -1 };
        let Some(handler) = shared.accepts.lock().unwrap().lookup(command) else { return -1 };
        {
            let mut state = self.state.borrow_mut();
            // A colliding id from a buggy bundle must not orphan a live pipe.
            if state.pipes.contains_key(&id) {
                return -1;
            }
            state.pipes.insert(id, PipeState::default());
        }
        let pipe = Pipe { id, command: command.to_owned(), shared: shared.clone(), served: true };
        tokio::task::spawn_local(async move {
            let result = handler(pipe).await;
            shared.post(move |engine| {
                if let Err(error) = result {
                    engine.close_read_now(id);
                    engine.close_write_now(id, Some(error.to_string()));
                }
                engine.release(id);
            });
        });
        0
    }

    fn on_write(&self, id: u32, data: &[u8]) -> c_int {
        let mut state = self.state.borrow_mut();
        let Some(pipe) = state.pipes.get_mut(&id) else { return -1 };
        if pipe.read_closed || pipe.write_ended {
            return -1;
        }
        let mut pos = 0;
        if let Some(reader) = pipe.reader.take() {
            let count = data.len().min(reader.max);
            // A reader whose future was dropped hands the bytes back; they are buffered instead.
            if reader.tx.send(Ok(Some(Bytes::copy_from_slice(&data[..count])))).is_ok() {
                pos = count;
            }
        }
        let room = PIPE_CAP.saturating_sub(pipe.inbound.len());
        let buffered = (data.len() - pos).min(room);
        pipe.inbound.extend_from_slice(&data[pos..pos + buffered]);
        pos += buffered;
        if pos < data.len() {
            pipe.peer_blocked = true;
        }
        pos as c_int
    }

    fn on_close_write(&self, id: u32, reason: Option<String>) {
        {
            let mut state = self.state.borrow_mut();
            let Some(pipe) = state.pipes.get_mut(&id) else { return };
            pipe.write_ended = true;
            let reader = pipe.reader.take();
            match reason {
                Some(reason) => {
                    pipe.inbound.clear();
                    pipe.reset_reason = Some(reason.clone());
                    if let Some(reader) = reader {
                        let _ = reader.tx.send(Err(PipeError::Reset(reason)));
                    }
                }
                None => {
                    if let Some(reader) = reader {
                        let _ = reader.tx.send(Ok(None));
                    }
                }
            }
        }
        self.notify_if_idle();
    }

    fn on_close_read(&self, id: u32) {
        let mut state = self.state.borrow_mut();
        let Some(pipe) = state.pipes.get_mut(&id) else { return };
        pipe.peer_read_closed = true;
        if let Some(writer) = pipe.writer.take() {
            let _ = writer.send(Err(PipeError::BrokenPipe));
        }
    }

    fn on_drain(&self, id: u32) {
        let mut state = self.state.borrow_mut();
        let Some(pipe) = state.pipes.get_mut(&id) else { return };
        let woke = pipe.writer.take().is_some_and(|writer| writer.send(Ok(())).is_ok());
        if !woke {
            pipe.drain_credit = true;
        }
    }

    fn open_now(&self, command: &CStr, name: &str) -> Result<u32, PipeError> {
        let id = {
            let mut state = self.state.borrow_mut();
            let id = state.next_id;
            state.next_id += 2;
            // Registered before the open so anything crossing during the call finds the pipe.
            state.pipes.insert(id, PipeState::default());
            id
        };
        // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); no state borrow is held.
        let rc = unsafe { ffi::qjs_open(self.native.get(), id, command.as_ptr()) };
        if rc < 0 {
            self.state.borrow_mut().pipes.remove(&id);
            self.notify_if_idle();
            return Err(PipeError::Refused(name.to_owned()));
        }
        Ok(id)
    }

    fn take_completed(&self, id: u32, command: &str) -> Result<String, PipeError> {
        let mut state = self.state.borrow_mut();
        let pipe = state.pipes.get_mut(&id).ok_or(PipeError::RuntimeClosed)?;
        if let Some(reason) = &pipe.reset_reason {
            return Err(PipeError::Reset(reason.clone()));
        }
        if !pipe.write_ended {
            return Err(PipeError::Js(format!("\"{command}\" did not complete synchronously")));
        }
        let body = pipe.inbound.split();
        String::from_utf8(body.to_vec()).map_err(|_| PipeError::Js(format!("\"{command}\" answered non-UTF-8")))
    }

    fn begin_receive(&self, id: u32, max: usize) -> Result<ReceiveStart, PipeError> {
        let (chunk, drain) = {
            let mut state = self.state.borrow_mut();
            let pipe = state.pipes.get_mut(&id).ok_or(PipeError::RuntimeClosed)?;
            if pipe.read_closed {
                return Err(PipeError::Misuse("receive() after the read side was closed"));
            }
            if let Some(reason) = &pipe.reset_reason {
                return Err(PipeError::Reset(reason.clone()));
            }
            if max == 0 {
                return Ok(ReceiveStart::Ready(Some(Bytes::new())));
            }
            if pipe.inbound.is_empty() {
                if pipe.write_ended {
                    return Ok(ReceiveStart::Ready(None));
                }
                if pipe.reader.as_ref().is_some_and(|reader| !reader.tx.is_closed()) {
                    return Err(PipeError::Misuse("concurrent receive() on the same pipe"));
                }
                let (tx, rx) = oneshot::channel();
                pipe.reader = Some(Reader { max, tx });
                return Ok(ReceiveStart::Parked(rx));
            }
            let count = pipe.inbound.len().min(max);
            let chunk = pipe.inbound.split_to(count).freeze();
            let drain = pipe.peer_blocked && pipe.inbound.len() <= PIPE_LOW;
            if drain {
                pipe.peer_blocked = false;
            }
            (chunk, drain)
        };
        if drain {
            // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); no state borrow is held.
            unsafe { ffi::qjs_drain(self.native.get(), id) };
        }
        Ok(ReceiveStart::Ready(Some(chunk)))
    }

    fn begin_send(&self, id: u32) -> Result<(), PipeError> {
        let mut state = self.state.borrow_mut();
        let pipe = state.pipes.get_mut(&id).ok_or(PipeError::RuntimeClosed)?;
        if pipe.sending {
            return Err(PipeError::Misuse("concurrent send() on the same pipe"));
        }
        if pipe.we_ended {
            return Err(PipeError::SendClosed("send() after close"));
        }
        pipe.sending = true;
        pipe.drain_credit = false;
        Ok(())
    }

    fn write_step(&self, id: u32, data: &[u8]) -> Result<WriteStep, PipeError> {
        {
            let state = self.state.borrow();
            let pipe = state.pipes.get(&id).ok_or(PipeError::RuntimeClosed)?;
            if pipe.peer_read_closed {
                return Err(PipeError::BrokenPipe);
            }
        }
        // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); `data` outlives the call and no state borrow is held.
        let written = unsafe { ffi::qjs_write(self.native.get(), id, data.as_ptr(), data.len()) };
        let mut state = self.state.borrow_mut();
        let pipe = state.pipes.get_mut(&id).ok_or(PipeError::RuntimeClosed)?;
        if written < 0 {
            pipe.peer_read_closed = true;
            return Err(PipeError::BrokenPipe);
        }
        let written = written as usize;
        if written == data.len() {
            return Ok(WriteStep::Progress(written));
        }
        // The write's own microtask pump may already have drained the peer (credit) or closed
        // its read end; parking after either would never wake.
        if pipe.drain_credit {
            pipe.drain_credit = false;
            return Ok(WriteStep::Progress(written));
        }
        if pipe.peer_read_closed {
            return Err(PipeError::BrokenPipe);
        }
        let (tx, rx) = oneshot::channel();
        pipe.writer = Some(tx);
        Ok(WriteStep::Park(written, rx))
    }

    fn end_send(&self, id: u32) {
        if let Some(pipe) = self.state.borrow_mut().pipes.get_mut(&id) {
            pipe.sending = false;
        }
    }

    fn abort_send(&self, id: u32) {
        let reset = {
            let mut state = self.state.borrow_mut();
            let Some(pipe) = state.pipes.get_mut(&id) else { return };
            pipe.sending = false;
            pipe.writer = None;
            let reset = !pipe.we_ended && !pipe.peer_read_closed;
            if reset {
                pipe.we_ended = true;
            }
            reset
        };
        if reset {
            let reason = CString::new("send aborted mid-stream").unwrap();
            // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); no state borrow is held.
            unsafe { ffi::qjs_close_write(self.native.get(), id, reason.as_ptr()) };
            self.notify_if_idle();
        }
    }

    fn close_write_now(&self, id: u32, reason: Option<String>) {
        let (reason, writer) = {
            let mut state = self.state.borrow_mut();
            let Some(pipe) = state.pipes.get_mut(&id) else { return };
            if pipe.we_ended {
                return;
            }
            pipe.we_ended = true;
            let writer = pipe.writer.take();
            // An END while a send is parked would truncate the peer's stream: RESET instead.
            let reason = reason.or_else(|| writer.as_ref().map(|_| "closed while a send was in progress".to_owned()));
            (reason, writer)
        };
        let reason = reason.map(|text| CString::new(text.replace('\0', " ")).unwrap());
        // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); no state borrow is held.
        unsafe {
            ffi::qjs_close_write(self.native.get(), id, reason.as_ref().map_or(ptr::null(), |text| text.as_ptr()))
        };
        if let Some(writer) = writer {
            let _ = writer.send(Err(PipeError::SendClosed("send aborted by close")));
        }
        self.notify_if_idle();
    }

    fn close_read_now(&self, id: u32) {
        {
            let mut state = self.state.borrow_mut();
            let Some(pipe) = state.pipes.get_mut(&id) else { return };
            if pipe.read_closed {
                return;
            }
            pipe.read_closed = true;
            pipe.inbound.clear();
            if let Some(reader) = pipe.reader.take() {
                let _ = reader.tx.send(Err(PipeError::Misuse("receive() after the read side was closed")));
            }
        }
        // SAFETY: on the engine thread before teardown, so `native` is live (Engine::native); no state borrow is held.
        unsafe { ffi::qjs_close_read(self.native.get(), id) };
        self.notify_if_idle();
    }

    /// The last handle is gone: whatever is still open is abandoned.
    fn release(&self, id: u32) {
        let (needs_reset, needs_close_read) = {
            let state = self.state.borrow();
            let Some(pipe) = state.pipes.get(&id) else { return };
            (!pipe.we_ended, !pipe.read_closed)
        };
        if needs_close_read {
            self.close_read_now(id);
        }
        if needs_reset {
            self.close_write_now(id, Some("pipe dropped without close".to_owned()));
        }
        self.state.borrow_mut().pipes.remove(&id);
        self.notify_if_idle();
    }

    fn is_idle(state: &State) -> bool {
        state.pipes.values().all(PipeState::finished)
    }

    fn idle_waiter(&self) -> Option<oneshot::Receiver<()>> {
        let mut state = self.state.borrow_mut();
        if Self::is_idle(&state) {
            return None;
        }
        let (tx, rx) = oneshot::channel();
        state.idle_waiters.push(tx);
        Some(rx)
    }

    fn notify_if_idle(&self) {
        let waiters = {
            let mut state = self.state.borrow_mut();
            if state.idle_waiters.is_empty() || !Self::is_idle(&state) {
                return;
            }
            std::mem::take(&mut state.idle_waiters)
        };
        for waiter in waiters {
            let _ = waiter.send(());
        }
    }

    fn teardown(&self) {
        let native = self.native.replace(ptr::null_mut());
        if native.is_null() {
            return;
        }
        if let Some(shared) = self.shared.upgrade() {
            shared.closed.store(true, Ordering::Release);
        }
        let (pipes, waiters) = {
            let mut state = self.state.borrow_mut();
            (std::mem::take(&mut state.pipes), std::mem::take(&mut state.idle_waiters))
        };
        for (_, mut pipe) in pipes {
            if let Some(reader) = pipe.reader.take() {
                let _ = reader.tx.send(Err(PipeError::RuntimeClosed));
            }
            if let Some(writer) = pipe.writer.take() {
                let _ = writer.send(Err(PipeError::RuntimeClosed));
            }
        }
        for waiter in waiters {
            let _ = waiter.send(());
        }
        // SAFETY: the live engine, closed once: `native` was swapped for null above.
        unsafe { ffi::qjs_close(native) };
        drop(self.mse_owner.take());
    }
}
