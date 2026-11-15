//! `settings/get` / `settings/set` (HOST_PROTOCOLS §3.5) on the app's live store, and
//! `remote-config/get` (§3.5.1) on the config the app holds now.

use std::sync::Arc;

use serde_json::{Value, json};
use ym_quickjs::{BoxError, QuickJsRuntime};
use ym_settings::{SettingValue, SettingsStore};

use crate::logs::ParkedLoggers;
use crate::protocol::{Codes, Request, ServeError, serve};

const SETTINGS: Codes = Codes { request: "R20012", failure: "R20013" };
const REMOTE_CONFIG: Codes = Codes { request: "R20014", failure: "R20014" };

/// A remote-config `features` value by key, answered from the config held now.
pub type FeatureLookup = Arc<dyn Fn(&str) -> Option<Value> + Send + Sync>;

pub(crate) fn install(runtime: &QuickJsRuntime, store: Arc<dyn SettingsStore>, parked: Arc<ParkedLoggers>) {
    let (get_store, get_parked) = (store.clone(), parked.clone());
    runtime.on_accept("settings/get", move |pipe| {
        let (store, parked) = (get_store.clone(), get_parked.clone());
        async move {
            serve(pipe, parked, "settings/get", SETTINGS, &["key"], async |request: &Request<'_>| {
                let key = request.string("key")?;
                let default = typed(request, "default")?;
                let value = tokio::task::spawn_blocking(move || -> Result<Value, BoxError> {
                    Ok(match default {
                        SettingValue::String(default) => json!(store.string(&key, &default)?),
                        SettingValue::Boolean(default) => json!(store.boolean(&key, default)?),
                        SettingValue::Int(default) => json!(store.int(&key, default)?),
                    })
                })
                .await?
                .map_err(ServeError::Failure)?;
                request.send_json(&json!({ "value": value })).await
            })
            .await
        }
    });
    runtime.on_accept("settings/set", move |pipe| {
        let (store, parked) = (store.clone(), parked.clone());
        async move {
            serve(pipe, parked, "settings/set", SETTINGS, &["key"], async |request: &Request<'_>| {
                let key = request.string("key")?;
                let value = typed(request, "value")?;
                tokio::task::spawn_blocking(move || store.put(&key, value)).await?.map_err(ServeError::Failure)
            })
            .await
        }
    });
}

/// `field` read as the head's declared `type`.
fn typed(request: &Request<'_>, field: &str) -> Result<SettingValue, ServeError> {
    let kind = request.string("type")?;
    let raw = request
        .head
        .get(field)
        .filter(|value| !value.is_null() && !value.is_array() && !value.is_object())
        .ok_or_else(|| ServeError::Request(format!("settings request missing '{field}'")))?;
    let invalid = || ServeError::Request(format!("settings request has an invalid '{field}'"));
    Ok(match kind.as_str() {
        "string" => SettingValue::String(raw.as_str().map_or_else(|| raw.to_string(), str::to_owned)),
        "boolean" => SettingValue::Boolean(raw.as_bool().ok_or_else(invalid)?),
        "int" => SettingValue::Int(raw.as_i64().and_then(|n| i32::try_from(n).ok()).ok_or_else(invalid)?),
        _ => return Err(ServeError::Request("settings request has an invalid 'type'".into())),
    })
}

pub(crate) fn install_remote_config(runtime: &QuickJsRuntime, lookup: FeatureLookup, parked: Arc<ParkedLoggers>) {
    runtime.on_accept("remote-config/get", move |pipe| {
        let (lookup, parked) = (lookup.clone(), parked.clone());
        async move {
            serve(pipe, parked, "remote-config/get", REMOTE_CONFIG, &["key"], async |request: &Request<'_>| {
                let key = request.string("key")?;
                let body = match lookup(&key) {
                    Some(value) if !value.is_null() => json!({ "value": value }),
                    _ => json!({}),
                };
                request.send_json(&body).await
            })
            .await
        }
    });
}
