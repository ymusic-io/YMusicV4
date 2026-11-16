//! gpui's image loading over the app's reqwest client.

use std::sync::Arc;

use futures::future::BoxFuture;
use gpui_kit::http_client::{AsyncBody, HttpClient, Request, Response, Url, http::HeaderValue};
use tokio::runtime::Handle;

pub struct ReqwestHttpClient {
    client: reqwest::Client,
    tokio: Handle,
}

impl ReqwestHttpClient {
    pub fn new(client: reqwest::Client, tokio: Handle) -> Arc<Self> {
        Arc::new(Self { client, tokio })
    }
}

impl HttpClient for ReqwestHttpClient {
    fn user_agent(&self) -> Option<&HeaderValue> {
        None
    }

    fn proxy(&self) -> Option<&Url> {
        None
    }

    /// Image GETs only: the request body is ignored.
    fn send(&self, request: Request<AsyncBody>) -> BoxFuture<'static, anyhow::Result<Response<AsyncBody>>> {
        let client = self.client.clone();
        let (parts, _) = request.into_parts();
        let task = self.tokio.spawn(async move {
            let response = client.request(parts.method, parts.uri.to_string()).headers(parts.headers).send().await?;
            let status = if response.status() == reqwest::StatusCode::NOT_FOUND
                && is_no_thumbnail_url(response.url().as_str())
            {
                reqwest::StatusCode::OK
            } else {
                response.status()
            };
            let mut builder = Response::builder().status(status);
            for (name, value) in response.headers() {
                builder = builder.header(name, value);
            }
            let bytes = response.bytes().await?;
            anyhow::Ok(builder.body(AsyncBody::from(bytes))?)
        });
        Box::pin(async move { task.await? })
    }
}

/// `i.ytimg.com/img/no_thumbnail.jpg`, YouTube's thumbnail for a playlist with nothing to show, is a
/// real placeholder JPEG served as 404; image caches drop non-2xx images, so it is answered as 200.
pub fn is_no_thumbnail_url(url: &str) -> bool {
    url.contains("/img/no_thumbnail")
}
