//! The data dir holds the JVM app's files under the JVM app's names, so a release build takes over
//! `~/.ymusic` in place and the JVM app still opens it after a rollback. Databases this app's
//! earlier builds kept under other names are renamed once at boot, before anything opens them.

use std::path::{Path, PathBuf};

use ym_common::log::Logger;
use ym_settings::{AppDb, SettingsStore};

use crate::cookie_session::{COOKIE_JAR_SECRET, FALLBACK_KEY_SECRET};

/// The JVM app's data dir, which a release build uses as its own.
pub fn release_data_dir() -> PathBuf {
    dirs::home_dir().unwrap_or_else(std::env::temp_dir).join(".ymusic")
}

const RENAMED: [(&str, &str); 4] = [
    ("media_store.db", "media-store.db"),
    ("play_history.db", "play-history.db"),
    ("sponsor_block.db", "sponsor-block.db"),
    ("engine-cache/index.db", "engine-cache/engine-cache.db"),
];
const SIDECARS: [&str; 3] = ["-wal", "-shm", "-journal"];
/// Caches earlier builds kept where the JVM app keeps nothing; refilled under the JVM app's names.
const OBSOLETE_DIRS: [&str; 1] = ["bundles"];

/// Blocking. Runs only in the instance that holds the data dir, so nothing has the files open.
pub fn adopt_jvm_names(data_dir: &Path, log: &Logger) {
    let log = log.fork("data-files");
    for (old_name, new_name) in RENAMED {
        let (old, new) = (data_dir.join(old_name), data_dir.join(new_name));
        if !old.exists() {
            continue;
        }
        let outcome = if new.exists() && !holds_no_tables(&new) {
            log.info(&format!("{new_name} already exists, removing the older {old_name}"));
            remove_database(&old)
        } else {
            rename_database(&old, &new)
        };
        match outcome {
            Ok(()) => log.info(&format!("{old_name} is now {new_name}")),
            Err(error) => log.warn(&format!("R90200 {old_name} could not become {new_name}"), Some(&error)),
        }
    }
    for name in OBSOLETE_DIRS {
        match std::fs::remove_dir_all(data_dir.join(name)) {
            Ok(()) => log.info(&format!("removed {name}, a cache the JVM app keeps elsewhere")),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => log.warn(&format!("R90404 the obsolete {name} folder could not be removed"), Some(&error)),
        }
    }
}

/// Folds the write-ahead log into the file first, so the file moves alone and no frame is left
/// behind under the old name.
fn rename_database(old: &Path, new: &Path) -> std::io::Result<()> {
    rusqlite::Connection::open(old)
        .and_then(|connection| {
            connection.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))?;
            connection.close().map_err(|(_, error)| error)
        })
        .map_err(std::io::Error::other)?;
    for suffix in SIDECARS {
        remove_if_exists(&with_suffix(new, suffix))?;
    }
    std::fs::rename(old, new)?;
    for suffix in SIDECARS {
        remove_if_exists(&with_suffix(old, suffix))?;
    }
    Ok(())
}

/// A database file something created without writing to it (an inspection opened the wrong name):
/// the older file's data must not be dropped in its favour.
fn holds_no_tables(path: &Path) -> bool {
    let open = rusqlite::Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY);
    open.and_then(|connection| {
        connection.query_row("SELECT count(*) FROM sqlite_master", [], |row| row.get::<_, i64>(0))
    })
    .is_ok_and(|count| count == 0)
}

fn remove_database(path: &Path) -> std::io::Result<()> {
    remove_if_exists(path)?;
    for suffix in SIDECARS {
        remove_if_exists(&with_suffix(path, suffix))?;
    }
    Ok(())
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(suffix);
    PathBuf::from(name)
}

