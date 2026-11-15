use std::io;

use ym_engine::EngineError;
use ym_ytmedia::sabr::SabrError;

/// Why a download failed, shaped for the user: a category they can act on, or a site code.
#[derive(Debug, thiserror::Error)]
pub enum DownloadError {
    #[error("network: {0}")]
    Network(String),
    #[error("disk full: {0}")]
    DiskFull(#[source] io::Error),
    #[error("live video {0}")]
    Live(String),
    #[error("still processing {0}")]
    StillProcessing(String),
    /// The download folder is gone or no longer writable.
    #[error("download folder unavailable: {0}")]
    FolderUnavailable(#[source] io::Error),
    /// Carries the code minted where the engine failed.
    #[error(transparent)]
    Engine(#[from] EngineError),
    #[error("{code}: {message}")]
    Technical { code: &'static str, message: String },
}

impl DownloadError {
    pub fn technical(code: &'static str, message: impl Into<String>) -> Self {
        Self::Technical { code, message: message.into() }
    }

    /// A file write failed: disk full is the user's to fix, anything else is `code`.
    pub fn file(code: &'static str, context: &str, error: io::Error) -> Self {
        if is_disk_full(&error) { Self::DiskFull(error) } else { Self::technical(code, format!("{context}: {error}")) }
    }

    /// The code shown with "Something went wrong", or the category name.
    pub fn code(&self) -> String {
        match self {
            Self::Network(_) => "network".to_owned(),
            Self::DiskFull(_) => "disk_full".to_owned(),
            Self::Live(_) => "live".to_owned(),
            Self::StillProcessing(_) => "processing".to_owned(),
            Self::FolderUnavailable(_) => "folder".to_owned(),
            Self::Engine(error) => error.code.clone(),
            Self::Technical { code, .. } => (*code).to_owned(),
        }
    }

    /// Network and disk-full failures are the user's environment, not a crash.
    pub fn is_environmental(&self) -> bool {
        match self {
            Self::Network(_) | Self::DiskFull(_) | Self::Live(_) | Self::StillProcessing(_) => true,
            Self::FolderUnavailable(_) => true,
            Self::Engine(error) => error.kind == ym_engine::ErrorKind::Network,
            Self::Technical { .. } => false,
        }
    }
}

pub fn is_disk_full(error: &io::Error) -> bool {
    error.kind() == io::ErrorKind::StorageFull || error.raw_os_error() == Some(28)
}

/// An uncoded SABR failure as its site code; network and disk-full failures keep their category.
pub fn sabr_failure(error: SabrError, attested_twice: bool) -> DownloadError {
    match error {
        SabrError::Network(error) => DownloadError::Network(error.to_string()),
        SabrError::Body(error) => DownloadError::Network(error.to_string()),
        SabrError::File(error) => DownloadError::file("R70001", "SABR write", error),
        SabrError::PoTokenInvalid => DownloadError::technical(
            "R70002",
            if attested_twice { "PO token rejected after a re-attest" } else { "PO token rejected" },
        ),
        SabrError::AttestationPending => DownloadError::technical("R70003", error.to_string()),
        SabrError::Server(_) => DownloadError::technical("R70004", error.to_string()),
        SabrError::Index(_) => DownloadError::technical("R70005", error.to_string()),
        SabrError::Status(_) | SabrError::Protocol(_) | SabrError::Stalled(_) => {
            DownloadError::technical("R70006", error.to_string())
        }
    }
}
