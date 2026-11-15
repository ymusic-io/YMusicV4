//! Caption and tag rewriting ffmpeg can't do: WebVTT cue capping, VTT → LRC, ID3 USLT lyrics and
//! Ogg Opus comments.

use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::sync::LazyLock;

use base64::Engine as _;
use regex::{Captures, Regex};

// MP4 can't hold a sample over 35:47, and ffmpeg fails the whole file on a longer cue.
const MAX_CUE_MS: i64 = 35 * 60 * 1000;

static CUE_TIMING: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?m)^((?:\d+:)?\d{2}:\d{2}\.\d{3})(\s+-->\s+)((?:\d+:)?\d{2}:\d{2}\.\d{3})").unwrap()
});

/// `vtt` with every cue longer than the cap ending that long after its start.
pub fn cap_cue_duration(vtt: &str) -> String {
    CUE_TIMING
        .replace_all(vtt, |captures: &Captures| {
            let (start, arrow, end) = (&captures[1], &captures[2], &captures[3]);
            let start_ms = vtt_millis(start);
            if vtt_millis(end) - start_ms <= MAX_CUE_MS {
                captures[0].to_owned()
            } else {
                format!("{start}{arrow}{}", vtt_time(start_ms + MAX_CUE_MS))
            }
        })
        .into_owned()
}

fn vtt_millis(time: &str) -> i64 {
    let (clock, millis) = time.split_once('.').unwrap_or((time, "0"));
    let seconds = clock.split(':').fold(0i64, |total, part| total * 60 + part.parse::<i64>().unwrap_or(0));
    seconds * 1000 + millis.parse::<i64>().unwrap_or(0)
}

