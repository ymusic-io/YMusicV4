// A request without a User-Agent gets the host's own (okhttp, the Android WebView UA), so Google
// would see one visitor and PO token from several clients.

import { addInterceptor } from "../../protocol/http/intercept";
import type { HttpChain } from "../../protocol/http/intercept";
import { DESKTOP_UA } from "./session";

function userAgentIntercept(chain: HttpChain): Promise<HttpResponse> {
  const req = chain.request;
  for (const entry of req.headers) {
    if (entry[0].toLowerCase() === "user-agent") return chain.proceed();
  }
  const headers = req.headers.slice();
  headers.push(["User-Agent", DESKTOP_UA]);
  return chain.proceed(Object.assign({}, req, { headers: headers }));
}

addInterceptor(userAgentIntercept);
