//! Signing in: session cookies from a paste or from the phone app's device link go into the
//! shared cookie jar, then the engine's cookie-only gate says whether they sign in.

use std::sync::Arc;

use ym_common::log::Logger;
use ym_engine::{CookieJar, EngineError, EngineManager};
use ym_remote_config::RemoteConfig;
use ym_signin::{DeviceLinkOffer, LinkError, SessionCookie, import_domain, to_set_cookie};

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

/// Stores `cookies`, each under its own domain, then reads the engine's cookie-only gate. No
/// network call before the gate passes: YouTube answers one by revoking a half-set jar.
pub async fn sign_in_with_cookies(
    cookies: Vec<SessionCookie>,
    jar: Arc<dyn CookieJar>,
    engines: Arc<EngineManager>,
    log: &Logger,
) -> Result<(), SignInError> {
    let result = import_and_check(cookies, jar, engines, log).await;
    match &result {
        Err(SignInError::Engine(error)) if error.kind == ym_engine::ErrorKind::Network => {
            log.warn("cookie import failed", Some(error));
        }
        Err(error @ (SignInError::Engine(_) | SignInError::Store(_))) => {
            log.capture_error("cookie import failed", error)
        }
        _ => {}
    }
    result
}

async fn import_and_check(
    cookies: Vec<SessionCookie>,
    jar: Arc<dyn CookieJar>,
    engines: Arc<EngineManager>,
    log: &Logger,
) -> Result<(), SignInError> {
    if !ym_signin::looks_signed_in(&cookies) {
        return Err(SignInError::NotSignedIn);
    }
    tokio::task::spawn_blocking(move || {
        for cookie in &cookies {
            let url =
                url::Url::parse(&format!("https://{}/", import_domain(cookie))).map_err(|error| error.to_string())?;
            jar.save_from_response(&url, &[to_set_cookie(cookie)]).map_err(|error| error.to_string())?;
        }
        Ok::<_, String>(())
    })
    .await
    .map_err(|error| SignInError::Store(error.to_string()))?
    .map_err(SignInError::Store)?;
    if !engines.current().login_status(log).await? {
        return Err(SignInError::NotSignedIn);
    }
    Ok(())
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
) -> Result<(), SignInError> {
    let received = ym_signin::await_cookies(&service, &offer).await;
    ym_signin::close_offer(&service, &offer).await;
    let cookies = received.inspect_err(|error| log.warn("device-link offer/poll failed", Some(error)))?;
    sign_in_with_cookies(cookies, jar, engines, log).await
}

/// The JVM AccountController.signOut: the bundle's logout (it drops the auth cookies and keeps the
/// visitor ones), then an anonymous session refresh. The caller shows signed-out either way.
pub async fn sign_out(engines: Arc<EngineManager>, log: &Logger) {
    let engine = engines.current();
    if let Err(error) = engine.logout(log).await {
        if error.kind == ym_engine::ErrorKind::Network {
            log.warn("logout failed, the jar may stay authenticated", Some(&error));
        } else {
            log.capture_error("logout failed, the jar may stay authenticated", &error);
        }
    }
    if let Err(error) = engine.refresh_session(log).await {
        log.warn("post-logout session refresh failed", Some(&error));
    }
}