fn vtt_time(ms: i64) -> String {
    format!("{:02}:{:02}:{:02}.{:03}", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

static LRC_TIMING: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^((?:\d+:)?\d{2}:\d{2}\.\d{3})\s+-->\s+(?:\d+:)?\d{2}:\d{2}\.\d{3}.*$").unwrap());
static TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new("<[^>]*>").unwrap());
static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s{2,}").unwrap());
static BLOCK_BREAK: LazyLock<Regex> = LazyLock::new(|| Regex::new("\n{2,}").unwrap());

/// WebVTT → LRC (`[mm:ss.xx]line`), one line per caption line stamped with its cue's start.
/// Auto-generated captions roll (each cue repeats the line before, and a 10 ms cue repeats the new
/// one), so a line equal to either of the last two emitted is skipped.
pub fn vtt_to_lrc(vtt: &str) -> String {
    let normalized = vtt.replace("\r\n", "\n").replace('\r', "\n");
    let mut out = String::new();
    let mut recent: VecDeque<String> = VecDeque::new();
    for block in BLOCK_BREAK.split(&normalized) {
        let lines: Vec<&str> = block.lines().filter(|line| !line.trim().is_empty()).collect();
        let Some(timing_at) = lines.iter().position(|line| LRC_TIMING.is_match(line.trim())) else { continue };
        let stamp = lrc_time(&LRC_TIMING.captures(lines[timing_at].trim()).unwrap()[1]);
        for raw in &lines[timing_at + 1..] {
            let line = SPACES.replace_all(&decode_entities(&TAG.replace_all(raw, "")), " ").trim().to_owned();
            if line.is_empty() || recent.contains(&line) {
                continue;
            }
            out.push_str(&stamp);
            out.push_str(&line);
            out.push('\n');
            recent.push_back(line);
            if recent.len() > 2 {
                recent.pop_front();
            }
        }
    }
    out
}

/// `h:mm:ss.mmm` / `mm:ss.mmm` → `[mm:ss.xx]`, minutes counting past 60.
fn lrc_time(vtt: &str) -> String {
    let parts: Vec<&str> = vtt.split(':').collect();
    let hours: u32 = if parts.len() == 3 { parts[0].parse().unwrap_or(0) } else { 0 };
    let minutes = hours * 60 + parts[parts.len() - 2].parse::<u32>().unwrap_or(0);
    let (seconds, millis) = parts[parts.len() - 1].split_once('.').unwrap_or(("00", "000"));
    format!("[{minutes:02}:{seconds}.{}]", &millis[..millis.len().min(2)])
}

fn decode_entities(text: &str) -> String {
    text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&lrm;", "\u{200E}")
        .replace("&rlm;", "\u{200F}")
        .replace("&amp;", "&")
}

/// One caption track as MP3 lyrics: ISO-639-2 language, the track's description and LRC text.
pub struct Lyrics {
    pub language: String,
    pub description: String,
    pub text: String,
}

/// Copies the MP3 to `sink` with `lyrics` added as ID3v2.3 USLT frames, first in the tag body so
/// they land before any padding (ffmpeg can't write USLT). A file without a tag gets a new one.
/// Returns false, having written nothing, for a tag it can't extend safely (another version,
/// unsynchronisation, extended header).
pub fn insert_id3_lyrics(source: &mut impl Read, sink: &mut impl Write, lyrics: &[Lyrics]) -> io::Result<bool> {
    let mut frames = Vec::new();
    for entry in lyrics {
        write_uslt(&mut frames, entry);
    }
    let mut header = [0u8; 10];
    let read = read_up_to(source, &mut header)?;
    if read < header.len() || &header[..3] != b"ID3" {
        sink.write_all(&id3_header(frames.len() as u32))?;
        sink.write_all(&frames)?;
        sink.write_all(&header[..read])?;
        io::copy(source, sink)?;
        return Ok(true);
    }
    if header[3] != 3 || header[5] != 0 {
        return Ok(false);
    }
    let body_size = header[6..10].iter().fold(0u32, |total, byte| total << 7 | u32::from(byte & 0x7F));
    sink.write_all(&id3_header(body_size + frames.len() as u32))?;
    sink.write_all(&frames)?;
    io::copy(source, sink)?;
    Ok(true)
}

fn read_up_to(source: &mut impl Read, buffer: &mut [u8]) -> io::Result<usize> {
    let mut filled = 0;
    while filled < buffer.len() {
        match source.read(&mut buffer[filled..])? {
            0 => break,
            count => filled += count,
        }
    }
    Ok(filled)
}

fn id3_header(body_size: u32) -> [u8; 10] {
    [
        b'I',
        b'D',
        b'3',
        3,
        0,
        0,
        (body_size >> 21 & 0x7F) as u8,
        (body_size >> 14 & 0x7F) as u8,
        (body_size >> 7 & 0x7F) as u8,
        (body_size & 0x7F) as u8,
    ]
}

/// USLT: encoding, 3-letter language, a terminated descriptor, then the text, in UTF-16 with a BOM
/// (v2.3's only Unicode encoding).
fn write_uslt(out: &mut Vec<u8>, lyrics: &Lyrics) {
    let utf16 = |text: &str, body: &mut Vec<u8>| {
        body.extend_from_slice(&[0xFF, 0xFE]);
        for unit in text.encode_utf16() {
            body.extend_from_slice(&unit.to_le_bytes());
        }
    };
    let mut body = vec![1u8];
    let language: String = format!("{:<3}", lyrics.language).chars().take(3).collect();
    body.extend_from_slice(language.as_bytes());
    utf16(&lyrics.description, &mut body);
    body.extend_from_slice(&[0, 0]);
    utf16(&lyrics.text, &mut body);
    out.extend_from_slice(b"USLT");
    // v2.3 frame sizes are plain big-endian, not syncsafe.
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    out.extend_from_slice(&[0, 0]);
    out.extend_from_slice(&body);
}

/// A `METADATA_BLOCK_PICTURE` comment holding the front cover; its size fields may stay 0.
pub fn opus_picture_comment(jpeg: &[u8]) -> String {
    const FRONT_COVER: u32 = 3;
    let mime = b"image/jpeg";
    let mut block = Vec::with_capacity(jpeg.len() + 64);
    block.extend_from_slice(&FRONT_COVER.to_be_bytes());
    block.extend_from_slice(&(mime.len() as u32).to_be_bytes());
    block.extend_from_slice(mime);
    for _ in 0..5 {
        block.extend_from_slice(&0u32.to_be_bytes());
    }
    block.extend_from_slice(&(jpeg.len() as u32).to_be_bytes());
    block.extend_from_slice(jpeg);
    format!("METADATA_BLOCK_PICTURE={}", base64::engine::general_purpose::STANDARD.encode(block))
}

struct OggPage {
    flags: u8,
    granule: [u8; 8],
    serial: [u8; 4],
    segments: Vec<u8>,
    body: Vec<u8>,
}

/// Rewrites an Ogg Opus stream with `comments` (`KEY=value`) appended to its tags. ffmpeg's Ogg
/// muxer refuses a picture stream and a cover overruns the argv limit, so this runs after the mux:
/// only the tags packet is rebuilt; audio pages keep their bytes (page number and CRC follow).
pub fn insert_opus_comments(source: &mut impl Read, sink: &mut impl Write, comments: &[String]) -> io::Result<()> {
    let invalid = |message: &str| io::Error::new(io::ErrorKind::InvalidData, message.to_owned());
    let head = read_page(source)?.ok_or_else(|| invalid("empty Ogg stream"))?;
    if !head.body.starts_with(b"OpusHead") {
        return Err(invalid("not an Ogg Opus stream"));
    }
    write_page(sink, &head, 0)?;
    let mut tags = Vec::new();
    loop {
        let page = read_page(source)?.ok_or_else(|| invalid("Ogg stream ends inside OpusTags"))?;
        tags.extend_from_slice(&page.body);
        if page.segments.last().copied() != Some(255) {
            break;
        }
    }
    let packet = with_comments(&tags, comments).ok_or_else(|| invalid("malformed OpusTags packet"))?;
    let mut sequence = write_tags(sink, &head, &packet, 1)?;
    while let Some(page) = read_page(source)? {
        write_page(sink, &page, sequence)?;
        sequence += 1;
    }
    Ok(())
}

fn with_comments(packet: &[u8], comments: &[String]) -> Option<Vec<u8>> {
    let u32_at =
        |offset: usize| packet.get(offset..offset + 4).map(|bytes| u32::from_le_bytes(bytes.try_into().unwrap()));
    if !packet.starts_with(b"OpusTags") {
        return None;
    }
    let vendor_length = u32_at(8)? as usize;
    let vendor = packet.get(12..12 + vendor_length)?;
    let mut offset = 12 + vendor_length;
    let existing = u32_at(offset)?;
    offset += 4;
    let entries_start = offset;
    // Stops after the counted comments: the packet may carry padding past them.
    for _ in 0..existing {
        offset += 4 + u32_at(offset)? as usize;
    }
    let entries = packet.get(entries_start..offset)?;
    let mut out = Vec::with_capacity(packet.len() + comments.iter().map(String::len).sum::<usize>() + 64);
    out.extend_from_slice(b"OpusTags");
    out.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
    out.extend_from_slice(vendor);
    out.extend_from_slice(&(existing + comments.len() as u32).to_le_bytes());
    out.extend_from_slice(entries);
    for comment in comments {
        out.extend_from_slice(&(comment.len() as u32).to_le_bytes());
        out.extend_from_slice(comment.as_bytes());
    }
    Some(out)
}

/// Splits `packet` over pages, ending it with a segment shorter than 255. Returns the next page number.
fn write_tags(sink: &mut impl Write, head: &OggPage, packet: &[u8], first_sequence: u32) -> io::Result<u32> {
    let mut lacing = vec![255u8; packet.len() / 255 + 1];
    *lacing.last_mut().unwrap() = (packet.len() % 255) as u8;
    let (mut sequence, mut offset, mut segment) = (first_sequence, 0, 0);
    while segment < lacing.len() {
        let segments = lacing[segment..(segment + 255).min(lacing.len())].to_vec();
        let size: usize = segments.iter().map(|&length| usize::from(length)).sum();
        let flags = if segment == 0 { 0 } else { 1 };
        segment += segments.len();
        let granule = if segment == lacing.len() { [0u8; 8] } else { [0xFF; 8] };
        let page =
            OggPage { flags, granule, serial: head.serial, segments, body: packet[offset..offset + size].to_vec() };
        write_page(sink, &page, sequence)?;
        sequence += 1;
        offset += size;
    }
    Ok(sequence)
}

fn read_page(source: &mut impl Read) -> io::Result<Option<OggPage>> {
    let mut header = [0u8; 27];
    match read_up_to(source, &mut header)? {
        0 => return Ok(None),
        27 => {}
        _ => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Ogg page header cut short")),
    }
    if &header[..4] != b"OggS" {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "not an Ogg page"));
    }
    let mut segments = vec![0u8; usize::from(header[26])];
    source.read_exact(&mut segments)?;
    let mut body = vec![0u8; segments.iter().map(|&length| usize::from(length)).sum()];
    source.read_exact(&mut body)?;
    Ok(Some(OggPage {
        flags: header[5],
        granule: header[6..14].try_into().unwrap(),
        serial: header[14..18].try_into().unwrap(),
        segments,
        body,
    }))
}

