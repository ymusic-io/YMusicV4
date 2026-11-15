//! UMP (`application/vnd.yt-ump`) framing: `partId` + `size` + payload. Unknown parts are skipped
//! by size; the framing describes itself.

use std::io;

use bytes::{Buf, Bytes, BytesMut};
use futures::{Stream, StreamExt};

pub mod part {
    pub const MEDIA_HEADER: u64 = 20;
    /// Payload: 1 byte header id, then media bytes.
    pub const MEDIA_DATA: u64 = 21;
    /// Payload: 1 byte header id.
    pub const MEDIA_END: u64 = 22;
    pub const NEXT_REQUEST_POLICY: u64 = 35;
    pub const FORMAT_INITIALIZATION_METADATA: u64 = 42;
    pub const SABR_REDIRECT: u64 = 43;
    pub const SABR_ERROR: u64 = 44;
    pub const SABR_CONTEXT_UPDATE: u64 = 57;
    pub const STREAM_PROTECTION_STATUS: u64 = 58;
}

/// Not a protobuf varint: the first byte's high bits give the total size (1–5 bytes), the
/// remaining value bytes are little-endian.
fn varint_size(prefix: u8) -> usize {
    match prefix {
        0x00..=0x7F => 1,
        0x80..=0xBF => 2,
        0xC0..=0xDF => 3,
        0xE0..=0xEF => 4,
        _ => 5,
    }
}

fn decode_varint(bytes: &[u8]) -> u64 {
    let byte = |index: usize| u64::from(bytes[index]);
    match bytes.len() {
        1 => byte(0),
        2 => (byte(0) & 0x3F) + 64 * byte(1),
        3 => (byte(0) & 0x1F) + 32 * (byte(1) + 256 * byte(2)),
        4 => (byte(0) & 0x0F) + 16 * (byte(1) + 256 * (byte(2) + 256 * byte(3))),
        _ => byte(1) + 256 * (byte(2) + 256 * (byte(3) + 256 * byte(4))),
    }
}

pub fn write_varint(out: &mut Vec<u8>, value: u32) {
    let value_wide = u64::from(value);
    match value_wide {
        0..0x80 => out.push(value as u8),
        0x80..0x4000 => out.extend_from_slice(&[(value as u8 & 0x3F) | 0x80, (value >> 6) as u8]),
        0x4000..0x20_0000 => {
            out.extend_from_slice(&[(value as u8 & 0x1F) | 0xC0, (value >> 5) as u8, (value >> 13) as u8])
        }
        0x20_0000..0x1000_0000 => out.extend_from_slice(&[
            (value as u8 & 0x0F) | 0xE0,
            (value >> 4) as u8,
            (value >> 12) as u8,
            (value >> 20) as u8,
        ]),
        _ => {
            out.push(0xF0);
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
}

pub fn write_part(out: &mut Vec<u8>, part_id: u32, payload: &[u8]) {
    write_part_header(out, part_id, payload.len() as u32);
    out.extend_from_slice(payload);
}

/// The payload (exactly `size` bytes) follows in the stream.
pub fn write_part_header(out: &mut Vec<u8>, part_id: u32, size: u32) {
    write_varint(out, part_id);
    write_varint(out, size);
}

/// A part header at the start of `bytes`: `(partId, size, header length)`, or `None` until all of
/// it has arrived.
pub fn parse_part_header(bytes: &[u8]) -> Option<(u64, u64, usize)> {
    let id_size = varint_size(*bytes.first()?);
    let size_size = varint_size(*bytes.get(id_size)?);
    let total = id_size + size_size;
    if bytes.len() < total {
        return None;
    }
    Some((decode_varint(&bytes[..id_size]), decode_varint(&bytes[id_size..total]), total))
}

pub type ByteStream = futures::stream::BoxStream<'static, io::Result<Bytes>>;

/// Streams parts off a response body without holding more than one network chunk plus the
/// current control part.
pub struct UmpReader<S> {
    stream: S,
    buffer: BytesMut,
    ended: bool,
}

impl<S: Stream<Item = io::Result<Bytes>> + Unpin> UmpReader<S> {
    pub fn new(stream: S) -> Self {
        Self { stream, buffer: BytesMut::new(), ended: false }
    }

    async fn fill(&mut self) -> io::Result<bool> {
        if self.ended {
            return Ok(false);
        }
        match self.stream.next().await {
            Some(chunk) => {
                self.buffer.extend_from_slice(&chunk?);
                Ok(true)
            }
            None => {
                self.ended = true;
                Ok(false)
            }
        }
    }

    async fn require(&mut self, count: usize) -> io::Result<()> {
        while self.buffer.len() < count {
            if !self.fill().await? {
                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "UMP body ended mid-part"));
            }
        }
        Ok(())
    }

    async fn read_varint(&mut self) -> io::Result<u64> {
        self.require(1).await?;
        let size = varint_size(self.buffer[0]);
        self.require(size).await?;
        let value = decode_varint(&self.buffer[..size]);
        self.buffer.advance(size);
        Ok(value)
    }

    /// The next part's `(partId, size)`, or `None` at a clean end of body.
    pub async fn part_header(&mut self) -> io::Result<Option<(u64, u64)>> {
        while self.buffer.is_empty() {
            if !self.fill().await? {
                return Ok(None);
            }
        }
        let part_id = self.read_varint().await?;
        let size = self.read_varint().await?;
        Ok(Some((part_id, size)))
    }

    pub async fn read_byte(&mut self) -> io::Result<u8> {
        self.require(1).await?;
        Ok(self.buffer.get_u8())
    }

    /// The whole payload in memory: control parts only, which are small.
    pub async fn read_bytes(&mut self, count: u64) -> io::Result<Bytes> {
        let count = usize::try_from(count).map_err(io::Error::other)?;
        self.require(count).await?;
        Ok(self.buffer.split_to(count).freeze())
    }

    /// Up to `limit` bytes of the current part, whatever is buffered (at least one byte).
    pub async fn chunk(&mut self, limit: u64) -> io::Result<Bytes> {
        self.require(1).await?;
        let take = self.buffer.len().min(usize::try_from(limit).unwrap_or(usize::MAX));
        Ok(self.buffer.split_to(take).freeze())
    }

    pub async fn skip(&mut self, mut count: u64) -> io::Result<()> {
        while count > 0 {
            count -= self.chunk(count).await?.len() as u64;
        }
        Ok(())
    }
}
