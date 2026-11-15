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

/// The User-Agent every host-made YouTube / googlevideo request sends. Must equal `DESKTOP_UA` in
/// YMusicV4 `engine-js/src/logic/yt/session.ts`: the PO token is minted under that UA.
pub const YOUTUBE_USER_AGENT: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 \
    (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
