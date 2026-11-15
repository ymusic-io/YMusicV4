//! What one launch runs on: the cookie jar, and the caches that go with whoever owns it. A guest's
//! caches are its own and are removed at quit, so a guest and the signed-in user never read each
//! other's cached responses. The JVM app's `CookieSession` / `openCookieStore`.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use ym_common::log::Logger;
use ym_engine::{CookieJar, MemoryCookieJar, PersistentCookieJar};
use ym_settings::AppDb;
use ym_signin::{CookieVault, PasswordSeal, VaultError};

/// The sealed jar's row: the JVM app's name for it.
pub(crate) const COOKIE_JAR_SECRET: &str = "api-cookies.json";
/// Where builds before the vault kept the jar in the clear; removed at boot.
const PLAIN_COOKIES_SECRET: &str = "cookies";
/// The JVM app's key item (`UserScopeKey.kt`): the Keychain item, the Secret Service attributes, the
/// DPAPI entropy. The jar it sealed opens after the update.
const KEYCHAIN_SERVICE: &str = "io.ymusic.desktop";
const KEYCHAIN_ACCOUNT: &str = "cookie-key";
/// Where the key lives when no OS key store holds it (the JVM app's Linux fallback name).
pub(crate) const FALLBACK_KEY_SECRET: &str = "cookie-key.bin";
/// The JVM app's Windows row: the key as a user-scope DPAPI blob.
#[cfg(windows)]
const DPAPI_KEY_SECRET: &str = "cookie-key.dpapi";
pub const ENGINE_CACHE_DIR: &str = "engine-cache";
/// The engine HTTP client's disk cache, in OkHttp's format (the JVM app's folder).
pub const HTTP_CACHE_DIR: &str = "http-cache";
/// What a sign-out from the unlock screen forgets with the jar (the JVM app's `sessionCacheDirs`).
const SESSION_CACHE_DIRS: [&str; 2] = [HTTP_CACHE_DIR, ENGINE_CACHE_DIR];
/// The macOS Keychain item is bound to this app's signature, so a password adds nothing there.
const KEY_IN_KEYCHAIN: bool = cfg!(target_os = "macos") && !cfg!(debug_assertions);

