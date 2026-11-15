//! Signing in: session cookies from a paste or from the phone app's device link go into the
//! shared cookie jar, then the engine refreshes its session under the new identity.

use std::sync::Arc;

use ym_common::log::Logger;
use ym_engine::yt::YtAccountInfo;
use ym_engine::{CookieJar, EngineError, EngineManager};
use ym_remote_config::RemoteConfig;
use ym_signin::{DeviceLinkOffer, LinkError, SessionCookie, to_set_cookie};

/// Someone signed in or out; every feed reloads under the new identity.
pub struct AccountChanged;

#[derive(Debug, thiserror::Error)]
pub enum SignInError {
    /// Nothing in the paste or handoff looks like a signed-in session.
    #[error("no sign-in cookies")]
    NotSignedIn,
    #[error(transparent)]
    Link(#[from] LinkError),
    #[error(transparent)]
    Engine(#[from] EngineError),
    #[error("cookie store: {0}")]
    Store(String),
}

/// Stores `cookies` as if their sites had set them, then asks the engine who is signed in.
pub async fn sign_in_with_cookies(
    cookies: Vec<SessionCookie>,
    jar: Arc<dyn CookieJar>,
    engines: Arc<EngineManager>,
    log: &Logger,
) -> Result<YtAccountInfo, SignInError> {
    if !ym_signin::looks_signed_in(&cookies) {
        return Err(SignInError::NotSignedIn);
    }
    tokio::task::spawn_blocking(move || {
        for cookie in &cookies {
            let host = if cookie.domain.ends_with("google.com") { "accounts.google.com" } else { "www.youtube.com" };
            let url = url::Url::parse(&format!("https://{host}/")).expect("static url");
            jar.save_from_response(&url, &[to_set_cookie(cookie)]).map_err(|error| error.to_string())?;
        }
        Ok::<_, String>(())
    })
    .await
    .map_err(|error| SignInError::Store(error.to_string()))?
    .map_err(SignInError::Store)?;
    let engine = engines.current();
    engine.refresh_session(log).await?;
    let account = engine.account_info(true, log).await?;
    if !account.signed_in {
        return Err(SignInError::NotSignedIn);
    }
    Ok(account)
}

/// Opens a relay slot for the phone app: show `offer.qr_payload()` as a QR and
/// `offer.confirmation_code()` beside it, then [`await_device_link`].
pub async fn start_device_link(service: &RemoteConfig) -> Result<DeviceLinkOffer, SignInError> {
    Ok(ym_signin::create_offer(service).await?)
}

/// Waits for the phone's cookies and signs in with them; the slot is closed either way.
pub async fn await_device_link(
    service: Arc<RemoteConfig>,
    offer: DeviceLinkOffer,
    jar: Arc<dyn CookieJar>,
    engines: Arc<EngineManager>,
    log: &Logger,
) -> Result<YtAccountInfo, SignInError> {
    let received = ym_signin::await_cookies(&service, &offer).await;
    ym_signin::close_offer(&service, &offer).await;
    sign_in_with_cookies(received?, jar, engines, log).await
}

/// Signs out on YouTube's side, then forgets every cookie.
pub async fn sign_out(jar: Arc<dyn CookieJar>, engines: Arc<EngineManager>, log: &Logger) -> Result<(), SignInError> {
    engines.current().logout(log).await?;
    tokio::task::spawn_blocking(move || jar.clear().map_err(|error| error.to_string()))
        .await
        .map_err(|error| SignInError::Store(error.to_string()))?
        .map_err(SignInError::Store)
}
