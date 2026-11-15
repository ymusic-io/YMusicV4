//! Mirrors a SABR UMP response into the player cache while the player gets the network bytes
//! untouched: a copy is parsed part by part, each segment (MEDIA_HEADER → MEDIA_DATA… → MEDIA_END)
//! fills the cache, and only complete segments commit. Lost framing or a failed cache write stops
//! the mirroring for the rest of the response, never the pass-through: a miss is always safe,
//! caching corrupt bytes is not.

use std::collections::HashMap;
use std::sync::Arc;

use ym_common::log::Logger;

use crate::cache::{CacheError, MediaCache, SegmentFill};
use crate::proto::Message;
use crate::sabr::messages::{self, FormatId, MediaHeader, NextRequestPolicy};
use crate::ump::{self, part};

/// The length of a segment whose MEDIA_HEADER has none: never owed short.
const UNKNOWN_LENGTH: i64 = i64::MAX;

struct OpenSegment {
    header: MediaHeader,
    fill: Option<SegmentFill>,
    length: i64,
    remaining: i64,
}

pub(crate) struct SabrCacheTee {
    cache: Arc<MediaCache>,
    log: Logger,
    on_storage_fault: Box<dyn Fn(&CacheError) + Send>,
    /// Undispatched bytes: at most a part header and the part payload buffered so far.
    scan: Vec<u8>,
    open: HashMap<u8, OpenSegment>,
    in_payload: bool,
    part_id: u64,
    part_remaining: u64,
    header_buf: Vec<u8>,
    /// The next MEDIA_DATA byte is its header id, not media.
    data_needs_id: bool,
    current: Option<u8>,
    /// The payload of a control part read here (format init is stored for hit replay, the rest
    /// only logged); other parts are skipped.
    payload_buf: Option<Vec<u8>>,
    illegal: bool,
    finished: bool,
    sealed_segments: u32,
    sealed_bytes: i64,
    network_bytes: u64,
}

impl SabrCacheTee {
    pub(crate) fn new(cache: Arc<MediaCache>, log: Logger, on_storage_fault: Box<dyn Fn(&CacheError) + Send>) -> Self {
        Self {
            cache,
            log: log.fork("sabr-fill"),
            on_storage_fault,
            scan: Vec::new(),
            open: HashMap::new(),
            in_payload: false,
            part_id: 0,
            part_remaining: 0,
            header_buf: Vec::new(),
            data_needs_id: false,
            current: None,
            payload_buf: None,
            illegal: false,
            finished: false,
            sealed_segments: 0,
            sealed_bytes: 0,
            network_bytes: 0,
        }
    }

    /// The response's next bytes, exactly as they went to the player.
    pub(crate) fn feed(&mut self, bytes: &[u8]) {
        self.network_bytes += bytes.len() as u64;
        if self.illegal {
            return;
        }
        self.scan.extend_from_slice(bytes);
        if let Err(error) = self.dispatch() {
            self.mark_illegal(&format!("parse or cache-write error: {error}"));
            if error.is_storage_fault() {
                (self.on_storage_fault)(&error);
            }
        }
    }

    /// The body ended or was dropped: a segment still open is truncated and dropped.
    pub(crate) fn finish(&mut self) {
        if self.finished {
            return;
        }
        self.finished = true;
        self.abort_open();
        let (segments, bytes, network) = (self.sealed_segments, self.sealed_bytes, self.network_bytes);
        self.log.debug(|| format!("sabr-fill sealed {segments} segments ({bytes} bytes) of {network} network bytes"));
    }

    fn dispatch(&mut self) -> Result<(), CacheError> {
        let mut consumed = 0;
        let result = loop {
            if self.illegal {
                break Ok(());
            }
            if !self.in_payload {
                let Some((part_id, size, header_len)) = ump::parse_part_header(&self.scan[consumed..]) else {
                    break Ok(());
                };
                consumed += header_len;
                self.part_id = part_id;
                self.part_remaining = size;
                self.in_payload = true;
                self.start_part();
                if self.illegal {
                    break Ok(());
                }
            }
            match self.consume_payload(&mut consumed) {
                Ok(true) => {}
                Ok(false) => break Ok(()),
                Err(error) => break Err(error),
            }
        };
        self.scan.drain(..consumed.min(self.scan.len()));
        result
    }

