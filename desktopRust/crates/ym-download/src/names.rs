//! The file-name policy, shared by the options dialog and publish: no character any of Windows,
//! macOS or Linux rejects, so a name that works here works everywhere.

use std::sync::LazyLock;

use regex::Regex;

/// The characters a file name may never contain, as shown to the user.
pub const FORBIDDEN_DISPLAY: &str = "\\ / : * ? \" < > |";

/// A stem's UTF-8 budget: names cap at 255 bytes, minus room for `_N` and the extension.
pub const MAX_STEM_BYTES: usize = 200;

fn is_forbidden(character: char) -> bool {
    "\\/:*?\"<>|".contains(character) || character < ' ' || character == '\u{7F}'
}

pub fn strip_forbidden(input: &str) -> String {
    input.chars().filter(|character| !is_forbidden(*character)).collect()
}

pub fn has_forbidden(input: &str) -> bool {
    input.chars().any(is_forbidden)
}

/// The default stem for a title: each run of forbidden characters (and the spaces around it)
/// becomes " - ", so "ARTIST | SONG" reads "ARTIST - SONG".
pub fn from_title(title: &str) -> String {
    static FORBIDDEN_RUN: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"\s*[\x00-\x1F\x7F\\/:*?"<>|]+\s*"#).unwrap());
    static REPEATED_SEPARATOR: LazyLock<Regex> = LazyLock::new(|| Regex::new("( - )+").unwrap());
    let separated = FORBIDDEN_RUN.replace_all(title, " - ");
    finalize(&REPEATED_SEPARATOR.replace_all(&separated, " - "))
}

/// `file_name` when `taken` says it is free, else `stem_1.ext`, `stem_2.ext`…: a download never
/// overwrites a file.
pub fn free_name(file_name: &str, taken: impl Fn(&str) -> bool) -> String {
    if !taken(file_name) {
        return file_name.to_owned();
    }
    let dot = file_name.rfind('.').filter(|index| *index > 0).unwrap_or(file_name.len());
    let (stem, extension) = file_name.split_at(dot);
    (1..).map(|number| format!("{stem}_{number}{extension}")).find(|candidate| !taken(candidate)).unwrap()
}

/// The stem actually written: forbidden characters dropped, spaces collapsed, no leading/trailing
/// spaces, dashes or dots (Windows rejects a trailing dot or space), capped at [`MAX_STEM_BYTES`].
/// May be empty: the caller falls back to something non-empty.
pub fn finalize(input: &str) -> String {
    let collapsed = strip_forbidden(input).split_whitespace().collect::<Vec<_>>().join(" ");
    let trimmed = collapsed.trim_matches(|character| character == ' ' || character == '.' || character == '-');
    cap_bytes(trimmed, MAX_STEM_BYTES).trim_end_matches([' ', '.']).to_owned()
}

/// The longest prefix within `max_bytes` of UTF-8, cut between characters.
pub fn cap_bytes(input: &str, max_bytes: usize) -> String {
    let mut end = 0;
    for (index, character) in input.char_indices() {
        if index + character.len_utf8() > max_bytes {
            break;
        }
        end = index + character.len_utf8();
    }
    input[..end].to_owned()
}
