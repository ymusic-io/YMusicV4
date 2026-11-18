// base.js posts /api/stats/qoe every ~10 s while it plays, which keeps the radio awake. Answered here
// with YouTube's own empty 204. /api/stats/playback and /watchtime still go out: they write the watch
// history.

import { addInterceptor } from "../../protocol/http/intercept";
import type { HttpChain } from "../../protocol/http/intercept";

function isQoeUrl(url: string): boolean {
  const q = url.indexOf("?");
  const path = q >= 0 ? url.slice(0, q) : url;
  return path.slice(path.length - 14) === "/api/stats/qoe";
}

class NoContentResponse implements HttpResponse {
  readonly status = 204;
  readonly statusText = "No Content";
  readonly url: string;
  readonly ok = true;
  readonly headers = {
    entries: [] as HeaderEntry[],
    get: function (_name: string): string | null {
      return null;
    },
    has: function (_name: string): boolean {
      return false;
    },
  };

  constructor(url: string) {
    this.url = url;
  }

  text(): Promise<string> {
    return Promise.resolve("");
  }
  bytes(): Promise<Uint8Array> {
    return Promise.resolve(new Uint8Array(0));
  }
  jsonObject(): Promise<Record<string, unknown>> {
    return Promise.reject(new TypeError("response body is not a JSON object"));
  }
  jsonArray(): Promise<unknown[]> {
    return Promise.reject(new TypeError("response body is not a JSON array"));
  }
  stream(): HttpBodyStream {
    return {
      read: function () {
        return Promise.resolve(-1);
      },
    };
  }
  cancel(): void {}
  close(): void {}
}

function qoeIntercept(chain: HttpChain): Promise<HttpResponse> {
  const url = chain.request.url;
  if (!isQoeUrl(url)) return chain.proceed();
  return Promise.resolve(new NoContentResponse(url));
}

let installed = false;

export function installQoeBlock(): void {
  if (installed) return;
  installed = true;
  addInterceptor(qoeIntercept);
}
