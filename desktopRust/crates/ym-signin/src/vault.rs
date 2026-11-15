//! The cookie jar sealed at rest: AES-256-GCM as `header | nonce | ciphertext+tag`, the header
//! bound in as AAD and naming the key: `YMC1U` for the OS user's key, `YMC1P` + iterations + salt
//! for a key derived from the user's password. The JVM app's format.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key, Nonce};

const MAGIC: &[u8] = b"YMC1";
const MODE_USER: u8 = b'U';
const MODE_PASSWORD: u8 = b'P';
const NONCE_LEN: usize = 12;
const SALT_LEN: usize = 16;
/// OWASP's 2023 floor for PBKDF2-HMAC-SHA256.
const ITERATIONS: u32 = 600_000;
pub(crate) const KEY_LEN: usize = 32;

#[derive(Debug, thiserror::Error)]
pub enum VaultError {
    #[error("cookie store is sealed under another key")]
    WrongKey,
    #[error("cookie store does not authenticate")]
    Corrupt,
    #[error("OS key store: {0}")]
    KeyStore(String),
}

/// A password-derived key and the header that names it.
#[derive(Clone)]
pub struct PasswordSeal {
    header: Vec<u8>,
    key: [u8; KEY_LEN],
}

impl PasswordSeal {
    /// Deliberately slow (PBKDF2): call off the UI thread.
    pub fn new(password: &str) -> Self {
        let mut salt = [0u8; SALT_LEN];
        getrandom::getrandom(&mut salt).expect("OS randomness");
        Self::derive(password, &salt, ITERATIONS)
    }

    fn derive(password: &str, salt: &[u8], iterations: u32) -> Self {
        let mut key = [0u8; KEY_LEN];
        pbkdf2::pbkdf2_hmac::<sha2::Sha256>(password.as_bytes(), salt, iterations, &mut key);
        let mut header = MAGIC.to_vec();
        header.push(MODE_PASSWORD);
        header.extend_from_slice(&iterations.to_be_bytes());
        header.extend_from_slice(salt);
        Self { header, key }
    }
}

pub struct CookieVault {
    user_key: [u8; KEY_LEN],
    password: std::sync::Mutex<Option<PasswordSeal>>,
}

fn user_header() -> Vec<u8> {
    let mut header = MAGIC.to_vec();
    header.push(MODE_USER);
    header
}

fn header_len(sealed: &[u8]) -> Option<usize> {
    if !sealed.starts_with(MAGIC) {
        return None;
    }
    match *sealed.get(MAGIC.len())? {
        MODE_USER => Some(MAGIC.len() + 1),
        MODE_PASSWORD => Some(MAGIC.len() + 1 + 4 + SALT_LEN),
        _ => None,
    }
}

impl CookieVault {
    pub fn new(user_key: [u8; KEY_LEN]) -> Self {
        Self { user_key, password: std::sync::Mutex::new(None) }
    }

    pub fn is_password_protected(&self) -> bool {
        self.password.lock().unwrap().is_some()
    }

    /// Whether `sealed` needs a password, readable without any key.
    pub fn is_password_sealed(sealed: &[u8]) -> bool {
        sealed.starts_with(MAGIC) && sealed.get(MAGIC.len()) == Some(&MODE_PASSWORD)
    }

