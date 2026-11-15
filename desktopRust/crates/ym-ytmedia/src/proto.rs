//! Schemaless protobuf: read fields by number, write them in order.

#[derive(Debug, thiserror::Error)]
pub enum ProtoError {
    #[error("protobuf field reads past the message end")]
    Truncated,
    #[error("protobuf varint longer than 64 bits")]
    VarintTooLong,
    #[error("unsupported protobuf wire type {0}")]
    WireType(u64),
    #[error("protobuf field {field} is missing or has the wrong type")]
    Missing { field: u32 },
    #[error("protobuf field {field} is not UTF-8")]
    NotUtf8 { field: u32 },
}

#[derive(Clone, Copy, Debug)]
pub enum Value<'a> {
    Varint(u64),
    Fixed64(u64),
    Fixed32(u32),
    Len(&'a [u8]),
}

/// One message's fields in wire order; repeated fields keep every occurrence.
#[derive(Clone, Debug, Default)]
pub struct Message<'a> {
    fields: Vec<(u32, Value<'a>)>,
}

impl<'a> Message<'a> {
    pub fn parse(mut bytes: &'a [u8]) -> Result<Self, ProtoError> {
        let mut fields = Vec::new();
        while !bytes.is_empty() {
            let tag = read_varint(&mut bytes)?;
            let field = (tag >> 3) as u32;
            let value = match tag & 7 {
                0 => Value::Varint(read_varint(&mut bytes)?),
                1 => Value::Fixed64(u64::from_le_bytes(take(&mut bytes, 8)?.try_into().unwrap())),
                5 => Value::Fixed32(u32::from_le_bytes(take(&mut bytes, 4)?.try_into().unwrap())),
                2 => {
                    let length = read_varint(&mut bytes)?;
                    let length = usize::try_from(length).map_err(|_| ProtoError::Truncated)?;
                    Value::Len(take(&mut bytes, length)?)
                }
                other => return Err(ProtoError::WireType(other)),
            };
            fields.push((field, value));
        }
        Ok(Self { fields })
    }

    fn first(&self, field: u32) -> Option<Value<'a>> {
        self.fields.iter().find(|(number, _)| *number == field).map(|(_, value)| *value)
    }

    pub fn varint(&self, field: u32) -> Option<u64> {
        match self.first(field)? {
            Value::Varint(value) => Some(value),
            _ => None,
        }
    }

    pub fn require_varint(&self, field: u32) -> Result<u64, ProtoError> {
        self.varint(field).ok_or(ProtoError::Missing { field })
    }

    pub fn bytes(&self, field: u32) -> Option<&'a [u8]> {
        match self.first(field)? {
            Value::Len(bytes) => Some(bytes),
            _ => None,
        }
    }

    pub fn string(&self, field: u32) -> Result<Option<String>, ProtoError> {
        self.bytes(field)
            .map(|bytes| String::from_utf8(bytes.to_vec()).map_err(|_| ProtoError::NotUtf8 { field }))
            .transpose()
    }

    pub fn message(&self, field: u32) -> Result<Option<Message<'a>>, ProtoError> {
        self.bytes(field).map(Message::parse).transpose()
    }

    pub fn messages(&self, field: u32) -> Result<Vec<Message<'a>>, ProtoError> {
        self.fields
            .iter()
            .filter(|(number, _)| *number == field)
            .map(|(_, value)| match value {
                Value::Len(bytes) => Message::parse(bytes),
                _ => Err(ProtoError::Missing { field }),
            })
            .collect()
    }
}

fn take<'a>(bytes: &mut &'a [u8], count: usize) -> Result<&'a [u8], ProtoError> {
    if bytes.len() < count {
        return Err(ProtoError::Truncated);
    }
    let (head, rest) = bytes.split_at(count);
    *bytes = rest;
    Ok(head)
}

fn read_varint(bytes: &mut &[u8]) -> Result<u64, ProtoError> {
    let mut result = 0u64;
    let mut shift = 0;
    loop {
        let byte = *take(bytes, 1)?.first().unwrap();
        result |= u64::from(byte & 0x7F) << shift;
        if byte & 0x80 == 0 {
            return Ok(result);
        }
        shift += 7;
        if shift >= 64 {
            return Err(ProtoError::VarintTooLong);
        }
    }
}

/// Fields are written as added; a nested message is built in its own writer first, since its
/// length prefix precedes it.
#[derive(Clone, Debug, Default)]
pub struct ProtoWriter {
    buffer: Vec<u8>,
}

impl ProtoWriter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.buffer
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.buffer
    }

    fn write_varint(&mut self, mut value: u64) {
        while value >= 0x80 {
            self.buffer.push((value as u8 & 0x7F) | 0x80);
            value >>= 7;
        }
        self.buffer.push(value as u8);
    }

    fn tag(&mut self, field: u32, wire_type: u8) {
        self.write_varint(u64::from(field) << 3 | u64::from(wire_type));
    }

    /// Negative values go out as two's-complement 64-bit, like protobuf `int64`.
    pub fn varint(mut self, field: u32, value: Option<i64>) -> Self {
        if let Some(value) = value {
            self.tag(field, 0);
            self.write_varint(value as u64);
        }
        self
    }

    pub fn bytes(mut self, field: u32, value: Option<&[u8]>) -> Self {
        if let Some(value) = value {
            self.tag(field, 2);
            self.write_varint(value.len() as u64);
            self.buffer.extend_from_slice(value);
        }
        self
    }

    pub fn string(self, field: u32, value: Option<&str>) -> Self {
        self.bytes(field, value.map(str::as_bytes))
    }

    pub fn message(self, field: u32, value: Option<ProtoWriter>) -> Self {
        match value {
            Some(child) => self.bytes(field, Some(&child.buffer)),
            None => self,
        }
    }

    pub fn messages(self, field: u32, values: impl IntoIterator<Item = ProtoWriter>) -> Self {
        values.into_iter().fold(self, |writer, child| writer.message(field, Some(child)))
    }
}
