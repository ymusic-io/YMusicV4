//! googlevideo SABR/UMP: the protocol, the download session built on it, and the player media
//! cache with the proxy that serves and fills it. Knows nothing about the engine or the player:
//! everything per video arrives as plain data ([`sabr::SabrStreamSpec`], the player's own requests).
//! Protocol notes: YMusicV4 `yt-media/SABR.md`.

pub mod cache;
pub mod proto;
pub mod proxy;
pub mod sabr;
pub mod segment_index;
pub mod ump;

/// The User-Agent a host-made YouTube / googlevideo request falls back to when the engine names none:
/// a bundle older than `SabrStreamSpec::user_agent`, or a proxied request sent without one. The
/// engine's own `DESKTOP_UA` wins wherever it is given, since the PO token is minted under it.
pub const YOUTUBE_USER_AGENT: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 \
    (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
