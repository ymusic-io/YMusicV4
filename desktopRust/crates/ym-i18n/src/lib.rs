//! UI strings, generated from YMusicV4's compose-resources so both apps share one translation set.
//! Never hardcode UI text: add the string to every `values*/strings.xml` in YMusicV4.

use std::fmt::Display;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicUsize, Ordering};

use intl_pluralrules::{PluralCategory, PluralRuleType, PluralRules};
use unic_langid::LanguageIdentifier;

include!(concat!(env!("OUT_DIR"), "/strings.rs"));

pub(crate) struct LocaleTable {
    code: &'static str,
    strings: &'static [Option<&'static str>],
    plurals: &'static [[Option<&'static str>; 6]],
}

static CURRENT: AtomicUsize = AtomicUsize::new(0);

/// Locale codes that have translations, default first.
pub fn available_locales() -> impl Iterator<Item = &'static str> {
    LOCALES.iter().map(|locale| locale.code)
}

/// Picks the closest translation for a BCP 47 tag (`pt-BR` → `pt`); unknown falls back to English.
/// Returns the code chosen.
pub fn set_locale(tag: &str) -> &'static str {
    let tag = tag.replace('_', "-");
    let language = tag.split('-').next().unwrap_or_default().to_ascii_lowercase();
    let index = LOCALES
        .iter()
        .position(|locale| locale.code.eq_ignore_ascii_case(&tag))
        .or_else(|| LOCALES.iter().position(|locale| locale.code.eq_ignore_ascii_case(&language)))
        .unwrap_or(0);
    CURRENT.store(index, Ordering::Relaxed);
    LOCALES[index].code
}

/// [`set_locale`] from the OS language.
pub fn use_system_locale() -> &'static str {
    set_locale(&sys_locale::get_locale().unwrap_or_else(|| "en".into()))
}

pub fn current_locale() -> &'static str {
    LOCALES[CURRENT.load(Ordering::Relaxed)].code
}

pub fn t(key: Str) -> &'static str {
    let index = key as usize;
    LOCALES[CURRENT.load(Ordering::Relaxed)].strings[index].or(LOCALES[0].strings[index]).unwrap_or_default()
}

/// A string with Android positional arguments (`%1$s`, `%2$d`, `%s`, `%%`).
pub fn tf(key: Str, args: &[&dyn Display]) -> String {
    format_args_android(t(key), args)
}

/// The plural form for `count` in the current locale, formatted with `args`.
pub fn plural(key: Plural, count: i64, args: &[&dyn Display]) -> String {
    let index = key as usize;
    let locale = &LOCALES[CURRENT.load(Ordering::Relaxed)];
    let slot = category_slot(locale.code, count);
    let pick = |table: &LocaleTable| table.plurals[index][slot].or(table.plurals[index][5]);
    format_args_android(pick(locale).or_else(|| pick(&LOCALES[0])).unwrap_or_default(), args)
}

type RuleCache = std::sync::Mutex<Vec<(String, Option<PluralRules>)>>;

fn category_slot(code: &str, count: i64) -> usize {
    static RULES: OnceLock<RuleCache> = OnceLock::new();
    let rules = RULES.get_or_init(Default::default);
    let mut rules = rules.lock().unwrap();
    if !rules.iter().any(|(cached, _)| cached == code) {
        let rule = code
            .parse::<LanguageIdentifier>()
            .ok()
            .and_then(|id| PluralRules::create(id, PluralRuleType::CARDINAL).ok());
        rules.push((code.to_owned(), rule));
    }
    let category = rules
        .iter()
        .find(|(cached, _)| cached == code)
        .and_then(|(_, rule)| rule.as_ref())
        .and_then(|rule| rule.select(count).ok())
        .unwrap_or(PluralCategory::OTHER);
    match category {
        PluralCategory::ZERO => 0,
        PluralCategory::ONE => 1,
        PluralCategory::TWO => 2,
        PluralCategory::FEW => 3,
        PluralCategory::MANY => 4,
        PluralCategory::OTHER => 5,
    }
}

fn format_args_android(template: &str, args: &[&dyn Display]) -> String {
    let mut out = String::with_capacity(template.len() + 16);
    let mut next_implicit = 0;
    let mut rest = template;
    while let Some(start) = rest.find('%') {
        out.push_str(&rest[..start]);
        let spec = &rest[start + 1..];
        if let Some(tail) = spec.strip_prefix('%') {
            out.push('%');
            rest = tail;
            continue;
        }
        let digits: String = spec.chars().take_while(char::is_ascii_digit).collect();
        let after_digits = &spec[digits.len()..];
        let (position, conversion_at) = match after_digits.strip_prefix('$') {
            Some(after) if !digits.is_empty() => (digits.parse::<usize>().unwrap_or(1) - 1, after),
            _ => {
                next_implicit += 1;
                (next_implicit - 1, spec)
            }
        };
        match conversion_at.chars().next() {
            Some(conversion @ ('s' | 'd' | 'f')) => {
                match args.get(position) {
                    Some(arg) => out.push_str(&arg.to_string()),
                    None => out.push_str(&format!("%{conversion}")),
                }
                rest = &conversion_at[1..];
            }
            _ => {
                out.push('%');
                rest = spec;
            }
        }
    }
    out.push_str(rest);
    out
}