fn remove_if_exists(path: &Path) -> std::io::Result<()> {
    match std::fs::remove_file(path) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

/// Settings earlier builds kept under their own keys, moved to the JVM app's: `(old, new)`.
const RENAMED_SETTINGS: [(&str, &str); 1] = [("playback.volume", "player.volume")];

/// Blocking.
pub fn adopt_jvm_settings(app_db: &AppDb, log: &Logger) {
    for (old, new) in RENAMED_SETTINGS {
        let Ok(Some(value)) = app_db.get_raw(old) else { continue };
        let moved = match app_db.get_raw(new) {
            Ok(Some(_)) => Ok(()),
            _ => app_db.put_raw(new, value),
        }
        .and_then(|()| Ok(app_db.delete_setting(old)?));
        if let Err(error) = moved {
            log.warn(&format!("R90203 setting {old} could not move to {new}"), Some(error.as_ref()));
        }
    }
}

const LEGACY_SETTINGS: &str = "settings.properties";
/// Each imported as the secret of its own name; the DPAPI key is the JVM app's Windows cookie key.
const LEGACY_SECRETS: [&str; 3] = [COOKIE_JAR_SECRET, "cookie-key.dpapi", FALLBACK_KEY_SECRET];
const LEGACY_LEFTOVERS: [&str; 3] = ["api-cookies.json.tmp", "api-cookies.json.reseal", "cookie-key.dpapi.tmp"];

/// The JVM app's one-time import (`AppStore.importLegacyFiles`) of what 4.0.6 and earlier kept as
/// loose files, for an install that comes here straight from one of those. The files go once their
/// rows commit. Blocking; runs before the cookie jar opens.
pub fn import_legacy_files(app_db: &AppDb, data_dir: &Path, log: &Logger) {
    let settings_file = data_dir.join(LEGACY_SETTINGS);
    let secret_files = LEGACY_SECRETS.map(|name| (name, data_dir.join(name)));
    if !settings_file.exists() && secret_files.iter().all(|(_, file)| !file.exists()) {
        return;
    }
    let log = log.fork("data-files");
    let settings = std::fs::read(&settings_file).and_then(|bytes| parse_properties(&bytes));
    let settings = match settings {
        Ok(settings) => settings,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Vec::new(),
        Err(error) => {
            log.warn(&format!("R90400 legacy {LEGACY_SETTINGS} unreadable, starting on defaults"), Some(&error));
            Vec::new()
        }
    };
    let mut secrets = Vec::new();
    for (name, file) in &secret_files {
        match std::fs::read(file) {
            // An empty file is what a power loss left behind: nothing to keep.
            Ok(bytes) if bytes.is_empty() => {}
            Ok(bytes) => secrets.push((name.to_string(), bytes)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                log.warn(
                    &format!("R90401 legacy {name} unreadable, the import waits for the next launch"),
                    Some(&error),
                );
                return;
            }
        }
    }
    if let Err(error) = app_db.import_absent(&settings, &secrets) {
        log.warn("R90402 legacy settings and cookie files could not be imported into app.db", Some(&error));
        return;
    }
    let imported = [settings_file].into_iter().chain(secret_files.into_iter().map(|(_, file)| file));
    for file in imported.chain(LEGACY_LEFTOVERS.iter().map(|name| data_dir.join(name))) {
        if let Err(error) = remove_if_exists(&file) {
            log.warn(&format!("R90403 imported legacy file {} could not be removed", file.display()), Some(&error));
        }
    }
    log.info("imported legacy settings and cookie files into app.db");
}

const PROPERTIES_BLANK: [char; 3] = [' ', '\t', '\x0c'];

/// `java.util.Properties.load(InputStream)`: ISO-8859-1, `#` / `!` comment lines, `=` / `:` /
/// blank separators, `\` line continuations and escapes. A malformed `\uXXXX` fails, as it throws there.
fn parse_properties(bytes: &[u8]) -> std::io::Result<Vec<(String, String)>> {
    let text: String = bytes.iter().map(|byte| char::from(*byte)).collect();
    let text = text.replace("\r\n", "\n").replace('\r', "\n");
    let mut natural_lines = text.split('\n');
    let mut entries = Vec::new();
    while let Some(line) = natural_lines.next() {
        let mut current = line.trim_start_matches(PROPERTIES_BLANK);
        if current.is_empty() || current.starts_with(['#', '!']) {
            continue;
        }
        let mut logical = String::new();
        loop {
            let trailing_backslashes = current.chars().rev().take_while(|character| *character == '\\').count();
            if trailing_backslashes % 2 == 0 {
                logical.push_str(current);
                break;
            }
            logical.push_str(&current[..current.len() - 1]);
            match natural_lines.next() {
                Some(next) => current = next.trim_start_matches(PROPERTIES_BLANK),
                None => break,
            }
        }
        let mut escaped = false;
        let key_end = logical
            .char_indices()
            .find(|(_, character)| {
                let separator = !escaped && (matches!(character, '=' | ':') || PROPERTIES_BLANK.contains(character));
                escaped = !escaped && *character == '\\';
                separator
            })
            .map_or(logical.len(), |(index, _)| index);
        let value = logical[key_end..].trim_start_matches(PROPERTIES_BLANK);
        let value = value.strip_prefix(['=', ':']).unwrap_or(value).trim_start_matches(PROPERTIES_BLANK);
        entries.push((unescape_properties(&logical[..key_end])?, unescape_properties(value)?));
    }
    Ok(entries)
}

fn unescape_properties(text: &str) -> std::io::Result<String> {
    let malformed = || std::io::Error::new(std::io::ErrorKind::InvalidData, "malformed \\uxxxx encoding");
    let mut units: Vec<u16> = Vec::with_capacity(text.len());
    let mut characters = text.chars();
    while let Some(character) = characters.next() {
        let unescaped = match character {
            '\\' => match characters.next() {
                Some('u') => {
                    let digits: String = characters.by_ref().take(4).collect();
                    if digits.len() != 4 || !digits.chars().all(|digit| digit.is_ascii_hexdigit()) {
                        return Err(malformed());
                    }
                    units.push(u16::from_str_radix(&digits, 16).map_err(|_| malformed())?);
                    continue;
                }
                Some('t') => '\t',
                Some('n') => '\n',
                Some('r') => '\r',
                Some('f') => '\x0c',
                Some(other) => other,
                None => break,
            },
            other => other,
        };
        units.extend(unescaped.encode_utf16(&mut [0; 2]).iter());
    }
    Ok(String::from_utf16_lossy(&units))
}
