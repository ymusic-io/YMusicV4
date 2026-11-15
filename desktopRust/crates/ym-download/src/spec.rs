//! The player engine's download JSON (`player/playerResponse`) plus a content PO token, mapped to
//! the SABR inputs. `serverAbrStreamingUrl` arrives with sig/n already decoded.

use base64::Engine as _;
use base64::engine::general_purpose::{STANDARD, URL_SAFE};
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use serde_json::Value;
use ym_ytmedia::sabr::{AdaptiveFormat, CaptionTrack, ClientInfo, SabrStreamSpec};

use crate::error::DownloadError;

#[derive(Clone, Debug)]
pub struct ResolvedVideo {
    pub spec: SabrStreamSpec,
    /// `videoDetails.author`, which the engine already resolved to the track artist.
    pub artist: Option<String>,
    pub title: Option<String>,
    pub loudness_target_lkfs: Option<f64>,
}

/// One downloadable caption: a video's own track, or an auto-translation of it.
#[derive(Clone, Debug)]
pub struct CaptionEntry {
    pub language_code: String,
    pub name: String,
    pub base_url: String,
    pub is_default: bool,
    pub is_asr: bool,
    pub is_translated: bool,
}

impl CaptionEntry {
    pub fn key(&self) -> String {
        caption_key(&self.language_code, self.is_asr)
    }

    fn to_track(&self, po_token: Option<&str>) -> CaptionTrack {
        CaptionTrack {
            language_code: self.language_code.clone(),
            name: self.name.clone(),
            base_url: timed_text_url(&self.base_url, po_token),
            is_default: self.is_default,
            is_asr: self.is_asr,
        }
    }
}

pub fn caption_key(language_code: &str, is_asr: bool) -> String {
    if is_asr { format!("a.{language_code}") } else { language_code.to_owned() }
}

/// An `exp=xpe` track answers 200 with an empty body unless the GET carries `c=WEB` and the
/// content PO token.
fn timed_text_url(base_url: &str, po_token: Option<&str>) -> String {
    let mut url = format!("{base_url}{}c=WEB", if base_url.contains('?') { '&' } else { '?' });
    if let Some(token) = po_token.filter(|token| !token.trim().is_empty()) {
        url.push_str("&pot=");
        url.push_str(&url::form_urlencoded::byte_serialize(token.as_bytes()).collect::<String>());
    }
    url
}

fn str_field<'a>(json: &'a Value, key: &str) -> Option<&'a str> {
    json.get(key).and_then(Value::as_str)
}

fn array<'a>(json: &'a Value, key: &str) -> impl Iterator<Item = &'a Value> {
    json.get(key).and_then(Value::as_array).into_iter().flatten()
}

/// Uploaded tracks win over auto-generated ones per language; every other translation language
/// becomes an auto-translation (`tlang`) of the source track, as the player picks it.
pub fn caption_entries(data: &Value) -> Vec<CaptionEntry> {
    let own: Vec<CaptionEntry> = array(data, "captionTracks")
        .filter_map(|track| {
            let base_url = str_field(track, "baseUrl").filter(|url| !url.trim().is_empty())?;
            let language = str_field(track, "languageCode")?;
            Some(CaptionEntry {
                language_code: language.to_owned(),
                name: str_field(track, "name").unwrap_or(language).to_owned(),
                base_url: base_url.to_owned(),
                is_default: track.get("isDefault").and_then(Value::as_bool).unwrap_or(false),
                is_asr: track.get("isAsr").and_then(Value::as_bool).unwrap_or(false),
                is_translated: false,
            })
        })
        .collect();
    let mut picked: Vec<CaptionEntry> = Vec::new();
    for entry in own.iter().filter(|entry| !entry.is_asr).chain(own.iter().filter(|entry| entry.is_asr)) {
        if !picked.iter().any(|existing| existing.language_code == entry.language_code) {
            picked.push(entry.clone());
        }
    }
    let Some(source) = own.iter().find(|entry| !entry.is_asr).or(own.first()) else { return picked };
    let separator = if source.base_url.contains('?') { '&' } else { '?' };
    let mut taken: Vec<String> = picked.iter().map(|entry| entry.language_code.clone()).collect();
    for language in array(data, "translationLanguages") {
        let Some(code) = str_field(language, "languageCode") else { continue };
        if taken.iter().any(|existing| existing == code) {
            continue;
        }
        taken.push(code.to_owned());
        picked.push(CaptionEntry {
            language_code: code.to_owned(),
            name: str_field(language, "name").unwrap_or(code).to_owned(),
            base_url: format!("{}{separator}tlang={code}", source.base_url),
            is_default: false,
            is_asr: false,
            is_translated: true,
        });
    }
    picked
}

