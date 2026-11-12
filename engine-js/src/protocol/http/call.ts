import { open, onAccept } from "../../transport";
import { hostError } from "../command";
import { dispatchHttp } from "./intercept";
import type { HttpReq } from "./intercept";
import { Response } from "./response";
import type { BodyKind, RequestHead, RequestOpts } from "./wire";
import type { JsLogger } from "../logger";
import { rootTrail } from "../logger";

// One request execution. execute() resolves the Response (once); cancel() aborts at ANY stage:
// before execute() (it refuses to start), mid-flight (a RESET of the control pipe's request
// direction — the server watcher treats it like the in-band -1 and aborts the exchange, even
// one parked inside its transport with the body never pulled; execute() rejects
// "request cancelled"), or after the Response (delegates to Response.cancel()).
export class HttpCallImpl implements HttpCall {
  private _method: string;
  private _url: string;
  private _headers: HeaderEntry[];
  private _kind: BodyKind;
  private _body: HttpBody | null;
  private _len: number;
  private _opts: RequestOpts | null;
  private _log: JsLogger | null; // the caller's crumb-trail logger for birth-site logs
  private _io: Pipe | null = null;
  private _responseRef: WeakRef<Response> | null = null;
  private _bodyReg: { remove(): void } | null = null; // the body pipe's one-shot accept registration, until opened
  private _bodyPipe: Pipe | null = null; // the body pipe, while the host is pulling from it
  private _started = false;
  private _cancelled = false;

  constructor(
    method: string,
    url: string,
    headers: HeaderEntry[],
    kind: BodyKind,
    body: HttpBody | null,
    len: number,
    opts: RequestOpts | null,
    log: JsLogger | null,
  ) {
    this._method = method;
    this._url = url;
    this._headers = headers;
    this._kind = kind;
    this._body = body;
    this._len = len;
    this._opts = opts;
    this._log = log;
  }
  // Retire the body plumbing: unregister a never-opened body command, abort an in-flight body
  // transfer. Runs on every terminal path (response consumed, cancel, failure) — a request
  // whose body the server never pulled (cache hit, early error) must not leak the registration.
  private _retireBody(reason: string): void {
    const reg = this._bodyReg;
    if (reg) {
      this._bodyReg = null;
      reg.remove();
    }
    const bp = this._bodyPipe;
    if (bp) {
      this._bodyPipe = null;
      bp.sink.close(reason); // RESET: allowed mid-write; a no-op if the body already ENDed cleanly
      bp.source.close();
    }
  }
  cancel(): void {
    if (this._cancelled) return;
    this._cancelled = true;
    const res = this._responseRef ? this._responseRef.deref() : undefined;
    if (res) return res.cancel(); // its finish/abort retires the body
    this._retireBody("cancelled");
    const io = this._io;
    if (io) {
      io.sink.close("cancelled"); // RESET: allowed mid-write, rejects a parked send
      io.source.close();
    }
  }
  async execute(): Promise<HttpResponse> {
    if (this._started) throw new Error("execute() called twice");
    this._started = true;
    if (this._cancelled) throw new Error("request cancelled");
    const self = this;
    return dispatchHttp(
      {
        method: this._method,
        url: this._url,
        headers: this._headers,
        body: this._body,
        opts: this._opts,
        log: this._log,
      },
      function (req) {
        return self._send(req);
      },
    );
  }

  private async _send(req: HttpReq): Promise<HttpResponse> {
    const self = this;
    this._url = req.url;
    this._headers = req.headers;
    this._body = req.body;
    this._opts = req.opts;
    this._log = req.log;
    this._method = req.method;
    let kind: BodyKind = null;
    if (req.body != null) {
      if (typeof req.body === "string") kind = "text";
      else if (req.body instanceof Uint8Array) kind = "bytes";
      else kind = "callback";
    }
    this._kind = kind;
    const io = open("http");
    this._io = io;
    // The body rides its own pipe: register a one-shot command the server opens lazily, on the
    // transport's FIRST body pull — a response that never needs the body never transfers it.
    // Raw bytes, END = body complete; the callback kind gets the pipe's real streaming sink.
    // Wire keys QUOTED/bracketed — this head crosses to Kotlin as JSON and must survive Closure's
    // ADVANCED renaming literally.
    const head: RequestHead = {
      "method": this._method,
      "url": this._url,
      "headers": this._headers,
    };
    if (this._kind !== null) {
      const bodyCmd = "http/body/" + io.id;
      head["bodyCmd"] = bodyCmd;
      this._bodyReg = onAccept(bodyCmd, async function (bp) {
        // One-shot: a registration left in the accept table keeps this whole call alive.
        const reg = self._bodyReg;
        self._bodyReg = null;
        if (reg) reg.remove();
        self._bodyPipe = bp;
        let p: MaybePromise;
        if (self._kind === "text") {
          p = bp.sink.writeUtf8(self._body as string);
          if (p) await p;
        } else if (self._kind === "bytes") {
          p = bp.sink.writeBytes(self._body as Uint8Array);
          if (p) await p;
        } else {
          // capability: a sink the callback cannot read or reopen
          await (self._body as (io: PipeSink) => unknown)(bp.sink);
        }
        await bp.sink.close(); // END: body complete
        self._bodyPipe = null;
      });
    }
    if (this._opts) head["opts"] = this._opts;
    // Threads the caller's logging context to the host so a transport failure (request/response
    // body) logs at its birth site with the full trail (HOST_PROTOCOLS "Logging context"). Even a
    // logger-less request carries the bundle-revision root — every serve names its bundle.
    head["log"] = this._log ? this._log.wire() : rootTrail();
    try {
      // control pipe: the JSON head (self-framing), then NOTHING outbound except a possible -1
      // cancel — the channel stays unobstructed at every stage.
      let p = io.sink.writeObjectAsJson(head);
      if (p) await p;
      p = io.sink.flush();
      if (p) await p;

      // response: JSON head, then the raw body bytes to EOF on the same pipe (bracket access:
      // wire data from the host, not a declared structure).
      const rh = await io.source.readJsonObject();
      const res = new Response(
        (rh["status"] as number) | 0,
        rh["message"] === undefined ? "" : String(rh["message"]),
        rh["url"] === undefined ? this._url : String(rh["url"]),
        (rh["headers"] as HeaderEntry[] | undefined) || [],
        io,
        function (reason: string) {
          self._retireBody(reason);
        },
        this._opts ? this._opts["timeoutMs"] : undefined,
        typeof rh["cache"] === "string" ? rh["cache"] : undefined,
      );
      this._responseRef = new WeakRef(res);
      return res;
    } catch (e) {
      // No Response will be returned, so nothing else will ever own the control pipe — retire it
      // here (like OkHttp's Call.execute() releasing its resources on a failed call). Idempotent
      // with a later cancel() (pipe close is first-close-wins).
      this._retireBody("request failed");
      const io2 = this._io;
      if (io2) {
        this._io = null;
        io2.sink.close();
        io2.source.close();
      }
      if (this._cancelled) throw new Error("request cancelled", { cause: e });
      throw hostError(e);
    }
  }
}