fn write_page(sink: &mut impl Write, page: &OggPage, sequence: u32) -> io::Result<()> {
    let mut bytes = Vec::with_capacity(27 + page.segments.len() + page.body.len());
    bytes.extend_from_slice(b"OggS");
    bytes.push(0);
    bytes.push(page.flags);
    bytes.extend_from_slice(&page.granule);
    bytes.extend_from_slice(&page.serial);
    bytes.extend_from_slice(&sequence.to_le_bytes());
    bytes.extend_from_slice(&[0; 4]);
    bytes.push(page.segments.len() as u8);
    bytes.extend_from_slice(&page.segments);
    bytes.extend_from_slice(&page.body);
    let crc = ogg_crc(&bytes);
    bytes[22..26].copy_from_slice(&crc.to_le_bytes());
    sink.write_all(&bytes)
}

static CRC_TABLE: LazyLock<[u32; 256]> = LazyLock::new(|| {
    let mut table = [0u32; 256];
    for (index, entry) in table.iter_mut().enumerate() {
        let mut value = (index as u32) << 24;
        for _ in 0..8 {
            value = if value & 0x8000_0000 != 0 { (value << 1) ^ 0x04C1_1DB7 } else { value << 1 };
        }
        *entry = value;
    }
    table
});

/// Ogg's CRC: polynomial 0x04C11DB7, no reflection, no final inversion.
fn ogg_crc(bytes: &[u8]) -> u32 {
    bytes.iter().fold(0u32, |crc, &byte| (crc << 8) ^ CRC_TABLE[((crc >> 24) ^ u32::from(byte)) as usize & 0xFF])
}
