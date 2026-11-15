//! Generates the string tables from YMusicV4's compose-resources `values*/strings.xml`: one key
//! set (the default `values/`), every locale's translations, English fallback where a locale omits
//! a key (brand marks are omitted on purpose). A `string-array` item is the string `name.N`
//! (`month_abbr` item 0 = `Str::MonthAbbr0`).

use std::collections::BTreeMap;
use std::fmt::Write;
use std::path::{Path, PathBuf};

const QUANTITIES: [&str; 6] = ["zero", "one", "two", "few", "many", "other"];

struct Locale {
    code: String,
    strings: BTreeMap<String, String>,
    plurals: BTreeMap<String, BTreeMap<String, String>>,
}

fn main() {
    println!("cargo:rerun-if-env-changed=YMUSIC_V4_DIR");
    let v4 = PathBuf::from(std::env::var("YMUSIC_V4_DIR").expect("YMUSIC_V4_DIR (set in .cargo/config.toml)"));
    let resources = v4.join("commonUi/src/commonMain/composeResources");
    let mut locales = Vec::new();
    let mut dirs: Vec<PathBuf> = std::fs::read_dir(&resources)
        .unwrap_or_else(|error| panic!("{}: {error}", resources.display()))
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.join("strings.xml").is_file())
        .collect();
    dirs.sort();
    for dir in dirs {
        let name = dir.file_name().unwrap().to_string_lossy().into_owned();
        let Some(code) = locale_code(&name) else { continue };
        // values-in / values-iw duplicate values-id / values-he; the modern folder sorts first.
        if locales.iter().any(|locale: &Locale| locale.code == code) {
            continue;
        }
        let file = dir.join("strings.xml");
        println!("cargo:rerun-if-changed={}", file.display());
        locales.push(parse(&file, code));
    }
    let default = locales.iter().position(|locale| locale.code == "en").expect("values/strings.xml");
    locales.swap(0, default);

    let keys: Vec<&String> = locales[0].strings.keys().collect();
    let plural_keys: Vec<&String> = locales[0].plurals.keys().collect();
    let mut out = String::new();
    writeln!(out, "/// Every UI string key, from `values/strings.xml`.").unwrap();
    writeln!(out, "#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]\n#[repr(u16)]\npub enum Str {{").unwrap();
    for key in &keys {
        writeln!(out, "    {},", camel(key)).unwrap();
    }
    writeln!(out, "}}\n").unwrap();
    writeln!(out, "#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]\n#[repr(u16)]\npub enum Plural {{").unwrap();
    for key in &plural_keys {
        writeln!(out, "    {},", camel(key)).unwrap();
    }
    writeln!(out, "}}\n").unwrap();
    writeln!(out, "pub(crate) static LOCALES: &[LocaleTable] = &[").unwrap();
    for locale in &locales {
        writeln!(out, "    LocaleTable {{\n        code: {:?},\n        strings: &[", locale.code).unwrap();
        for key in &keys {
            match locale.strings.get(*key) {
                Some(text) => writeln!(out, "            Some({text:?}),").unwrap(),
                None => writeln!(out, "            None,").unwrap(),
            }
        }
        writeln!(out, "        ],\n        plurals: &[").unwrap();
        for key in &plural_keys {
            let forms = locale.plurals.get(*key);
            let row: Vec<String> = QUANTITIES
                .iter()
                .map(|quantity| match forms.and_then(|forms| forms.get(*quantity)) {
                    Some(text) => format!("Some({text:?})"),
                    None => "None".to_owned(),
                })
                .collect();
            writeln!(out, "            [{}],", row.join(", ")).unwrap();
        }
        writeln!(out, "        ],\n    }},").unwrap();
    }
    writeln!(out, "];").unwrap();
    let missing: usize =
        locales[1..].iter().map(|locale| keys.iter().filter(|key| !locale.strings.contains_key(**key)).count()).sum();
    if missing > 0 {
        println!("cargo:warning=ym-i18n: {missing} translations fall back to English");
    }
    std::fs::write(PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("strings.rs"), out).unwrap();
}

/// Android resource qualifiers to BCP 47; `None` skips a folder that isn't a plain locale.
fn locale_code(folder: &str) -> Option<String> {
    let qualifier = match folder.strip_prefix("values") {
        Some("") => return Some("en".into()),
        Some(rest) => rest.strip_prefix('-')?,
        None => return None,
    };
    let code = match qualifier {
        "in" => "id".to_owned(),
        "iw" => "he".to_owned(),
        other => other.replace("-r", "-"),
    };
    code.chars().all(|c| c.is_ascii_alphanumeric() || c == '-').then_some(code)
}

fn parse(file: &Path, code: String) -> Locale {
    let text = std::fs::read_to_string(file).unwrap();
    let doc = roxmltree::Document::parse(&text).unwrap_or_else(|error| panic!("{}: {error}", file.display()));
    let mut locale = Locale { code, strings: BTreeMap::new(), plurals: BTreeMap::new() };
    for node in doc.root_element().children().filter(|node| node.is_element()) {
        let Some(name) = node.attribute("name") else { continue };
        match node.tag_name().name() {
            "string" => {
                locale.strings.insert(name.to_owned(), unescape(&inner_text(node)));
            }
            "string-array" => {
                let items = node.children().filter(|item| item.has_tag_name("item"));
                for (index, item) in items.enumerate() {
                    locale.strings.insert(format!("{name}.{index}"), unescape(&inner_text(item)));
                }
            }
            "plurals" => {
                let forms = node
                    .children()
                    .filter(|item| item.has_tag_name("item"))
                    .filter_map(|item| Some((item.attribute("quantity")?.to_owned(), unescape(&inner_text(item)))))
                    .collect();
                locale.plurals.insert(name.to_owned(), forms);
            }
            _ => {}
        }
    }
    locale
}

fn inner_text(node: roxmltree::Node<'_, '_>) -> String {
    node.descendants().filter(|child| child.is_text()).filter_map(|child| child.text()).collect()
}

/// Android string escapes: `\n`, `\t`, `\'`, `\"`, `\\`, `\@`, `\?`; an outer pair of quotes.
fn unescape(raw: &str) -> String {
    let raw = raw.strip_prefix('"').and_then(|rest| rest.strip_suffix('"')).unwrap_or(raw);
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some('u') => {
                let hex: String = chars.by_ref().take(4).collect();
                out.extend(u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32));
            }
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
}

fn camel(key: &str) -> String {
    let mut out = String::new();
    let mut upper = true;
    for c in key.chars() {
        if c == '_' || c == '.' || c == '-' {
            upper = true;
        } else if upper {
            out.extend(c.to_uppercase());
            upper = false;
        } else {
            out.push(c);
        }
    }
    if out.starts_with(|c: char| c.is_ascii_digit()) {
        out.insert(0, 'S');
    }
    out
}