    pub fn seal(&self, document: &[u8]) -> Vec<u8> {
        let password = self.password.lock().unwrap().clone();
        let (header, key) = match &password {
            Some(seal) => (seal.header.clone(), seal.key),
            None => (user_header(), self.user_key),
        };
        let mut nonce = [0u8; NONCE_LEN];
        getrandom::getrandom(&mut nonce).expect("OS randomness");
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key));
        let ciphertext = cipher
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: document, aad: &header })
            .expect("AES-GCM encryption does not fail on valid lengths");
        let mut out = header;
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ciphertext);
        out
    }

    pub fn open(&self, sealed: &[u8]) -> Result<Vec<u8>, VaultError> {
        let header_end = header_len(sealed).ok_or(VaultError::Corrupt)?;
        let header = &sealed[..header_end];
        let key = if header == user_header().as_slice() {
            self.user_key
        } else {
            match self.password.lock().unwrap().as_ref().filter(|seal| seal.header == header) {
                Some(seal) => seal.key,
                None => return Err(VaultError::WrongKey),
            }
        };
        let rest = &sealed[header_end..];
        if rest.len() < NONCE_LEN {
            return Err(VaultError::Corrupt);
        }
        let (nonce, ciphertext) = rest.split_at(NONCE_LEN);
        Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key))
            .decrypt(Nonce::from_slice(nonce), Payload { msg: ciphertext, aad: header })
            .map_err(|_| VaultError::Corrupt)
    }

    /// Every later seal uses `seal`.
    pub fn protect(&self, seal: PasswordSeal) {
        *self.password.lock().unwrap() = Some(seal);
    }

    /// Keeps the password's key when it opens `sealed`; `false` when it does not authenticate.
    /// Slow (PBKDF2): call off the UI thread.
    pub fn unlock(&self, sealed: &[u8], password: &str) -> Result<bool, VaultError> {
        if !Self::is_password_sealed(sealed) {
            return Err(VaultError::WrongKey);
        }
        let fields = sealed.get(MAGIC.len() + 1..MAGIC.len() + 1 + 4 + SALT_LEN).ok_or(VaultError::Corrupt)?;
        let iterations = u32::from_be_bytes(fields[..4].try_into().map_err(|_| VaultError::Corrupt)?);
        let seal = PasswordSeal::derive(password, &fields[4..], iterations);
        let previous = self.password.lock().unwrap().replace(seal);
        match self.open(sealed) {
            Ok(_) => Ok(true),
            Err(VaultError::Corrupt) => {
                *self.password.lock().unwrap() = previous;
                Ok(false)
            }
            Err(other) => Err(other),
        }
    }
}

/// 32 fresh random bytes for a key.
pub fn random_key() -> [u8; KEY_LEN] {
    let mut key = [0u8; KEY_LEN];
    getrandom::getrandom(&mut key).expect("OS randomness");
    key
}

/// The OS user's 32-byte cookie key, created on first use: the login Keychain on macOS, where the
/// JVM app keeps it too. Only a missing item is created, and never over one: a read that fails for
/// any other reason (a refused access prompt) must not replace the key the stored jar is sealed with.
/// Blocking. `service` names the app's item.
#[cfg(target_os = "macos")]
pub fn os_cookie_key(service: &str, account: &str) -> Result<[u8; KEY_LEN], VaultError> {
    use core_foundation::data::CFData;
    use security_framework::item::{ItemAddOptions, ItemAddValue, ItemClass};
    use security_framework::passwords::{PasswordOptions, generic_password};
    const ERR_SEC_ITEM_NOT_FOUND: i32 = -25300;
    const ERR_SEC_DUPLICATE_ITEM: i32 = -25299;
    let keychain_error = |error: security_framework::base::Error| VaultError::KeyStore(error.to_string());
    let read = || generic_password(PasswordOptions::new_generic_password(service, account));
    let stored = match read() {
        Ok(stored) => stored,
        Err(error) if error.code() == ERR_SEC_ITEM_NOT_FOUND => {
            let mut key = [0u8; KEY_LEN];
            getrandom::getrandom(&mut key).map_err(|error| VaultError::KeyStore(error.to_string()))?;
            let mut item = ItemAddOptions::new(ItemAddValue::Data {
                class: ItemClass::generic_password(),
                data: CFData::from_buffer(&key),
            });
            item.set_service(service).set_account_name(account);
            match item.add() {
                Ok(()) => return Ok(key),
                Err(error) if error.code() == ERR_SEC_DUPLICATE_ITEM => read().map_err(keychain_error)?,
                Err(error) => return Err(keychain_error(error)),
            }
        }
        Err(error) => return Err(keychain_error(error)),
    };
    stored.try_into().map_err(|_| VaultError::KeyStore("keychain item has the wrong size".into()))
}
