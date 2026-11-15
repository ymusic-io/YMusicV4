use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicI64, Ordering};

use serde_json::{Map, Value};
use ym_common::log::{Crumb, Logger, now_millis};

/// Callers' loggers parked for a command's lifetime under the `logKey` sent in its head, so a host
/// protocol serving that command can graft the JS trail onto the caller's.
pub struct ParkedLoggers {
    root: Logger,
    parked: Mutex<HashMap<i64, Logger>>,
    next: AtomicI64,
}

impl ParkedLoggers {
    pub fn new(root: Logger) -> Self {
        Self { root, parked: Mutex::default(), next: AtomicI64::new(0) }
    }

    pub fn root(&self) -> &Logger {
        &self.root
    }

    pub fn park(&self, logger: Logger) -> i64 {
        let key = self.next.fetch_add(1, Ordering::Relaxed) + 1;
        self.parked.lock().unwrap().insert(key, logger);
        key
    }

    pub fn unpark(&self, key: i64) {
        self.parked.lock().unwrap().remove(&key);
    }

    /// The parked logger, or the root for a late or unknown key.
    pub fn resolve(&self, key: i64) -> Logger {
        self.parked.lock().unwrap().get(&key).cloned().unwrap_or_else(|| self.root.clone())
    }
}

/// The caller's logger for a protocol request: its parked logger (or the root when the head has
/// no key) + the JS trail from the head's `log` field + this protocol's own hop.
pub(crate) fn protocol_logger(
    parked: &ParkedLoggers,
    head: &Value,
    tag: &str,
    props: Option<Map<String, Value>>,
) -> Logger {
    let wire = head.get("log");
    let caller = match wire.and_then(|log| log.get("key")).and_then(Value::as_i64) {
        Some(key) => parked.resolve(key),
        None => parked.root().clone(),
    };
    caller.fork_all(read_trail(wire)).fork_crumb(Crumb::new(tag, props))
}

/// A head's string field as a one-entry prop map, query string stripped (never log query params).
pub(crate) fn prop_of(head: &Value, field: &str) -> Option<Map<String, Value>> {
    let value = head.get(field)?.as_str()?;
    let stripped = value.split('?').next().unwrap_or_default();
    Some(Map::from_iter([(field.to_owned(), Value::String(stripped.to_owned()))]))
}

fn read_trail(wire: Option<&Value>) -> Vec<Crumb> {
    let Some(trail) = wire.and_then(|log| log.get("trail")).and_then(Value::as_array) else { return Vec::new() };
    let grafted_at = now_millis();
    trail
        .iter()
        .filter_map(|entry| {
            let entry = entry.as_array()?;
            let tag = entry.first()?.as_str()?.to_owned();
            let props = entry.get(1).and_then(Value::as_object).map(|props| {
                props
                    .iter()
                    .filter(|(_, value)| !value.is_array() && !value.is_object())
                    .map(|(k, v)| (k.clone(), v.clone()))
                    .collect::<Map<_, _>>()
            });
            // Older bundles send no time; a time past the graft is a skewed JS clock.
            let at_millis =
                entry.get(2).and_then(Value::as_i64).filter(|at| (1..=grafted_at).contains(at)).unwrap_or(grafted_at);
            Some(Crumb { tag, props: props.filter(|props| !props.is_empty()), at_millis })
        })
        .collect()
}
