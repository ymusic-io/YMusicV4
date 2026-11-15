use std::path::PathBuf;

use base64::Engine as _;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use sha2::{Digest, Sha256};
use ym_common::log::{Logger, now_millis};

use crate::config::BundleVersion;

/// A remote JS bundle's authenticity: ECDSA P-256 / SHA-256 over `"<name>:<version>:<sha256 hex>"`.
/// `public_key` is the pinned key (base64 X.509 SubjectPublicKeyInfo), `signature` base64 DER.
/// The name keeps an engine signature from passing as a player one; the version stops an old file
/// being replayed under a newer number. Malformed input is a failed check.
pub fn verify_signature(public_key: &str, name: &str, version: i64, sha256_hex: &str, signature: &str) -> bool {
    let decode = |text: &str| base64::engine::general_purpose::STANDARD.decode(text.trim()).ok();
    let (Some(key), Some(signature)) = (decode(public_key), decode(signature)) else { return false };
    let (Ok(key), Ok(signature)) = (VerifyingKey::from_public_key_der(&key), Signature::from_der(&signature)) else {
        return false;
    };
    key.verify(format!("{name}:{version}:{sha256_hex}").as_bytes(), &signature).is_ok()
}

#[derive(Debug, thiserror::Error)]
pub enum BundleError {
    #[error("bundle download failed: {0}")]
    Download(String),
    #[error("bundle {name} v{version} does not match its signature")]
    Signature { name: String, version: i64 },
    #[error("bundle cache: {0}")]
    Io(#[from] std::io::Error),
}

/// A downloaded remote bundle whose signature checked out.
#[derive(Debug, Clone)]
pub struct VerifiedBundle {
    pub remote: BundleVersion,
    pub source: String,
}

/// The JVM app's `bundle.meta` (`DefaultJsSourceProvider`): the version, the time it was stored
/// (epoch ms) and the signature, one per line, so either app boots the other's cached bundle.
struct CachedMeta {
    version: i64,
    stored_at: i64,
    signature: String,
}

impl CachedMeta {
    fn parse(text: &str) -> Option<Self> {
        let mut lines = text.lines();
        let version = lines.next()?.parse().ok()?;
        let stored_at = lines.next()?.parse().ok()?;
        Some(Self { version, stored_at, signature: lines.next().unwrap_or_default().to_owned() })
    }

    fn to_text(&self) -> String {
        format!("{}\n{}\n{}\n", self.version, self.stored_at, self.signature)
    }
}

/// Where an updatable bundle (engine.js, player.js) comes from: the embedded copy is the floor, a
/// cached remote copy rides above it, in the JVM app's folder and format (`bundle.js` + `bundle.meta`). The disk is not trusted: the cached file is re-hashed and
/// its signature re-checked on every load, and a torn or tampered cache reads as absent.
pub struct BundleSource {
    /// The signed name: `engine` or `player`.
    name: String,
    /// Blank: no remote bundle is ever trusted.
    public_key: String,
    embedded: (&'static str, i64),
    directory: PathBuf,
    client: reqwest::Client,
    log: Logger,
}

impl BundleSource {
    pub fn new(
        name: &str,
        public_key: &str,
        embedded: (&'static str, i64),
        directory: PathBuf,
        client: reqwest::Client,
        log: Logger,
    ) -> Self {
        Self {
            name: name.to_owned(),
            public_key: public_key.to_owned(),
            embedded,
            directory,
            client,
            log: log.fork("js-source"),
        }
    }

    pub fn embedded(&self) -> (&'static str, i64) {
        self.embedded
    }

    /// The cached remote bundle when it verifies and is newer than the embedded one. Blocking.
    pub fn cached(&self) -> Option<(String, i64)> {
        if self.public_key.trim().is_empty() {
            return None;
        }
        let meta = CachedMeta::parse(&std::fs::read_to_string(self.directory.join("bundle.meta")).ok()?)?;
        let source = std::fs::read(self.directory.join("bundle.js")).ok()?;
        let digest = hex::encode(Sha256::digest(&source));
        if !verify_signature(&self.public_key, &self.name, meta.version, &digest, &meta.signature) {
            self.log.warn(&format!("R30004 cached {} bundle fails its signature, deleted", self.name), None);
            self.clear();
            return None;
        }
        if meta.version < self.embedded.1 {
            return None;
        }
        Some((String::from_utf8(source).ok()?, meta.version))
    }

    /// Downloads and verifies `remote`; nothing is kept until [`Self::store`] (after it booted).
    pub async fn download(&self, remote: &BundleVersion) -> Result<VerifiedBundle, BundleError> {
        let failed = |error: reqwest::Error| BundleError::Download(error.without_url().to_string());
        let response = self.client.get(&remote.url).send().await.map_err(failed)?;
        if !response.status().is_success() {
            return Err(BundleError::Download(format!("HTTP {}", response.status().as_u16())));
        }
        let bytes = response.bytes().await.map_err(failed)?;
        let digest = hex::encode(Sha256::digest(&bytes));
        if !verify_signature(&self.public_key, &self.name, remote.version, &digest, &remote.signature) {
            return Err(BundleError::Signature { name: self.name.clone(), version: remote.version });
        }
        let source =
            String::from_utf8(bytes.to_vec()).map_err(|_| BundleError::Download("bundle is not UTF-8".into()))?;
        Ok(VerifiedBundle { remote: remote.clone(), source })
    }

    /// Caches a bundle that booted. Blocking.
    pub fn store(&self, bundle: &VerifiedBundle) -> Result<(), BundleError> {
        std::fs::create_dir_all(&self.directory)?;
        let meta = CachedMeta {
            version: bundle.remote.version,
            stored_at: now_millis(),
            signature: bundle.remote.signature.clone(),
        };
        let staged = self.directory.join("bundle.js.tmp");
        std::fs::write(&staged, &bundle.source)?;
        std::fs::write(self.directory.join("bundle.meta.tmp"), meta.to_text())?;
        std::fs::rename(&staged, self.directory.join("bundle.js"))?;
        std::fs::rename(self.directory.join("bundle.meta.tmp"), self.directory.join("bundle.meta"))?;
        Ok(())
    }

    fn clear(&self) {
        let _ = std::fs::remove_file(self.directory.join("bundle.js"));
        let _ = std::fs::remove_file(self.directory.join("bundle.meta"));
    }
}
