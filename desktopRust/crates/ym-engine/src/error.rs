use serde::{Deserialize, Serialize};

/// The `{code, …}` object crossing Rust ↔ JS ↔ server. `code` is a category (`network`, `parse`,
/// `oom`) or a site code (`E#####` from the bundle or server, `R#####` from this app).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WireError {
    pub code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stack: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cause: Option<WireCause>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WireCause {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl WireError {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.to_owned(),
            message: Some(message.into()),
            name: None,
            user_message: None,
            stack: None,
            cause: None,
        }
    }

    /// The RESET reason string carrying this error.
    pub fn to_reason(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| self.code.clone())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Network,
    Parse,
    Oom,
    /// A site code: shown to the user as "Something went wrong (code)".
    Technical,
    /// A category newer than this build: degrades to the generic message plus the code.
    Other,
}

/// A failure from the engine or its host, carrying the code minted at its origin.
#[derive(Debug, Clone, thiserror::Error)]
#[error("{code}: {message}")]
pub struct EngineError {
    pub code: String,
    pub kind: ErrorKind,
    pub message: String,
    pub details: Option<Box<ErrorDetails>>,
}

#[derive(Debug, Clone, Default)]
pub struct ErrorDetails {
    /// Localized, display-ready text minted with the error (e.g. by the server).
    pub user_message: Option<String>,
    pub js_name: Option<String>,
    pub stack: Option<String>,
}

impl EngineError {
    pub fn site(code: &str, message: impl Into<String>) -> Self {
        Self::from_wire(WireError::new(code, message))
    }

    pub fn from_wire(wire: WireError) -> Self {
        let kind = match wire.code.as_str() {
            "network" => ErrorKind::Network,
            "parse" => ErrorKind::Parse,
            "oom" => ErrorKind::Oom,
            code if is_site_code(code) => ErrorKind::Technical,
            _ => ErrorKind::Other,
        };
        let details = (wire.user_message.is_some() || wire.name.is_some() || wire.stack.is_some())
            .then(|| Box::new(ErrorDetails { user_message: wire.user_message, js_name: wire.name, stack: wire.stack }));
        Self { kind, message: wire.message.unwrap_or_default(), code: wire.code, details }
    }

    /// Rebuilds the error a peer RESET with. A plain-text reason (transport-generated, or a raw
    /// handler backstop) keeps an embedded site code when it has one.
    pub fn decode_reset(reason: &str) -> Self {
        if let Ok(wire) = serde_json::from_str::<WireError>(reason)
            && !wire.code.is_empty()
        {
            return Self::from_wire(wire);
        }
        match find_site_code(reason) {
            Some(code) => Self::site(code, reason),
            None => Self::site("R20005", reason),
        }
    }
}

fn is_site_code(code: &str) -> bool {
    let bytes = code.as_bytes();
    bytes.len() == 6 && matches!(bytes[0], b'E' | b'R') && bytes[1..].iter().all(u8::is_ascii_digit)
}

pub(crate) fn find_site_code(text: &str) -> Option<&str> {
    let bytes = text.as_bytes();
    (0..bytes.len().saturating_sub(5)).find_map(|start| {
        let candidate = text.get(start..start + 6)?;
        let bounded_before = start == 0 || !bytes[start - 1].is_ascii_alphanumeric();
        let bounded_after = bytes.get(start + 6).is_none_or(|next| !next.is_ascii_alphanumeric());
        (bounded_before && bounded_after && is_site_code(candidate)).then_some(candidate)
    })
}
