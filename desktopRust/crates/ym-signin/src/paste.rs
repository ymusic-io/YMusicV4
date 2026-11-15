use std::sync::LazyLock;
use std::time::{SystemTime, UNIX_EPOCH};

use regex::Regex;

/// One YouTube/Google cookie moved between devices; `domain` is a suffix without a leading dot.
#[derive(Debug, Clone, PartialEq)]
pub struct SessionCookie {
    pub name: String,
    pub value: String,
    pub domain: String,
    pub path: String,
    pub secure: bool,
    pub http_only: bool,
    /// Epoch milliseconds.
    pub expires_at: Option<i64>,
}

impl SessionCookie {
    fn youtube(name: &str, value: &str) -> Self {
        Self {
            name: name.to_owned(),
            value: value.to_owned(),
            domain: "youtube.com".into(),
            path: "/".into(),
            secure: true,
            http_only: false,
            expires_at: None,
        }
    }
}

/// The cookies a signed-in session needs at least one of.
const ESSENTIAL_COOKIE_NAMES: &[&str] =
    &["LOGIN_INFO", "SAPISID", "APISID", "HSID", "SSID", "SIDCC", "__Secure-1PAPISID", "__Secure-3PAPISID"];
/// How long an imported cookie without its own expiry is kept.
const PERSIST_MS: i64 = 400 * 24 * 60 * 60 * 1000;

pub fn looks_signed_in(cookies: &[SessionCookie]) -> bool {
    cookies.iter().any(|cookie| ESSENTIAL_COOKIE_NAMES.contains(&cookie.name.as_str()) && !cookie.value.is_empty())
}

/// A `Set-Cookie` line to store the cookie as if `https://www.youtube.com/` had sent it.
pub fn to_set_cookie(cookie: &SessionCookie) -> String {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_millis() as i64);
    let expires = cookie.expires_at.filter(|at| *at > now).unwrap_or(now + PERSIST_MS);
    let domain = cookie.domain.trim_start_matches('.');
    let domain = if domain.is_empty() { "youtube.com" } else { domain };
    let path = if cookie.path.trim().is_empty() { "/" } else { cookie.path.as_str() };
    let mut line = format!(
        "{}={}; Domain={domain}; Path={path}; Max-Age={}",
        cookie.name,
        cookie.value,
        ((expires - now) / 1000).max(1)
    );
    if cookie.secure {
        line.push_str("; Secure");
    }
    if cookie.http_only {
        line.push_str("; HttpOnly");
    }
    line
}

static CURL_CMD: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\bcurl(?:\.exe)?\s+").unwrap());
static CURL_HEADER_COOKIE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?:-H|--header)(?:\s+|=)\$?(?:'\s*[Cc]ookie\s*:\s*(.*?)'|"\s*[Cc]ookie\s*:\s*(.*?)")"#).unwrap()
});
static CURL_COOKIE_FLAG: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?:^|\s)(?:-b|--cookie)(?:\s+|=)(?:'(.*?)'|"(.*?)")"#).unwrap());
static COOKIE_HEADER_LINE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?m)^[Cc]ookie\s*:\s*(.+)$").unwrap());
static JSON_OBJECT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\{[^{}]+\}").unwrap());

pub fn looks_like_curl(text: &str) -> bool {
    CURL_CMD.is_match(text.trim())
}

/// A browser's Copy as cURL (bash, cmd, PowerShell), a `Cookie:` header or bare header value,
/// name=value lines, a Netscape cookies.txt, a DevTools table copy, or a JSON cookie export.
pub fn parse_cookie_paste(text: &str) -> Vec<SessionCookie> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let json = parse_json(trimmed);
    if !json.is_empty() {
        return json;
    }
    let lines: Vec<&str> = trimmed.lines().map(str::trim_end).collect();
    let header =
        lines.iter().find(|line| !line.trim().is_empty() && !line.starts_with('#')).copied().unwrap_or_default();
    let lower = header.to_lowercase();
    if header.contains('\t') && lower.contains("name") && lower.contains("value") {
        return parse_chrome_tsv(&lines);
    }
    if header.contains('\t') {
        let netscape = parse_netscape(&lines);
        if !netscape.is_empty() {
            return netscape;
        }
        let tsv = parse_chrome_tsv(&lines);
        if !tsv.is_empty() {
            return tsv;
        }
    }
    if let Some(payload) = cookie_payload(trimmed) {
        let extracted = parse_cookie_header(&payload);
        if extracted.len() >= 2 || looks_like_curl(trimmed) {
            return extracted;
        }
    }
    let header_cookies = parse_cookie_header(trimmed);
    if header_cookies.len() >= 2 {
        return header_cookies;
    }
    let named = parse_name_value_lines(&lines);
    if named.is_empty() { header_cookies } else { named }
}

