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
            let mut builder = Response::builder().status(response.status());
            for (name, value) in response.headers() {
                builder = builder.header(name, value);
            }
            let bytes = response.bytes().await?;
            anyhow::Ok(builder.body(AsyncBody::from(bytes))?)
        });
        Box::pin(async move { task.await? })
    }
}
