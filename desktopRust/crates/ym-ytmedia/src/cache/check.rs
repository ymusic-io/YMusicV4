//! Header-only structural walk of one SABR segment: WebM elements, or fMP4 boxes, must chain
//! exactly from the segment's first byte to its last, using only the ids / box types that belong
//! there (a media segment is Clusters and other Segment-level elements; an init one starts with
//! the EBML header / `ftyp` and has a `moov`). Payloads are never read.

use std::io;

const HEAD_BYTES: i64 = 16;
const NO_CLUSTER: i64 = -1;
const UNKNOWN_END: i64 = i64::MAX;
const WINDOW_BYTES: i64 = 4096;

const ID_EBML: u64 = 0x1A45_DFA3;
const ID_SEGMENT: u64 = 0x1853_8067;
const ID_CLUSTER: u64 = 0x1F43_B675;
/// SeekHead, Info, Tracks, Cluster, Cues, Attachments, Chapters, Tags, Void, CRC-32.
const SEGMENT_CHILDREN: [u64; 10] =
    [0x114D_9B74, 0x1549_A966, 0x1654_AE6B, ID_CLUSTER, 0x1C53_BB6B, 0x1941_A469, 0x1043_A770, 0x1254_C367, 0xEC, 0xBF];
/// Timestamp, SilentTracks, Position, PrevSize, SimpleBlock, BlockGroup, EncryptedBlock, Void, CRC-32.
const CLUSTER_CHILDREN: [u64; 9] = [0xE7, 0x5854, 0xA7, 0xAB, 0xA3, 0xA0, 0xAF, 0xEC, 0xBF];

const fn fourcc(name: &[u8; 4]) -> u32 {
    u32::from_be_bytes(*name)
}

const BOX_FTYP: u32 = fourcc(b"ftyp");
const BOX_MOOV: u32 = fourcc(b"moov");
const BOX_MOOF: u32 = fourcc(b"moof");
const BOX_MDAT: u32 = fourcc(b"mdat");
const MP4_SHARED: [u32; 7] = [
    fourcc(b"sidx"),
    fourcc(b"free"),
    fourcc(b"skip"),
    fourcc(b"uuid"),
    fourcc(b"styp"),
    fourcc(b"emsg"),
    fourcc(b"prft"),
];
const MP4_INIT_ONLY: [u32; 4] = [BOX_FTYP, BOX_MOOV, fourcc(b"pdin"), fourcc(b"meta")];
const MP4_MEDIA_ONLY: [u32; 4] = [BOX_MOOF, BOX_MDAT, fourcc(b"ssix"), fourcc(b"mfra")];

/// Where and why a segment failed: `offset` is inside the segment, `head` the bytes found there.
#[derive(Clone, Debug)]
pub struct SegmentCheckFailure {
    pub offset: i64,
    pub head: String,
    pub reason: String,
}

impl SegmentCheckFailure {
    fn new(offset: i64, head: &[u8], reason: impl Into<String>) -> Self {
        let head = head.iter().take(8).map(|byte| format!("{byte:02x}")).collect();
        Self { offset, head, reason: reason.into() }
    }

    /// The failure placed in the format's byte stream, for log lines.
    pub fn describe(&self, segment_start: i64) -> String {
        format!("at byte {} (+{}) [{}]: {}", segment_start + self.offset, self.offset, self.head, self.reason)
    }
}

struct WebmHeader {
    id: u64,
    size: i64,
    unknown_size: bool,
    header_len: i64,
}

/// Bytes in an EBML variable-length integer starting with `first`; 9 when it is zero (invalid).
fn vint_length(first: u8) -> usize {
    (first.leading_zeros() as usize + 1).min(9)
}

fn webm_header(head: &[u8]) -> Option<WebmHeader> {
    if head.len() < 2 {
        return None;
    }
    let id_len = vint_length(head[0]);
    if !(1..=4).contains(&id_len) || head.len() <= id_len {
        return None;
    }
    let id = head[..id_len].iter().fold(0u64, |id, byte| (id << 8) | u64::from(*byte));
    let first = head[id_len];
    let size_len = vint_length(first);
    if !(1..=8).contains(&size_len) || head.len() < id_len + size_len {
        return None;
    }
    let mask = 0xFFu32 >> size_len;
    let mut size = u64::from(u32::from(first) & mask);
    let mut all_ones = size == u64::from(mask);
    for byte in &head[id_len + 1..id_len + size_len] {
        if *byte != 0xFF {
            all_ones = false;
        }
        size = (size << 8) | u64::from(*byte);
    }
    Some(WebmHeader { id, size: size as i64, unknown_size: all_ones, header_len: (id_len + size_len) as i64 })
}

fn int32(head: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(head[at..at + 4].try_into().expect("4 bytes"))
}