/// Joins shell line continuations (`\`, `^`, backtick) into one line.
fn flatten_continuations(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut index = 0;
    while index < chars.len() {
        let c = chars[index];
        if matches!(c, '\\' | '^' | '`') {
            match (chars.get(index + 1), chars.get(index + 2)) {
                (Some('\n'), _) => {
                    out.push(' ');
                    index += 2;
                    continue;
                }
                (Some('\r'), Some('\n')) => {
                    out.push(' ');
                    index += 3;
                    continue;
                }
                _ => {}
            }
        }
        out.push(if c == '\n' || c == '\r' { ' ' } else { c });
        index += 1;
    }
    out
}

fn cookie_payload(text: &str) -> Option<String> {
    let flat = flatten_continuations(text);
    if looks_like_curl(&flat) {
        let from = |regex: &Regex| {
            regex
                .captures(&flat)
                .and_then(|captures| captures.get(1).or_else(|| captures.get(2)))
                .map(|value| value.as_str().trim().to_owned())
                .filter(|value| !value.is_empty())
        };
        return Some(from(&CURL_HEADER_COOKIE).or_else(|| from(&CURL_COOKIE_FLAG)).unwrap_or_default());
    }
    COOKIE_HEADER_LINE.captures(text).map(|captures| captures[1].trim().to_owned())
}

fn parse_cookie_header(text: &str) -> Vec<SessionCookie> {
    let collapsed = text.replace(['\n', '\r'], " ");
    let collapsed = collapsed.trim();
    let body = if collapsed.len() >= 7 && collapsed[..7].eq_ignore_ascii_case("cookie:") {
        collapsed[7..].trim()
    } else {
        collapsed
    };
    if !body.contains('=') {
        return Vec::new();
    }
    body.split(';')
        .filter_map(|pair| {
            let (name, value) = pair.split_once('=')?;
            let name = name.trim();
            if name.is_empty() || name.contains(['=', ' ', ':']) {
                return None;
            }
            Some(SessionCookie::youtube(name, value.trim()))
        })
        .collect()
}

fn parse_name_value_lines(lines: &[&str]) -> Vec<SessionCookie> {
    lines
        .iter()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with("//") {
                return None;
            }
            let (name, value) = line.split_once('=')?;
            let name = name.trim();
            if name.is_empty() || name.contains(' ') {
                return None;
            }
            Some(SessionCookie::youtube(name, value.trim()))
        })
        .collect()
}

fn parse_netscape(lines: &[&str]) -> Vec<SessionCookie> {
    lines
        .iter()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') && !line.starts_with("#HttpOnly_") {
                return None;
            }
            let columns: Vec<&str> = line.split('\t').collect();
            if columns.len() < 7 {
                return None;
            }
            let domain = columns[0].trim_start_matches("#HttpOnly_").trim_start_matches('.');
            let name = columns[5];
            if name.is_empty() || domain.is_empty() {
                return None;
            }
            let expires = columns[4].parse::<i64>().ok().map(|at| if at < 1_000_000_000_000 { at * 1000 } else { at });
            Some(SessionCookie {
                name: name.to_owned(),
                value: columns[6..].join("\t"),
                domain: domain.to_owned(),
                path: if columns[2].trim().is_empty() { "/".into() } else { columns[2].to_owned() },
                secure: columns[3].eq_ignore_ascii_case("TRUE"),
                http_only: true,
                expires_at: expires,
            })
        })
        .collect()
}

