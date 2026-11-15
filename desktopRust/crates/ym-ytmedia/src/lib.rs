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
