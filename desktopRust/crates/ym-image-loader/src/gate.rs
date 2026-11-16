//! The JVM `LifoGate.kt`: a semaphore whose waiters are served newest first (a stack, not a queue).
//! In a scrolling list the most recently requested images are the ones on screen, so they jump ahead
//! of stale requests still waiting. A dropped waiter leaves the stack, so a scrolled-away item never
//! occupies a slot.

use std::sync::{Arc, Mutex, MutexGuard};

use tokio::sync::oneshot;

pub(crate) struct LifoGate {
    state: Arc<Mutex<State>>,
}

struct State {
    free: usize,
    next_id: u64,
    /// Newest last.
    waiters: Vec<(u64, oneshot::Sender<()>)>,
}

/// A held slot; dropping it hands the slot to the newest waiter.
pub(crate) struct Permit {
    state: Arc<Mutex<State>>,
}

impl LifoGate {
    pub fn new(permits: usize) -> Self {
        Self { state: Arc::new(Mutex::new(State { free: permits, next_id: 0, waiters: Vec::new() })) }
    }

    pub async fn acquire(&self) -> Permit {
        loop {
            let (id, receiver) = {
                let mut state = lock(&self.state);
                if state.free > 0 {
                    state.free -= 1;
                    return Permit { state: self.state.clone() };
                }
                let (sender, receiver) = oneshot::channel();
                state.next_id += 1;
                let id = state.next_id;
                state.waiters.push((id, sender));
                (id, receiver)
            };
            let mut waiting = Waiting { state: self.state.clone(), id, receiver: Some(receiver) };
            let granted = match waiting.receiver.as_mut() {
                Some(receiver) => receiver.await.is_ok(),
                None => false,
            };
            waiting.receiver = None;
            if granted {
                return Permit { state: self.state.clone() };
            }
        }
    }
}

/// A waiter's cleanup when its acquire is dropped before the grant arrives.
struct Waiting {
    state: Arc<Mutex<State>>,
    id: u64,
    receiver: Option<oneshot::Receiver<()>>,
}

impl Drop for Waiting {
    fn drop(&mut self) {
        let Some(mut receiver) = self.receiver.take() else { return };
        lock(&self.state).waiters.retain(|(id, _)| *id != self.id);
        // The grant and the drop race once: a grant that already landed is passed on.
        receiver.close();
        if receiver.try_recv().is_ok() {
            release(&self.state);
        }
    }
}

impl Drop for Permit {
    fn drop(&mut self) {
        release(&self.state);
    }
}

fn release(state: &Arc<Mutex<State>>) {
    loop {
        let next = {
            let mut state = lock(state);
            match state.waiters.pop() {
                Some((_, sender)) => sender,
                None => {
                    state.free += 1;
                    return;
                }
            }
        };
        // A send to a waiter that was dropped after we popped it fails: grant the next one instead.
        if next.send(()).is_ok() {
            return;
        }
    }
}

fn lock(state: &Mutex<State>) -> MutexGuard<'_, State> {
    state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}
