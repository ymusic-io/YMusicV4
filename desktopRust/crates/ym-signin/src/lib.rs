//! Signing in without a browser engine: session cookies arrive from the phone app (an encrypted
//! one-shot handoff through the YMusic service, started by a QR the desktop shows) or from a paste
//! (a browser's Copy as cURL, a Cookie header, or a cookie export), and are kept sealed at rest.

#[cfg(windows)]
mod dpapi;
mod link;
mod paste;
#[cfg(target_os = "linux")]
mod secret_service;
mod vault;

#[cfg(windows)]
pub use dpapi::{dpapi_protect, dpapi_unprotect};

pub use link::{DeviceLinkOffer, LinkError, await_cookies, close_offer, create_offer, decrypt, encrypt, post_cookies};
pub use paste::{SessionCookie, looks_like_curl, looks_signed_in, parse_cookie_paste, to_set_cookie};
#[cfg(target_os = "linux")]
pub use secret_service::{SecretServiceError, secret_service_cookie_key};
#[cfg(target_os = "macos")]
pub use vault::os_cookie_key;
pub use vault::{CookieVault, PasswordSeal, VaultError, random_key};