pub(crate) struct SegmentCheck {
    length: i64,
    is_init: bool,
    /// Offset of the next header the walk needs; everything before it is checked or skipped.
    next: i64,
    failure: Option<SegmentCheckFailure>,
    mp4: Option<bool>,
    cluster_end: i64,
    saw_moov: bool,
    saw_moof: bool,
    saw_mdat: bool,
}

impl SegmentCheck {
    pub(crate) fn new(length: i64, is_init: bool) -> Self {
        Self {
            length,
            is_init,
            next: 0,
            failure: (length <= 0).then(|| SegmentCheckFailure::new(0, &[], "empty segment")),
            mp4: None,
            cluster_end: NO_CLUSTER,
            saw_moov: false,
            saw_moof: false,
            saw_mdat: false,
        }
    }

    fn done(&self) -> bool {
        self.failure.is_some() || self.next >= self.length
    }

    /// How many bytes at `next` the next [`Self::step`] takes.
    fn head_bytes(&self) -> usize {
        HEAD_BYTES.min(self.length - self.next).max(0) as usize
    }

    /// Walks the header at `next`; `head` holds exactly [`Self::head_bytes`] bytes from there.
    fn step(&mut self, head: &[u8]) {
        if self.done() {
            return;
        }
        let is_mp4 = *self.mp4.get_or_insert_with(|| {
            head.len() >= 8 && {
                let kind = int32(head, 4);
                MP4_SHARED.contains(&kind) || MP4_INIT_ONLY.contains(&kind) || MP4_MEDIA_ONLY.contains(&kind)
            }
        });
        if is_mp4 { self.step_mp4(head) } else { self.step_webm(head) }
    }

    fn fail(&mut self, head: &[u8], reason: impl Into<String>) {
        self.failure = Some(SegmentCheckFailure::new(self.next, head, reason));
    }

    fn kind(&self) -> &'static str {
        if self.is_init { "init" } else { "media" }
    }

    fn step_webm(&mut self, head: &[u8]) {
        let Some(header) = webm_header(head) else { return self.fail(head, "invalid element header") };
        let name = format!("0x{:x}", header.id);
        let header_end = self.next + header.header_len;
        let end = if header.unknown_size { UNKNOWN_END } else { header_end.saturating_add(header.size) };
        if self.cluster_end != NO_CLUSTER {
            if CLUSTER_CHILDREN.contains(&header.id) {
                if header.unknown_size {
                    return self.fail(head, format!("unknown-size cluster child {name}"));
                }
                if end > self.cluster_end.min(self.length) {
                    return self.fail(head, format!("cluster child {name} overruns its cluster"));
                }
                self.next = end;
                if self.next == self.cluster_end {
                    self.cluster_end = NO_CLUSTER;
                }
                return;
            }
            if self.cluster_end != UNKNOWN_END || !SEGMENT_CHILDREN.contains(&header.id) {
                return self.fail(head, format!("element {name} inside a cluster"));
            }
            self.cluster_end = NO_CLUSTER;
        }
        if self.next == 0 && self.is_init && header.id != ID_EBML {
            self.fail(head, "init segment does not start with an EBML header");
        } else if header.id == ID_SEGMENT && self.is_init {
            // Entered, not skipped: its size covers the whole stream, not just the init segment.
            self.next = header_end;
        } else if header.id == ID_CLUSTER && !self.is_init && header.unknown_size {
            self.next = header_end;
            self.cluster_end = UNKNOWN_END;
        } else if header.unknown_size {
            self.fail(head, format!("unknown-size element {name}"));
        } else if end > self.length {
            self.fail(head, format!("element {name} overruns the segment by {}", end - self.length));
        } else if header.id == ID_EBML && self.next == 0 && self.is_init {
            self.next = end;
        } else if header.id == ID_CLUSTER && !self.is_init {
            self.next = header_end;
            if self.next != end {
                self.cluster_end = end;
            }
        } else if SEGMENT_CHILDREN.contains(&header.id) && header.id != ID_CLUSTER {
            self.next = end;
        } else {
            self.fail(head, format!("element {name} does not belong in a {} segment", self.kind()));
        }
    }

    fn step_mp4(&mut self, head: &[u8]) {
        if head.len() < 8 {
            return self.fail(head, "truncated box header");
        }
        let size32 = i64::from(int32(head, 0));
        let kind = int32(head, 4);
        let size = match size32 {
            0 => self.length - self.next,
            1 => {
                if head.len() < 16 {
                    return self.fail(head, "truncated box header");
                }
                i64::from_be_bytes(head[8..16].try_into().expect("8 bytes"))
            }
            other => other,
        };
        if size < 8 || (size32 == 1 && size < 16) {
            return self.fail(head, format!("box size {size}"));
        }
        let left = self.length - self.next;
        if size > left {
            return self.fail(head, format!("box overruns the segment by {}", size - left));
        }
        let allowed = MP4_SHARED.contains(&kind)
            || if self.is_init { MP4_INIT_ONLY.contains(&kind) } else { MP4_MEDIA_ONLY.contains(&kind) };
        if !allowed {
            return self.fail(head, format!("box type 0x{kind:x} does not belong in a {} segment", self.kind()));
        }
        if self.next == 0 && self.is_init && kind != BOX_FTYP {
            return self.fail(head, "init segment does not start with ftyp");
        }
        match kind {
            BOX_MOOV => self.saw_moov = true,
            BOX_MOOF => self.saw_moof = true,
            BOX_MDAT => self.saw_mdat = true,
            _ => {}
        }
        self.next += size;
    }

    /// The verdict once every byte was seen: the first failure, or a missing piece the walk could
    /// not flag on its own.
    fn finish(&self) -> Option<SegmentCheckFailure> {
        if let Some(failure) = &self.failure {
            return Some(failure.clone());
        }
        if self.next != self.length {
            return Some(SegmentCheckFailure::new(
                self.next,
                &[],
                format!("segment ends after {} unchecked bytes", self.length - self.next),
            ));
        }
        if self.mp4 == Some(true) {
            if self.is_init && !self.saw_moov {
                return Some(SegmentCheckFailure::new(0, &[], "init segment has no moov"));
            }
            if !self.is_init && !(self.saw_moof && self.saw_mdat) {
                return Some(SegmentCheckFailure::new(0, &[], "media segment has no moof+mdat"));
            }
        }
        None
    }
}