/// Live streams and replays YouTube hasn't finished processing have no downloadable formats.
pub fn check_processed(data: &Value) -> Result<(), DownloadError> {
    let details = data.get("videoDetails");
    let video_id = details.and_then(|details| str_field(details, "videoId")).unwrap_or_default().to_owned();
    if details.and_then(|details| details.get("isLive")).and_then(Value::as_bool) == Some(true) {
        return Err(DownloadError::Live(video_id));
    }
    let streaming = data.get("streamingData").unwrap_or(&Value::Null);
    if array(streaming, "adaptiveFormats").any(|format| format.get("lastModified").is_none()) {
        return Err(DownloadError::StillProcessing(video_id));
    }
    Ok(())
}

pub fn adaptive_formats(data: &Value) -> Vec<AdaptiveFormat> {
    let streaming = data.get("streamingData").unwrap_or(&Value::Null);
    array(streaming, "adaptiveFormats").filter_map(AdaptiveFormat::from_json).collect()
}

const LENIENT: GeneralPurposeConfig =
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent);

/// YouTube mixes standard and URL-safe base64.
fn decode_base64(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    GeneralPurpose::new(&base64::alphabet::STANDARD, LENIENT)
        .decode(text)
        .or_else(|_| GeneralPurpose::new(&base64::alphabet::URL_SAFE, LENIENT).decode(text))
        .or_else(|_| STANDARD.decode(text))
        .or_else(|_| URL_SAFE.decode(text))
        .ok()
}

pub fn resolve(video_id: &str, data: &Value, po_token: Option<&str>) -> Result<ResolvedVideo, DownloadError> {
    let streaming = data
        .get("streamingData")
        .ok_or_else(|| DownloadError::technical("R70007", format!("no streamingData for {video_id}")))?;
    let sabr_url = str_field(streaming, "serverAbrStreamingUrl").ok_or_else(|| {
        DownloadError::technical("R70008", format!("no SABR stream for {video_id}; progressive is not supported"))
    })?;
    let ustreamer = str_field(data, "videoPlaybackUstreamerConfig")
        .and_then(decode_base64)
        .ok_or_else(|| DownloadError::technical("R70009", format!("no videoPlaybackUstreamerConfig for {video_id}")))?;
    check_processed(data)?;
    let po_token = po_token.filter(|token| !token.trim().is_empty());
    let po_bytes = match po_token {
        Some(token) => {
            Some(decode_base64(token).ok_or_else(|| DownloadError::technical("R70010", "PO token is not base64"))?)
        }
        None => None,
    };
    let captions = caption_entries(data).iter().map(|entry| entry.to_track(po_token)).collect();
    let details = data.get("videoDetails");
    let detail = |key: &str| {
        details.and_then(|details| str_field(details, key)).filter(|value| !value.trim().is_empty()).map(str::to_owned)
    };
    Ok(ResolvedVideo {
        spec: SabrStreamSpec {
            video_id: video_id.to_owned(),
            sabr_url: sabr_url.to_owned(),
            ustreamer_config: ustreamer,
            po_token: po_bytes,
            client_info: ClientInfo {
                client_name: Some(data.get("clientNameId").and_then(Value::as_i64).unwrap_or(1)),
                client_version: str_field(data, "clientVersion").map(str::to_owned),
                os_name: Some("Windows".to_owned()),
                os_version: Some("10.0".to_owned()),
            },
            formats: adaptive_formats(data),
            caption_tracks: captions,
        },
        artist: detail("author"),
        title: detail("title"),
        loudness_target_lkfs: data
            .get("audioConfig")
            .and_then(|config| config.get("loudnessTargetLkfs"))
            .and_then(Value::as_f64),
    })
}
