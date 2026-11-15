use std::io::Read;
use std::path::Path;

use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

#[derive(Debug, thiserror::Error)]
pub enum DownloadError {
    /// The file is not the published one (size or hash); its partial file is gone.
    #[error("download does not match the published file: {0}")]
    Mismatch(String),
    #[error("HTTP {0} fetching the installer")]
    Status(u16),
    #[error("installer download interrupted: {0}")]
    Network(String),
    #[error("installer download: {0}")]
    Io(#[from] std::io::Error),
}

impl DownloadError {
    fn network(error: reqwest::Error) -> Self {
        Self::Network(error.without_url().to_string())
    }
}

/// Downloads `url` into `part`, continuing what an interrupted attempt left there: an existing
/// partial file is resumed with `Range: bytes=<its size>-` and appended to only on a `206`; any other
/// answer starts over from byte 0. A partial file already `size` long is just verified.
///
/// The finished file must be exactly `size` bytes hashing to `sha256` (hex), else
/// [`DownloadError::Mismatch`] and `part` is deleted. Any other failure leaves `part` to resume, so
/// name it after the release's version AND hash: one release's bytes must never continue another's.
pub async fn download_resumable(
    client: &reqwest::Client,
    url: &str,
    part: &Path,
    size: u64,
    sha256: &str,
    mut on_progress: impl FnMut(u64),
) -> Result<(), DownloadError> {
    let mut offset = tokio::fs::metadata(part).await.map(|metadata| metadata.len()).unwrap_or(0);
    if offset > size {
        remove(part).await?;
        offset = 0;
    }
    if offset == size && size > 0 {
        if hash_file(part).await? == sha256 {
            return Ok(());
        }
        remove(part).await?;
        offset = 0;
    }

    // no-store: no cache on the way may keep a copy of the installer.
    let request = |from: u64| {
        let mut request = client.get(url).header(reqwest::header::CACHE_CONTROL, "no-store");
        if from > 0 {
            request = request.header(reqwest::header::RANGE, format!("bytes={from}-"));
        }
        request.send()
    };
    let mut response = request(offset).await.map_err(DownloadError::network)?;
    if offset > 0 && response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        drop(response);
        remove(part).await?;
        offset = 0;
        response = request(0).await.map_err(DownloadError::network)?;
    }
    if !response.status().is_success() {
        return Err(DownloadError::Status(response.status().as_u16()));
    }
    let mut file = if offset > 0 {
        tokio::fs::OpenOptions::new().append(true).open(part).await?
    } else {
        tokio::fs::File::create(part).await?
    };
    let mut done = offset;
    on_progress(done);
    while let Some(chunk) = response.chunk().await.map_err(DownloadError::network)? {
        file.write_all(&chunk).await?;
        done += chunk.len() as u64;
        if done > size {
            // Closed first: Windows cannot delete an open file.
            drop(file);
            remove(part).await?;
            return Err(DownloadError::Mismatch(format!("more than the published {size} bytes")));
        }
        on_progress(done);
    }
    file.flush().await?;
    drop(file);

    let hashed = hash_file(part).await?;
    if hashed != sha256 {
        remove(part).await?;
        return Err(DownloadError::Mismatch(format!("hashed {hashed}, published {sha256}")));
    }
    Ok(())
}

/// Hex SHA-256 of `file`, streamed: an installer is a few hundred MB. Blocking.
pub fn sha256_of(file: &Path) -> std::io::Result<String> {
    let mut source = std::fs::File::open(file)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 256 * 1024];
    loop {
        let read = source.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

async fn hash_file(file: &Path) -> std::io::Result<String> {
    let file = file.to_owned();
    tokio::task::spawn_blocking(move || sha256_of(&file)).await.map_err(std::io::Error::other)?
}

async fn remove(file: &Path) -> std::io::Result<()> {
    match tokio::fs::remove_file(file).await {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}
