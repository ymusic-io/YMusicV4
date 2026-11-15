pub mod messages;
pub mod session;

pub use messages::{AdaptiveFormat, ClientInfo, MediaHeader};
pub use session::{CookieHeader, DownloadFile, Progress, SabrError, SabrHttp, SabrSession, SabrTrack};

/// Everything SABR needs for one video, as plain data.
#[derive(Clone, Debug)]
pub struct SabrStreamSpec {
    pub video_id: String,
    /// `serverAbrStreamingUrl` with sig/n already decoded.
    pub sabr_url: String,
    /// `videoPlaybackUstreamerConfig`, base64-decoded; passed through opaquely.
    pub ustreamer_config: Vec<u8>,
    /// GVS PO token bound to the video, base64-decoded. Without one media stops ~40s in.
    pub po_token: Option<Vec<u8>>,
    pub client_info: ClientInfo,
    pub formats: Vec<AdaptiveFormat>,
    /// Not SABR: carried because it comes from the same player response.
    pub caption_tracks: Vec<CaptionTrack>,
}

/// One timed-text track; `base_url` is a plain GET, ready to fetch.
#[derive(Clone, Debug)]
pub struct CaptionTrack {
    pub language_code: String,
    pub name: String,
    pub base_url: String,
    pub is_default: bool,
    pub is_asr: bool,
}
