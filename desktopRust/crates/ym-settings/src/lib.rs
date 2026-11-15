//! The typed key-value settings store shared by the UI and the engine bundle (`settings/get|set`),
//! and `app.db`, the desktop app's own database (settings rows + secrets).

mod app_db;

use std::collections::HashMap;
use std::sync::Mutex;

pub use app_db::AppDb;

pub type SettingsError = Box<dyn std::error::Error + Send + Sync>;

#[derive(Debug, Clone, PartialEq)]
pub enum SettingValue {
    String(String),
    Boolean(bool),
    Int(i32),
}

impl SettingValue {
    /// The stored text: values are kept as text and parsed by the type a reader asks for.
    pub fn to_raw(&self) -> String {
        match self {
            SettingValue::String(value) => value.clone(),
            SettingValue::Boolean(value) => value.to_string(),
            SettingValue::Int(value) => value.to_string(),
        }
    }
}

/// `value` as Kotlin's `Float.toString` writes it (`1.0`, not `1`), the JVM app's float settings.
pub fn kotlin_float(value: f32) -> String {
    let text = value.to_string();
    if text.contains(['.', 'e', 'N', 'i']) { text } else { format!("{text}.0") }
}

/// Reads answer from memory; writes may block on disk, so call `put` off the UI and engine threads.
pub trait SettingsStore: Send + Sync {
    fn get_raw(&self, key: &str) -> Result<Option<String>, SettingsError>;
    fn put_raw(&self, key: &str, value: String) -> Result<(), SettingsError>;

    fn put(&self, key: &str, value: SettingValue) -> Result<(), SettingsError> {
        self.put_raw(key, value.to_raw())
    }

    fn string(&self, key: &str, default: &str) -> Result<String, SettingsError> {
        Ok(self.get_raw(key)?.unwrap_or_else(|| default.to_owned()))
    }

    /// A stored value of another type reads as the default.
    fn boolean(&self, key: &str, default: bool) -> Result<bool, SettingsError> {
        Ok(self.get_raw(key)?.and_then(|raw| raw.parse().ok()).unwrap_or(default))
    }

    fn int(&self, key: &str, default: i32) -> Result<i32, SettingsError> {
        Ok(self.get_raw(key)?.and_then(|raw| raw.parse().ok()).unwrap_or(default))
    }
}

#[derive(Default)]
pub struct MemorySettings {
    values: Mutex<HashMap<String, String>>,
}

impl SettingsStore for MemorySettings {
    fn get_raw(&self, key: &str) -> Result<Option<String>, SettingsError> {
        Ok(self.values.lock().unwrap().get(key).cloned())
    }

    fn put_raw(&self, key: &str, value: String) -> Result<(), SettingsError> {
        self.values.lock().unwrap().insert(key.to_owned(), value);
        Ok(())
    }
}