#[derive(Debug, thiserror::Error)]
pub enum CookieStoreError {
    #[error(transparent)]
    Vault(#[from] VaultError),
    #[error("app.db: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("guest cache: {0}")]
    Io(#[from] std::io::Error),
    #[error("no sealed cookie jar to unlock")]
    Missing,
}

/// The jar's row in app.db and the vault that seals it. Writes and [`SealedJar::protect`] share one
/// lock, so a write that raced a protect never leaves the jar under the weaker key.
pub struct SealedJar {
    app_db: Arc<AppDb>,
    vault: CookieVault,
    lock: Mutex<()>,
}

impl SealedJar {
    fn new(app_db: Arc<AppDb>, vault: CookieVault) -> Self {
        Self { app_db, vault, lock: Mutex::new(()) }
    }

    fn write(&self, document: &[u8]) -> rusqlite::Result<()> {
        let _guard = self.lock.lock().unwrap();
        self.app_db.put_secret(COOKIE_JAR_SECRET, &self.vault.seal(document))
    }

    pub fn is_password_protected(&self) -> bool {
        self.vault.is_password_protected()
    }

    /// Every later write, and the stored jar now, goes under `password`. Slow (PBKDF2) and blocking.
    pub fn protect(&self, password: &str) -> Result<(), CookieStoreError> {
        let seal = PasswordSeal::new(password);
        let _guard = self.lock.lock().unwrap();
        let document = match self.app_db.secret(COOKIE_JAR_SECRET)? {
            Some(sealed) => Some(self.vault.open(&sealed)?),
            None => None,
        };
        self.vault.protect(seal);
        if let Some(document) = document {
            self.app_db.put_secret(COOKIE_JAR_SECRET, &self.vault.seal(&document))?;
        }
        Ok(())
    }
}

pub struct CookieSession {
    pub jar: Arc<dyn CookieJar>,
    /// `None` for a guest: nothing of it is kept, so there is nothing to protect.
    pub sealed: Option<Arc<SealedJar>>,
    /// Root of the session's HTTP and engine caches.
    pub cache_root: PathBuf,
}

impl CookieSession {
    pub fn is_guest(&self) -> bool {
        self.sealed.is_none()
    }

    /// The "protect with a password" step is offered where the default key is one every app of
    /// this user can recover.
    pub fn can_protect(&self) -> bool {
        !KEY_IN_KEYCHAIN && self.sealed.as_ref().is_some_and(|sealed| !sealed.is_password_protected())
    }

    fn user(app_db: Arc<AppDb>, vault: CookieVault, document: Option<&[u8]>, data_dir: &Path) -> Self {
        let sealed = Arc::new(SealedJar::new(app_db, vault));
        let writer = sealed.clone();
        let jar = PersistentCookieJar::new(document, Box::new(move |bytes| Ok(writer.write(bytes)?)));
        Self { jar: Arc::new(jar), sealed: Some(sealed), cache_root: data_dir.to_owned() }
    }

    fn guest() -> Result<Self, CookieStoreError> {
        let root = std::env::temp_dir().join(format!("ymusic-guest-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&root)?;
        Ok(Self { jar: Arc::new(MemoryCookieJar::default()), sealed: None, cache_root: root })
    }

    /// A guest's cache root, removed at quit.
    pub fn guest_cache(&self) -> Option<PathBuf> {
        self.is_guest().then(|| self.cache_root.clone())
    }
}

/// How the cookie store opened at launch.
pub enum CookieOpen {
    Ready(CookieSession),
    /// Password-sealed: the launch waits for [`LockedJar::unlock`], [`LockedJar::as_guest`] or
    /// [`LockedJar::sign_out`].
    Locked(Arc<LockedJar>),
}

pub struct LockedJar {
    sealed: Arc<SealedJar>,
    data_dir: PathBuf,
    user_key: Option<[u8; 32]>,
}

impl LockedJar {
    /// `None` when `password` does not open the jar. Slow (PBKDF2) and blocking.
    pub fn unlock(&self, password: &str) -> Result<Option<CookieSession>, CookieStoreError> {
        let stored = self.sealed.app_db.secret(COOKIE_JAR_SECRET)?.ok_or(CookieStoreError::Missing)?;
        if !self.sealed.vault.unlock(&stored, password)? {
            return Ok(None);
        }
        let document = self.sealed.vault.open(&stored)?;
        let writer = self.sealed.clone();
        let jar = PersistentCookieJar::new(Some(&document), Box::new(move |bytes| Ok(writer.write(bytes)?)));
        Ok(Some(CookieSession {
            jar: Arc::new(jar),
            sealed: Some(self.sealed.clone()),
            cache_root: self.data_dir.clone(),
        }))
    }

    pub fn as_guest(&self) -> Result<CookieSession, CookieStoreError> {
        CookieSession::guest()
    }

    /// Forgets the sealed sign-in and the caches made under it, then starts signed out. Blocking.
    pub fn sign_out(&self) -> Result<CookieSession, CookieStoreError> {
        self.sealed.app_db.delete_secret(COOKIE_JAR_SECRET)?;
        for dir in SESSION_CACHE_DIRS {
            match std::fs::remove_dir_all(self.data_dir.join(dir)) {
                Err(error) if error.kind() != std::io::ErrorKind::NotFound => return Err(error.into()),
                _ => {}
            }
        }
        match self.user_key {
            Some(key) => {
                Ok(CookieSession::user(self.sealed.app_db.clone(), CookieVault::new(key), None, &self.data_dir))
            }
            None => CookieSession::guest(),
        }
    }
}

/// The OS user's key in release builds, where the JVM app keeps it (`UserScopeKey.kt`). Debug
/// builds keep it in app.db, which only keeps other OS users out: macOS treats every re-signed build
/// as another app, so a Keychain read would stop each launch on an access prompt, and on every OS a
/// development build stays off the installed app's key.
fn cookie_key(app_db: &AppDb, log: &Logger) -> Result<[u8; 32], VaultError> {
    if cfg!(debug_assertions) {
        return app_db_key(app_db);
    }
    os_key(app_db, log)
}

#[cfg(target_os = "macos")]
fn os_key(_app_db: &AppDb, _log: &Logger) -> Result<[u8; 32], VaultError> {
    ym_signin::os_cookie_key(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)
}

/// The desktop keyring when one answers, else app.db. A fallback key, once made, stays the source: a
/// keyring that turns up later must not orphan the jar sealed under it.
#[cfg(target_os = "linux")]
fn os_key(app_db: &AppDb, log: &Logger) -> Result<[u8; 32], VaultError> {
    use ym_signin::SecretServiceError;
    if let Some(key) = stored_key(app_db, FALLBACK_KEY_SECRET)? {
        return Ok(key);
    }
    match ym_signin::secret_service_cookie_key(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT) {
        Ok(key) => Ok(key),
        Err(error @ SecretServiceError::Unavailable(_)) => {
            log.warn("R90450 no Secret Service, the cookie key falls back to app.db", Some(&error));
            app_db_key(app_db)
        }
        Err(error @ SecretServiceError::Failed(_)) => Err(VaultError::KeyStore(error.to_string())),
    }
}

/// A DPAPI blob in app.db that opens only for this Windows user. A blob that does not open is an
/// error, never replaced.
#[cfg(windows)]
fn os_key(app_db: &AppDb, _log: &Logger) -> Result<[u8; 32], VaultError> {
    let entropy = format!("{KEYCHAIN_SERVICE}/{KEYCHAIN_ACCOUNT}");
    let unprotect = |blob: &[u8]| key_bytes(ym_signin::dpapi_unprotect(blob, entropy.as_bytes())?);
    if let Some(blob) = app_db.secret(DPAPI_KEY_SECRET).map_err(db_error)? {
        return unprotect(&blob);
    }
    let key = ym_signin::random_key();
    let blob = ym_signin::dpapi_protect(&key, entropy.as_bytes())?;
    let kept = app_db.secret_or_put(DPAPI_KEY_SECRET, &blob).map_err(db_error)?;
    if kept == blob { Ok(key) } else { unprotect(&kept) }
}

#[cfg(not(any(target_os = "macos", target_os = "linux", windows)))]
fn os_key(app_db: &AppDb, _log: &Logger) -> Result<[u8; 32], VaultError> {
    app_db_key(app_db)
}

fn db_error(error: rusqlite::Error) -> VaultError {
    VaultError::KeyStore(format!("app.db: {error}"))
}

fn key_bytes(bytes: Vec<u8>) -> Result<[u8; 32], VaultError> {
    bytes.try_into().map_err(|_| VaultError::KeyStore("stored cookie key has the wrong size".into()))
}

fn stored_key(app_db: &AppDb, name: &str) -> Result<Option<[u8; 32]>, VaultError> {
    app_db.secret(name).map_err(db_error)?.map(key_bytes).transpose()
}

/// The plain key in app.db, created when there is none.
fn app_db_key(app_db: &AppDb) -> Result<[u8; 32], VaultError> {
    if let Some(key) = stored_key(app_db, FALLBACK_KEY_SECRET)? {
        return Ok(key);
    }
    key_bytes(app_db.secret_or_put(FALLBACK_KEY_SECRET, &ym_signin::random_key()).map_err(db_error)?)
}

/// The persistent jar sealed in app.db under the OS user's key. Without that key the launch runs
/// as a guest: signing in is off and nothing is written that a later launch could read. A
/// password-sealed jar never needs the OS key: every write after unlock is password-sealed.
/// Blocking.
pub fn open(app_db: Arc<AppDb>, data_dir: &Path, log: &Logger) -> Result<CookieOpen, CookieStoreError> {
    let log = log.fork("cookies");
    let _ = app_db.delete_secret(PLAIN_COOKIES_SECRET);
    let user_key = match cookie_key(&app_db, &log) {
        Ok(key) => Some(key),
        Err(error) => {
            log.warn("R90013 OS cookie key unavailable, this launch runs as a guest", Some(&error));
            None
        }
    };
    let stored = app_db.secret(COOKIE_JAR_SECRET)?;
    if stored.as_deref().is_some_and(CookieVault::is_password_sealed) {
        let vault = CookieVault::new(user_key.unwrap_or_else(ym_signin::random_key));
        return Ok(CookieOpen::Locked(Arc::new(LockedJar {
            sealed: Arc::new(SealedJar::new(app_db, vault)),
            data_dir: data_dir.to_owned(),
            user_key,
        })));
    }
    let Some(key) = user_key else { return Ok(CookieOpen::Ready(CookieSession::guest()?)) };
    let vault = CookieVault::new(key);
    let document = match stored.as_deref().map(|sealed| vault.open(sealed)) {
        Some(Ok(document)) => Some(document),
        Some(Err(error)) => {
            log.warn("R90014 sealed cookie jar unreadable, starting signed out", Some(&error));
            None
        }
        None => None,
    };
    Ok(CookieOpen::Ready(CookieSession::user(app_db, vault, document.as_deref(), data_dir)))
}