    fn start_part(&mut self) {
        match self.part_id {
            part::MEDIA_HEADER => self.header_buf.clear(),
            part::MEDIA_DATA => {
                // Each MEDIA_DATA names its segment and is self-contained: one starting while
                // another is unfinished means the framing is lost.
                if self.current.is_some() {
                    return self.mark_illegal("MEDIA_DATA began with an unfinished segment");
                }
                self.data_needs_id = true;
            }
            part::FORMAT_INITIALIZATION_METADATA
            | part::STREAM_PROTECTION_STATUS
            | part::SABR_ERROR
            | part::NEXT_REQUEST_POLICY
            | part::SABR_REDIRECT => self.payload_buf = Some(Vec::new()),
            _ => self.payload_buf = None,
        }
    }

    /// Feeds the available payload of the current part; false when more bytes are needed.
    fn consume_payload(&mut self, consumed: &mut usize) -> Result<bool, CacheError> {
        if self.part_remaining == 0 {
            return self.end_part();
        }
        let available = self.scan.len() - *consumed;
        if available == 0 {
            return Ok(false);
        }
        let take = (self.part_remaining.min(available as u64)) as usize;
        match self.part_id {
            part::MEDIA_HEADER => {
                self.header_buf.extend_from_slice(&self.scan[*consumed..*consumed + take]);
                *consumed += take;
                self.part_remaining -= take as u64;
            }
            part::MEDIA_DATA if self.data_needs_id => {
                let id = self.scan[*consumed];
                *consumed += 1;
                self.part_remaining -= 1;
                self.data_needs_id = false;
                // Every MEDIA_HEADER opens an entry: an unknown id is data without a header or
                // after its end.
                if !self.open.contains_key(&id) {
                    self.mark_illegal(&format!("MEDIA_DATA for unopened id {id}"));
                    return Ok(false);
                }
                self.current = Some(id);
            }
            part::MEDIA_DATA => {
                let id = self.current.expect("set with the header id");
                let segment = self.open.get_mut(&id).expect("checked with the header id");
                if self.part_remaining as i64 > segment.remaining {
                    let message = format!(
                        "MEDIA_DATA overruns id: {} owed vs {} expected",
                        self.part_remaining, segment.remaining
                    );
                    self.mark_illegal(&message);
                    return Ok(false);
                }
                if let Some(fill) = &mut segment.fill {
                    fill.write(&self.scan[*consumed..*consumed + take])?;
                }
                *consumed += take;
                segment.remaining -= take as i64;
                self.part_remaining -= take as u64;
            }
            part::MEDIA_END => {
                if self.part_remaining != 1 {
                    let message = format!("MEDIA_END payload is {} bytes, expected 1", self.part_remaining);
                    self.mark_illegal(&message);
                    return Ok(false);
                }
                let id = self.scan[*consumed];
                *consumed += 1;
                self.part_remaining -= 1;
                let Some(segment) = self.open.remove(&id) else {
                    self.mark_illegal(&format!("MEDIA_END for unopened id {id}"));
                    return Ok(false);
                };
                if segment.length != UNKNOWN_LENGTH && segment.remaining > 0 {
                    if let Some(fill) = segment.fill {
                        fill.abort();
                    }
                    let message = format!(
                        "MEDIA_END for id {id} short by {} of {} bytes ({})",
                        segment.remaining,
                        segment.length,
                        segment.header.describe()
                    );
                    self.mark_illegal(&message);
                    return Ok(false);
                }
                if let Some(fill) = segment.fill {
                    match fill.commit()? {
                        // The framing held, so only this segment is bad: later ones still cache.
                        Some(failure) => self.log.warn(
                            &format!(
                                "sabr-fill segment failed its structure check, not cached ({}) {}",
                                segment.header.describe(),
                                failure.describe(segment.header.start_range)
                            ),
                            None,
                        ),
                        None => {
                            self.sealed_segments += 1;
                            self.sealed_bytes += segment.length;
                        }
                    }
                }
            }
            _ => {
                if let Some(buf) = &mut self.payload_buf {
                    buf.extend_from_slice(&self.scan[*consumed..*consumed + take]);
                }
                *consumed += take;
                self.part_remaining -= take as u64;
            }
        }
        if self.part_remaining == 0 {
            return self.end_part();
        }
        Ok(true)
    }