/// A [`SegmentCheck`] fed the segment's bytes in order as they stream past.
pub(crate) struct StreamingSegmentCheck {
    check: SegmentCheck,
    length: i64,
    position: i64,
    /// Bytes from `head_at` the walk needs and has not had yet.
    head: Vec<u8>,
    head_at: i64,
}

impl StreamingSegmentCheck {
    pub(crate) fn new(length: i64, is_init: bool) -> Self {
        Self { check: SegmentCheck::new(length, is_init), length, position: 0, head: Vec::new(), head_at: 0 }
    }

    /// The segment's next bytes.
    pub(crate) fn write(&mut self, bytes: &[u8]) {
        let count = bytes.len() as i64;
        let mut offset = 0i64;
        while offset < count && !self.check.done() {
            let at = self.position + offset;
            if self.head.is_empty() {
                if at < self.check.next {
                    offset += (self.check.next - at).min(count - offset);
                    continue;
                }
                self.head_at = at;
            }
            let take = (self.check.head_bytes() as i64 - self.head.len() as i64).min(count - offset);
            if take <= 0 {
                break;
            }
            self.head.extend_from_slice(&bytes[offset as usize..(offset + take) as usize]);
            offset += take;
            self.step_held();
        }
        self.position += count;
    }

    pub(crate) fn result(&self) -> Option<SegmentCheckFailure> {
        if self.position != self.length && self.check.failure.is_none() {
            return Some(SegmentCheckFailure::new(
                self.position,
                &[],
                format!("segment has {} of {} bytes", self.position, self.length),
            ));
        }
        self.check.finish()
    }

    /// Steps through every header that starts inside the held bytes.
    fn step_held(&mut self) {
        while !self.check.done() && !self.head.is_empty() {
            if self.check.next >= self.head_at + self.head.len() as i64 {
                self.head.clear();
                return;
            }
            if self.check.next > self.head_at {
                self.head.drain(..(self.check.next - self.head_at) as usize);
                self.head_at = self.check.next;
            }
            let need = self.check.head_bytes();
            if self.head.len() < need {
                return;
            }
            let window = self.head[..need].to_vec();
            self.check.step(&window);
        }
    }
}

/// Checks a stored segment through positional reads (0 = end), a small window per header.
pub(crate) fn check_stored_segment(
    length: i64,
    is_init: bool,
    mut read_at: impl FnMut(i64, &mut [u8]) -> io::Result<usize>,
) -> io::Result<Option<SegmentCheckFailure>> {
    let mut check = SegmentCheck::new(length, is_init);
    let mut window: Vec<u8> = Vec::new();
    let mut window_at = 0i64;
    while !check.done() {
        let need = check.head_bytes() as i64;
        if check.next < window_at || check.next + need > window_at + window.len() as i64 {
            window_at = check.next;
            let want = WINDOW_BYTES.min(length - window_at) as usize;
            window.clear();
            window.resize(want, 0);
            let mut filled = 0;
            while filled < want {
                let read = read_at(window_at + filled as i64, &mut window[filled..])?;
                if read == 0 {
                    let at = window_at + filled as i64;
                    return Ok(Some(SegmentCheckFailure::new(
                        at,
                        &[],
                        format!("stored bytes end at +{at} of {length}"),
                    )));
                }
                filled += read;
            }
        }
        let from = (check.next - window_at) as usize;
        let head = window[from..from + need as usize].to_vec();
        check.step(&head);
    }
    Ok(check.finish())
}
