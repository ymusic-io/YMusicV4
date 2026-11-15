use std::time::Duration;

use base64::Engine as _;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use ym_remote_config::{RelayError, RemoteConfig};

use crate::paste::SessionCookie;

const QR_PREFIX: &str = "ymusic-link:2:";
const QR_KEY: &str = "k=";
const KEY_LEN: usize = 32;
const IV_LEN: usize = 16;
const MAC_LEN: usize = 32;
const POLL_INTERVAL: Duration = Duration::from_secs(2);
/// The service keeps a slot open this long.
const LINK_TTL: Duration = Duration::from_secs(600);
/// Consecutive failed polls before a wait gives up: one stalled poll must not end it.
const MAX_FAILED_POLLS: u32 = 3;
const FIELD: char = '\u{1f}';

/// What a link failure is to the user: each is a localized message at the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum LinkError {
    #[error("network")]
    Network,
    #[error("timeout")]
    Timeout,
    #[error("bad-payload")]
    BadPayload,
}

/// A relay slot this desktop opened: its id on the service and the key, both in the QR. The QR
/// carries no url: the phone sends to its own build's service, so a forged QR cannot redirect it.
#[derive(Debug, Clone)]
pub struct DeviceLinkOffer {
    pub id: String,
    pub key: [u8; KEY_LEN],
}

fn base64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn decode_base64url(text: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(text.trim().trim_end_matches('=')).ok()
}

fn valid_id(id: &str) -> bool {
    id.len() == 22 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

impl DeviceLinkOffer {
    pub fn qr_payload(&self) -> String {
        format!("{QR_PREFIX}{}|{QR_KEY}{}", self.id, base64url(&self.key))
    }

    /// The code both screens show so the user can tell the phone is talking to this desktop.
    pub fn confirmation_code(&self) -> String {
        let mac = hmac(&self.key, b"sas");
        let number = u32::from_be_bytes([mac[0], mac[1], mac[2], mac[3]]) as u64 % 1_000_000;
        let digits = format!("{number:06}");
        format!("{}\u{a0}{}", &digits[..3], &digits[3..])
    }

    pub fn parse_qr(raw: &str) -> Option<(String, [u8; KEY_LEN])> {
        let rest = raw.trim().strip_prefix(QR_PREFIX)?;
        let (id, key) = rest.split_once(&format!("|{QR_KEY}"))?;
        if !valid_id(id) {
            return None;
        }
        Some((id.to_owned(), decode_base64url(key)?.try_into().ok()?))
    }
}

fn relay_error(error: RelayError) -> LinkError {
    match error {
        RelayError::Status(404) => LinkError::Timeout,
        _ => LinkError::Network,
    }
}

pub async fn create_offer(service: &RemoteConfig) -> Result<DeviceLinkOffer, LinkError> {
    let id = service.open_device_link().await.map_err(relay_error)?;
    if !valid_id(&id) {
        return Err(LinkError::BadPayload);
    }
    let mut key = [0u8; KEY_LEN];
    getrandom::getrandom(&mut key).map_err(|_| LinkError::Network)?;
    Ok(DeviceLinkOffer { id, key })
}

/// Gives the slot up; it also expires on the service, so a failure is moot.
pub async fn close_offer(service: &RemoteConfig, offer: &DeviceLinkOffer) {
    let _ = service.close_device_link(&offer.id).await;
}

/// Polls the slot until the phone's cookies arrive, the slot expires, or the service stays down.
pub async fn await_cookies(service: &RemoteConfig, offer: &DeviceLinkOffer) -> Result<Vec<SessionCookie>, LinkError> {
    let mut waited = Duration::ZERO;
    let mut failed = 0;
    while waited <= LINK_TTL {
        match service.take_device_link(&offer.id).await {
            Ok(Some(raw)) => {
                let blob = decode_base64url(&raw).ok_or(LinkError::BadPayload)?;
                let cookies = decode_cookies(&decrypt(&offer.key, &blob)?);
                return if cookies.is_empty() { Err(LinkError::BadPayload) } else { Ok(cookies) };
            }
            Ok(None) => failed = 0,
            Err(error) => match relay_error(error) {
                LinkError::Network if failed + 1 < MAX_FAILED_POLLS => failed += 1,
                other => return Err(other),
            },
        }
        tokio::time::sleep(POLL_INTERVAL).await;
        waited += POLL_INTERVAL;
    }
    Err(LinkError::Timeout)
}

/// The sending side (this device signed in, another scanned its QR).
pub async fn post_cookies(
    service: &RemoteConfig,
    id: &str,
    key: &[u8; KEY_LEN],
    cookies: &[SessionCookie],
) -> Result<(), LinkError> {
    if !valid_id(id) {
        return Err(LinkError::BadPayload);
    }
    let blob = encrypt(key, &encode_cookies(cookies))?;
    match service.put_device_link(id, base64url(&blob)).await {
        Ok(true) => Ok(()),
        Ok(false) => Err(LinkError::Timeout),
        Err(error) => Err(relay_error(error)),
    }
}

fn hmac(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC takes any key length");
    mac.update(data);
    mac.finalize().into_bytes().into()
}

fn keystream_xor(enc_key: &[u8], iv: &[u8], data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len());
    for (block, chunk) in data.chunks(32).enumerate() {
        let mut input = iv.to_vec();
        input.extend_from_slice(&(block as u32).to_be_bytes());
        let stream = hmac(enc_key, &input);
        out.extend(chunk.iter().zip(stream).map(|(byte, key)| byte ^ key));
    }
    out
}