    fn end_part(&mut self) -> Result<bool, CacheError> {
        let payload = self.payload_buf.take().unwrap_or_default();
        match self.part_id {
            part::MEDIA_HEADER => {
                let header =
                    MediaHeader::parse(&self.header_buf).map_err(|error| CacheError::Invalid(error.to_string()))?;
                self.header_buf.clear();
                let id = header.header_id;
                if self.open.contains_key(&id) {
                    self.mark_illegal(&format!("duplicate MEDIA_HEADER id {id}, still open"));
                } else if header.content_length <= 0 {
                    self.log.warn(&format!("segment without a length, not cached ({})", header.describe()), None);
                    self.open.insert(
                        id,
                        OpenSegment { header, fill: None, length: UNKNOWN_LENGTH, remaining: UNKNOWN_LENGTH },
                    );
                } else if header.data_offset != 0 && !(0..header.content_length).contains(&header.data_offset) {
                    self.mark_illegal(&format!("MEDIA_HEADER data offset outside its segment ({})", header.describe()));
                } else if header.data_offset > 0 {
                    // Only the tail arrives: caching it would leave a hole, and a full fetch
                    // rewrites it anyway.
                    self.log.warn(&format!("resumed segment, not cached ({})", header.describe()), None);
                    let length = header.content_length - header.data_offset;
                    self.open.insert(id, OpenSegment { header, fill: None, length, remaining: length });
                } else {
                    let fill = self.cache.open_segment_fill(&header)?;
                    let length = header.content_length;
                    self.open.insert(id, OpenSegment { header, fill: Some(fill), length, remaining: length });
                }
            }
            part::MEDIA_DATA => self.current = None,
            part::FORMAT_INITIALIZATION_METADATA => {
                let format = Message::parse(&payload)
                    .and_then(|message| message.message(2))
                    .and_then(|format| format.map(|format| FormatId::parse(&format)).transpose());
                match format {
                    Ok(Some(format)) => self.cache.index.put_format_init(format.itag, format.lmt, &payload)?,
                    Ok(None) => self.mark_illegal("FORMAT_INITIALIZATION_METADATA without a format id"),
                    Err(error) => self.mark_illegal(&format!("parse FORMAT_INITIALIZATION_METADATA failed {error}")),
                }
            }
            part::STREAM_PROTECTION_STATUS => match messages::stream_protection_status(&payload) {
                Some(3) => self.log.warn("stream-protection-status=3 (invalid PO token — media will stop)", None),
                status => self.log.debug(|| format!("stream-protection-status={status:?}")),
            },
            part::SABR_ERROR => {
                let message = messages::string_field_1(&payload);
                self.log.warn(&format!("sabr-error: {message:?}"), None);
            }
            part::NEXT_REQUEST_POLICY => {
                let backoff = NextRequestPolicy::parse(&payload).ok().and_then(|policy| policy.backoff_time_ms);
                self.log.debug(|| format!("next-request-policy backoff={}ms", backoff.unwrap_or(0)));
            }
            part::SABR_REDIRECT => {
                let url = messages::string_field_1(&payload);
                self.log.debug(|| format!("sabr-redirect → {}", url.as_deref().unwrap_or("?")));
            }
            _ => {}
        }
        self.in_payload = false;
        Ok(true)
    }

    fn abort_open(&mut self) {
        for (_, segment) in self.open.drain() {
            if let Some(fill) = segment.fill {
                fill.abort();
            }
        }
        self.current = None;
    }

    /// Drops every open fill and stops mirroring this response; the pass-through is untouched.
    fn mark_illegal(&mut self, reason: &str) {
        if self.illegal {
            return;
        }
        self.illegal = true;
        self.log.warn(&format!("sabr-fill disabled (illegal state): {reason}"), None);
        self.abort_open();
        self.scan = Vec::new();
    }
}
