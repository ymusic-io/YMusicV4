use std::time::Duration;

/// Deadline until the response head when a request sets none.
pub const DEFAULT_EXECUTE_TIMEOUT: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(10);

/// The app's HTTP clients, sharing one configuration. reqwest fixes the redirect policy per
/// client, so following and non-following requests each get their own.
#[derive(Clone)]
pub struct HttpClients {
    pub following: reqwest::Client,
    pub not_following: reqwest::Client,
    /// No redirects and no transparent gzip, with TLS details on each response: the engine's HTTP
    /// cache stores bodies as they arrive and follows redirects hop by hop itself.
    pub raw: reqwest::Client,
}

impl HttpClients {
    /// No cookie store: the engine bundle cooks every request's `Cookie` header itself.
    pub fn new() -> reqwest::Result<Self> {
        Ok(Self {
            following: builder().redirect(reqwest::redirect::Policy::limited(20)).build()?,
            not_following: builder().redirect(reqwest::redirect::Policy::none()).build()?,
            raw: builder().redirect(reqwest::redirect::Policy::none()).no_gzip().tls_info(true).build()?,
        })
    }

    pub fn pick(&self, follow_redirects: bool) -> &reqwest::Client {
        if follow_redirects { &self.following } else { &self.not_following }
    }
}

fn builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder().connect_timeout(CONNECT_TIMEOUT).read_timeout(READ_TIMEOUT)
}
