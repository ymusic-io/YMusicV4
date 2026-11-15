//! OkHttp `Headers` semantics over an ordered list: names compare case-insensitively, `get` is
//! the last value.

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct Headers(Vec<(String, String)>);

impl Headers {
    pub fn new(pairs: Vec<(String, String)>) -> Self {
        Self(pairs)
    }

    pub fn len(&self) -> usize {
        self.0.len()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &str)> {
        self.0.iter().map(|(name, value)| (name.as_str(), value.as_str()))
    }

    pub fn get(&self, name: &str) -> Option<&str> {
        self.0.iter().rev().find(|(candidate, _)| candidate.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
    }

    pub fn values(&self, name: &str) -> Vec<&str> {
        self.0
            .iter()
            .filter(|(candidate, _)| candidate.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
            .collect()
    }

    pub fn add(&mut self, name: impl Into<String>, value: impl Into<String>) {
        self.0.push((name.into(), value.into()));
    }

    /// `Headers.Builder.addLenient(line)`: split at the first `:` after the first character.
    pub fn add_lenient(&mut self, line: &str) {
        let (name, value) = match line.get(1..).and_then(|rest| rest.find(':')) {
            Some(index) => (&line[..index + 1], &line[index + 2..]),
            None => match line.strip_prefix(':') {
                Some(value) => ("", value),
                None => ("", line),
            },
        };
        self.0.push((name.to_owned(), value.trim().to_owned()));
    }

    pub fn remove_all(&mut self, name: &str) {
        self.0.retain(|(candidate, _)| !candidate.eq_ignore_ascii_case(name));
    }
}