/// `iv | ciphertext | HMAC(iv | ciphertext)`, keys derived from the QR key.
pub fn encrypt(key: &[u8; KEY_LEN], plaintext: &[u8]) -> Result<Vec<u8>, LinkError> {
    let mut iv = [0u8; IV_LEN];
    getrandom::getrandom(&mut iv).map_err(|_| LinkError::Network)?;
    let ciphertext = keystream_xor(&hmac(key, b"enc"), &iv, plaintext);
    let mut out = iv.to_vec();
    out.extend_from_slice(&ciphertext);
    let mac = hmac(&hmac(key, b"mac"), &out);
    out.extend_from_slice(&mac);
    Ok(out)
}

pub fn decrypt(key: &[u8; KEY_LEN], blob: &[u8]) -> Result<Vec<u8>, LinkError> {
    if blob.len() < IV_LEN + MAC_LEN {
        return Err(LinkError::BadPayload);
    }
    let (body, mac) = blob.split_at(blob.len() - MAC_LEN);
    let mut verifier = <Hmac<Sha256> as Mac>::new_from_slice(&hmac(key, b"mac")).expect("HMAC takes any key length");
    verifier.update(body);
    verifier.verify_slice(mac).map_err(|_| LinkError::BadPayload)?;
    let (iv, ciphertext) = body.split_at(IV_LEN);
    Ok(keystream_xor(&hmac(key, b"enc"), iv, ciphertext))
}

fn encode_cookies(cookies: &[SessionCookie]) -> Vec<u8> {
    let mut text = String::new();
    for cookie in cookies {
        let path = if cookie.path.trim().is_empty() { "/" } else { cookie.path.as_str() };
        let expires = cookie.expires_at.map(|at| at.to_string()).unwrap_or_default();
        let flag = |on: bool| if on { '1' } else { '0' };
        text.push_str(&format!(
            "{}{FIELD}{}{FIELD}{}{FIELD}{path}{FIELD}{}{FIELD}{}{FIELD}{expires}\n",
            cookie.name,
            cookie.value,
            cookie.domain.trim_start_matches('.'),
            flag(cookie.secure),
            flag(cookie.http_only),
        ));
    }
    text.into_bytes()
}

fn decode_cookies(bytes: &[u8]) -> Vec<SessionCookie> {
    String::from_utf8_lossy(bytes)
        .split('\n')
        .filter_map(|line| {
            let fields: Vec<&str> = line.split(FIELD).collect();
            if fields.len() != 7 || fields[0].is_empty() || fields[1].is_empty() {
                return None;
            }
            let domain = fields[2].trim_start_matches('.');
            Some(SessionCookie {
                name: fields[0].to_owned(),
                value: fields[1].to_owned(),
                domain: if domain.is_empty() { "youtube.com".into() } else { domain.to_owned() },
                path: if fields[3].is_empty() { "/".into() } else { fields[3].to_owned() },
                secure: fields[4] == "1",
                http_only: fields[5] == "1",
                expires_at: fields[6].parse().ok(),
            })
        })
        .collect()
}