fn parse_chrome_tsv(lines: &[&str]) -> Vec<SessionCookie> {
    let rows: Vec<&str> = lines.iter().copied().filter(|line| !line.trim().is_empty()).collect();
    let Some(first) = rows.first() else { return Vec::new() };
    let header: Vec<String> = first.split('\t').map(|column| column.trim().to_lowercase()).collect();
    let has_header = header.iter().any(|c| c == "name") && header.iter().any(|c| c == "value");
    let index_of = |name: &str, fallback: usize| {
        if has_header { header.iter().position(|c| c == name).unwrap_or(fallback) } else { fallback }
    };
    let (name_i, value_i, domain_i, path_i) =
        (index_of("name", 0), index_of("value", 1), index_of("domain", 2), index_of("path", 3));
    let expires_i = header.iter().position(|c| c.starts_with("expire"));
    let http_only_i = header.iter().position(|c| c == "httponly" || c == "http only");
    rows.iter()
        .skip(usize::from(has_header))
        .filter_map(|row| {
            let columns: Vec<&str> = row.split('\t').collect();
            let name = columns.get(name_i)?.trim();
            if name.is_empty() || name.eq_ignore_ascii_case("name") {
                return None;
            }
            let domain = columns
                .get(domain_i)
                .map(|d| d.trim().trim_start_matches('.'))
                .filter(|d| !d.is_empty())
                .unwrap_or("youtube.com");
            let path = columns.get(path_i).map(|p| p.trim()).filter(|p| !p.is_empty()).unwrap_or("/");
            let expires = expires_i.and_then(|i| columns.get(i)).and_then(|raw| parse_expiry(raw));
            let http_only = http_only_i.and_then(|i| columns.get(i)).is_some_and(|raw| truthy(raw));
            Some(SessionCookie {
                name: name.to_owned(),
                value: columns.get(value_i).copied().unwrap_or_default().to_owned(),
                domain: domain.to_owned(),
                path: path.to_owned(),
                secure: true,
                http_only,
                expires_at: expires,
            })
        })
        .collect()
}

fn parse_json(text: &str) -> Vec<SessionCookie> {
    if !text.starts_with(['{', '[']) {
        return Vec::new();
    }
    JSON_OBJECT
        .find_iter(text)
        .filter_map(|object| {
            let object = object.as_str();
            let string = |keys: &[&str]| keys.iter().find_map(|key| json_string(object, key));
            let boolean = |keys: &[&str]| keys.iter().find_map(|key| json_bool(object, key));
            let name = string(&["name", "n"])?;
            let value = string(&["value", "v"])?;
            let expires = json_string(object, "expiresAt")
                .and_then(|raw| raw.parse::<i64>().ok())
                .or_else(|| {
                    json_string(object, "expirationDate")
                        .and_then(|raw| raw.parse::<f64>().ok())
                        .map(|at| (at * 1000.0) as i64)
                })
                .or_else(|| json_string(object, "e").and_then(|raw| raw.parse::<i64>().ok()));
            Some(SessionCookie {
                name,
                value,
                domain: string(&["domain", "d"])
                    .unwrap_or_else(|| "youtube.com".into())
                    .trim_start_matches('.')
                    .to_owned(),
                path: string(&["path", "p"]).unwrap_or_else(|| "/".into()),
                secure: boolean(&["secure", "s"]).unwrap_or(true),
                http_only: boolean(&["httpOnly", "httponly", "h"]).unwrap_or(false),
                expires_at: expires,
            })
        })
        .collect()
}

fn json_string(object: &str, key: &str) -> Option<String> {
    let pattern = Regex::new(&format!(r#""{}"\s*:\s*("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?)"#, regex::escape(key))).ok()?;
    let raw = pattern.captures(object)?.get(1)?.as_str().to_owned();
    Some(if let Some(inner) = raw.strip_prefix('"').and_then(|rest| rest.strip_suffix('"')) {
        unescape_json(inner)
    } else {
        raw
    })
}

fn json_bool(object: &str, key: &str) -> Option<bool> {
    let pattern = Regex::new(&format!(r#""{}"\s*:\s*(true|false)"#, regex::escape(key))).ok()?;
    Some(&pattern.captures(object)?[1] == "true")
}

fn unescape_json(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut index = 0;
    while index < chars.len() {
        if chars[index] != '\\' || index + 1 == chars.len() {
            out.push(chars[index]);
            index += 1;
            continue;
        }
        match chars[index + 1] {
            'n' => out.push('\n'),
            'r' => out.push('\r'),
            't' => out.push('\t'),
            'u' if index + 5 < chars.len() => {
                let hex: String = chars[index + 2..index + 6].iter().collect();
                out.extend(u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32));
                index += 4;
            }
            other => out.push(other),
        }
        index += 2;
    }
    out
}

fn truthy(raw: &str) -> bool {
    matches!(raw.trim().to_lowercase().as_str(), "true" | "✓" | "✔" | "1" | "yes")
}

fn parse_expiry(raw: &str) -> Option<i64> {
    let raw = raw.trim();
    if let Ok(at) = raw.parse::<i64>() {
        return Some(if (1..1_000_000_000_000).contains(&at) { at * 1000 } else { at });
    }
    raw.parse::<f64>().ok().map(|at| (at * 1000.0) as i64)
}
