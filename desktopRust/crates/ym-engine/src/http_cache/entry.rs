//! OkHttp `Cache.Entry`: the metadata value (`<key>.0`) of a cached response, in OkHttp's line format.

use std::io::{self, BufRead, ErrorKind, Write};

use super::disk_lru::read_line_strict;
use super::headers::Headers;

const SENT_MILLIS: &str = "OkHttp-Sent-Millis";
const RECEIVED_MILLIS: &str = "OkHttp-Received-Millis";

#[derive(Clone, Debug)]
pub(crate) struct Handshake {
    pub cipher_suite: String,
    /// Base64 DER, one per line as stored.
    pub peer_certificates: Vec<String>,
    pub local_certificates: Vec<String>,
    /// A Java TLS name (`TLSv1.3`); absent in entries OkHttp 3 wrote.
    pub tls_version: Option<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct EntryMeta {
    pub url: String,
    pub request_method: String,
    pub vary_headers: Headers,
    pub http_1_0: bool,
    pub code: u16,
    pub message: String,
    pub response_headers: Headers,
    pub sent_millis: i64,
    pub received_millis: i64,
    pub handshake: Option<Handshake>,
}

fn corrupt(message: String) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, message)
}

impl EntryMeta {
    pub fn is_https(&self) -> bool {
        self.url.starts_with("https:")
    }

    pub fn read(source: &mut impl BufRead) -> io::Result<Self> {
        let url = read_line_strict(source)?;
        match url::Url::parse(&url) {
            Ok(parsed) if matches!(parsed.scheme(), "http" | "https") => {}
            _ => return Err(corrupt(format!("cache corruption for {url}"))),
        }
        let request_method = read_line_strict(source)?;
        let mut vary_headers = Headers::default();
        for _ in 0..read_int(source)? {
            vary_headers.add_lenient(&read_line_strict(source)?);
        }
        let (http_1_0, code, message) = parse_status_line(&read_line_strict(source)?)?;
        let mut response_headers = Headers::default();
        for _ in 0..read_int(source)? {
            response_headers.add_lenient(&read_line_strict(source)?);
        }
        let millis = |headers: &Headers, name: &str| -> io::Result<i64> {
            headers.get(name).map_or(Ok(0), |value| value.parse().map_err(|_| corrupt(format!("{name}: {value}"))))
        };
        let sent_millis = millis(&response_headers, SENT_MILLIS)?;
        let received_millis = millis(&response_headers, RECEIVED_MILLIS)?;
        response_headers.remove_all(SENT_MILLIS);
        response_headers.remove_all(RECEIVED_MILLIS);
        let handshake = if url.starts_with("https:") {
            let blank = read_line_strict(source)?;
            if !blank.is_empty() {
                return Err(corrupt(format!("expected \"\" but was \"{blank}\"")));
            }
            let cipher_suite = read_line_strict(source)?;
            let peer_certificates = read_certificates(source)?;
            let local_certificates = read_certificates(source)?;
            let tls_version = if source.fill_buf()?.is_empty() { None } else { Some(read_line_strict(source)?) };
            Some(Handshake { cipher_suite, peer_certificates, local_certificates, tls_version })
        } else {
            None
        };
        Ok(Self {
            url,
            request_method,
            vary_headers,
            http_1_0,
            code,
            message,
            response_headers,
            sent_millis,
            received_millis,
            handshake,
        })
    }

    pub fn write(&self, sink: &mut impl Write) -> io::Result<()> {
        writeln!(sink, "{}", self.url)?;
        writeln!(sink, "{}", self.request_method)?;
        writeln!(sink, "{}", self.vary_headers.len())?;
        for (name, value) in self.vary_headers.iter() {
            writeln!(sink, "{name}: {value}")?;
        }
        let protocol = if self.http_1_0 { "HTTP/1.0" } else { "HTTP/1.1" };
        writeln!(sink, "{protocol} {} {}", self.code, self.message)?;
        writeln!(sink, "{}", self.response_headers.len() + 2)?;
        for (name, value) in self.response_headers.iter() {
            writeln!(sink, "{name}: {value}")?;
        }
        writeln!(sink, "{SENT_MILLIS}: {}", self.sent_millis)?;
        writeln!(sink, "{RECEIVED_MILLIS}: {}", self.received_millis)?;
        if let Some(handshake) = self.handshake.as_ref().filter(|_| self.is_https()) {
            writeln!(sink)?;
            writeln!(sink, "{}", handshake.cipher_suite)?;
            for list in [&handshake.peer_certificates, &handshake.local_certificates] {
                writeln!(sink, "{}", list.len())?;
                for certificate in list {
                    writeln!(sink, "{certificate}")?;
                }
            }
            if let Some(version) = &handshake.tls_version {
                writeln!(sink, "{version}")?;
            }
        }
        Ok(())
    }
}

/// `Cache.readInt`: a decimal line in `0..=i32::MAX`.
fn read_int(source: &mut impl BufRead) -> io::Result<usize> {
    let line = read_line_strict(source)?;
    match line.parse::<i64>() {
        Ok(value) if (0..=i64::from(i32::MAX)).contains(&value) => Ok(value as usize),
        _ => Err(corrupt(format!("expected an int but was \"{line}\""))),
    }
}

fn read_certificates(source: &mut impl BufRead) -> io::Result<Vec<String>> {
    (0..read_int(source)?).map(|_| read_line_strict(source)).collect()
}

/// OkHttp `StatusLine.parse`: `(is HTTP/1.0, code, message)`.
fn parse_status_line(line: &str) -> io::Result<(bool, u16, String)> {
    let malformed = || io::Error::new(ErrorKind::InvalidData, format!("unexpected status line: {line}"));
    let bytes = line.as_bytes();
    let (http_1_0, code_start) = if line.starts_with("HTTP/1.") {
        if bytes.len() < 9 || bytes[8] != b' ' {
            return Err(malformed());
        }
        match bytes[7] {
            b'0' => (true, 9),
            b'1' => (false, 9),
            _ => return Err(malformed()),
        }
    } else if line.starts_with("ICY ") {
        (true, 4)
    } else if line.starts_with("SOURCETABLE ") {
        (false, 12)
    } else {
        return Err(malformed());
    };
    let code_text = line.get(code_start..code_start + 3).ok_or_else(malformed)?;
    if !code_text.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(malformed());
    }
    let code = code_text.parse().map_err(|_| malformed())?;
    let message = if bytes.len() > code_start + 3 {
        if bytes[code_start + 3] != b' ' {
            return Err(malformed());
        }
        line[code_start + 4..].to_owned()
    } else {
        String::new()
    };
    Ok((http_1_0, code, message))
}
