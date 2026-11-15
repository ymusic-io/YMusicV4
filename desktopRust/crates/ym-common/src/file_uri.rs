//! `file://` URIs exactly as the JVM app writes them (`kmpcommon.uri.fileUri`): the path's bytes
//! percent-encoded (uppercase hex) except RFC 3986 unreserved, sub-delims, `:`, `@` and `/`. The
//! media store and play history hold these strings and match them byte for byte, so either app
//! finds the other's rows.

/// The URI of an absolute path; a Windows drive path `C:\a` becomes `file:///C:/a`. On macOS the
/// path is taken in NFC, as the JVM reports file names there (the filesystem may hand out NFD).
pub fn to_file_uri(path: &str) -> String {
    #[cfg(target_os = "macos")]
    let path = &*icu_normalizer::ComposingNormalizerBorrowed::new_nfc().normalize(path);
    let path = path.strip_prefix(r"\\?\").unwrap_or(path);
    let bytes = path.as_bytes();
    let drive = bytes.len() >= 2
        && bytes[0].is_ascii_alphabetic()
        && bytes[1] == b':'
        && bytes.get(2).is_none_or(|next| *next == b'\\' || *next == b'/');
    let path = if drive { path.replace('\\', "/") } else { path.to_owned() };
    let mut uri = String::from(if path.starts_with('/') { "file://" } else { "file:///" });
    for byte in path.bytes() {
        let kept = byte.is_ascii_alphanumeric() || b"-._~!$&'()*+,;=:@/".contains(&byte);
        if kept {
            uri.push(byte as char);
        } else {
            uri.push_str(&format!("%{byte:02X}"));
        }
    }
    uri
}

/// The decoded path of a `file:` URI (`file:///C:/a` gives `C:/a`); `None` for any other string.
pub fn file_uri_path(uri: &str) -> Option<String> {
    let rest = uri.strip_prefix("file://")?;
    let path = rest.strip_prefix("localhost").unwrap_or(rest);
    if !path.starts_with('/') {
        return None;
    }
    let path = percent_decode(path);
    let bytes = path.as_bytes();
    let drive = bytes.len() >= 3
        && bytes[1].is_ascii_alphabetic()
        && bytes[2] == b':'
        && bytes.get(3).is_none_or(|next| *next == b'/');
    Some(if drive { path[1..].to_owned() } else { path })
}

/// `%XX` escapes decoded as UTF-8 bytes; a malformed escape is kept as written.
pub fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        let hex = |at: usize| bytes.get(at).and_then(|digit| (*digit as char).to_digit(16));
        match (bytes[index], hex(index + 1), hex(index + 2)) {
            (b'%', Some(high), Some(low)) => {
                decoded.push((high * 16 + low) as u8);
                index += 3;
            }
            (byte, _, _) => {
                decoded.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&decoded).into_owned()
}
