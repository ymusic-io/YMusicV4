// The typed contract of the engine's global surface. transport.ts IMPLEMENTS ymix/pipes/timers
// (checked against these declarations, like every other module that ASSIGNS a global here); the
// C installs `versions`. Ambient (no import/export) so every module sees them.

/** Pipe reads return the value when it is already buffered, else a Promise C settles — always
 *  awaitable. Writes return undefined when done synchronously, else a Promise = backpressure
 *  (test with `if (p) await p`). */
type MaybePromise<T = void> = T | Promise<T>;

/** The readable end (C-owned buffer). close() abandons it: the host's next send gets EPIPE. */
interface PipeSource {
  /** Up to `len` bytes into `buffer` at `offset`; resolves the count, -1 at EOF. */
  receive(buffer: Uint8Array, offset?: number, len?: number): MaybePromise<number>;
  readInt(): MaybePromise<number>;
  /** number: loses precision above 2^53. */
  readLong(): MaybePromise<number>;
  /** strict requires a '\n' (throws at EOF); else the final unterminated line, then null at EOF. */
  readUtf8Line(strict?: boolean): MaybePromise<string | null>;
  /** Streaming parse; stops at the value's end (trailing bytes stay queued); `{…}` root required. */
  readJsonObject(): MaybePromise<Record<string, unknown>>;
  /** As readJsonObject, `[…]` root required. */
  readJsonArray(): MaybePromise<unknown[]>;
  /** The rest of the stream to EOF, decoded as UTF-8. */
  readUtf8(): MaybePromise<string>;
  /** The rest of the stream to EOF, raw bytes. */
  readBytes(): MaybePromise<Uint8Array>;
  close(): void;
}

/** The writable end (buffered). close() = END; close(reason) = RESET (aborts a parked send). */
interface PipeSink {
  send(buffer: Uint8Array, offset?: number, len?: number): MaybePromise;
  writeBytes(b: Uint8Array): MaybePromise;
  putByte(b: number): MaybePromise;
  writeInt(v: number): MaybePromise;
  writeLong(v: number | bigint): MaybePromise;
  writeUtf8(...parts: string[]): MaybePromise;
  writeUtf8Range(str: string, from: number, to: number): MaybePromise;
  writeUtf8Line(...parts: string[]): MaybePromise;
  /** Base64url (no padding) of [b], written in 4-char groups into the staging segment. */
  writeBytesAsBase64url(b: Uint8Array): MaybePromise;
  /** Matches JSON.stringify (a cycle throws); streams via the native encoder. */
  writeObjectAsJson(value: unknown): MaybePromise;
  flush(): MaybePromise;
  close(reason?: string): MaybePromise;
}

/** The pair of ends this side holds, like pipe(2) fds. */
interface Pipe {
  readonly id: number;
  readonly command: string;
  readonly source: PipeSource;
  readonly sink: PipeSink;
}

declare var ymix: {
  /** Open a pipe to the host; fails AT THE OPEN (ECONNREFUSED-style) when nothing serves it. */
  open(command: string): Pipe;
  /** Handle pipes the host opens to us; returns a removable registration. */
  onAccept(command: string, handler: (pipe: Pipe) => void | Promise<void>): { remove(): void };
  /** Host [DynamicEngine.fire] / `"event"` pipe. Multiple listeners per name. */
  onEvent(name: string, handler: (name: string) => void): { remove(): void };
  utf8Encode(...parts: string[]): Uint8Array;
  utf8Decode(bytes: Uint8Array, from?: number, to?: number): string;
};

/** Diagnostics to the host logger over the __consoleLog native (console.ts implements) —
 *  developer breadcrumbs, never the error channel, never shown to the user. */
declare var console: {
  log(...args: unknown[]): void;
  info(...args: unknown[]): void;
  warn(...args: unknown[]): void;
  error(...args: unknown[]): void;
};

/** Installed frozen from C before this bundle runs. The whole host (transport, protocols, app)
 *  ships as one app build, so the app version identifies its full surface: branch on it
 *  (`hostVersionCode() >= 123` / `typeof __x === "function"`) to adapt to the installed host — never
 *  `versions.app` directly, see protocol/host.ts. */
declare var versions: {
  readonly app: number;
  /** Device facts from QuickJsRuntime.hostVersions; absent on hosts that predate them. */
  readonly os?: string;
  readonly osVersion?: string;
  readonly brand?: string;
  readonly model?: string;
  /** The display in physical pixels, at its orientation when the engine was created. */
  readonly screenWidth?: number;
  readonly screenHeight?: number;
  /** Physical pixels per CSS pixel (Android density, desktop scale). */
  readonly screenDensity?: number;
};

// ---- compile-time define, inlined by esbuild --define (value in build.gradle.kts) ----

/** The bundle's own revision — bumped manually on every remote push (build.gradle.kts).
 *  (The host app's version needs no twin: read `hostVersionCode()`.) */
declare const BACKEND_VERSION: number;

/** Which bundle this is — "engine" (scrape, also the test bundle) or "player" (MSE player). */
declare const BUNDLE_NAME: string;

/** Same bake as Kotlin BuildFlags.PRODUCTION (`-Pproduction`, default false).
 *  `debug(function () { return concat; })` does not call the thunk when true. */
declare const PRODUCTION: boolean;

declare function atob(data: string): string;
declare function btoa(data: string): string;

interface NativeTextEncoder {
  readonly encoding: string;
  encode(input?: string): Uint8Array;
  encodeInto(input: string, dest: Uint8Array): { read: number; written: number };
}
interface NativeTextDecoder {
  readonly encoding: string;
  decode(input?: ArrayBuffer | ArrayBufferView): string;
}
declare var TextEncoder: { new (): NativeTextEncoder };
declare var TextDecoder: { new (label?: string): NativeTextDecoder };

// ---- host-scheduled timers (transport.ts over the "setTimeout"/"setInterval" pipe commands) ----
// The handle is a number, same as a browser. The pipe stays inside the transport.

declare var setTimeout: (
  cb: (...args: unknown[]) => void,
  ms?: number,
  ...args: unknown[]
) => number;
declare var setInterval: (
  cb: (...args: unknown[]) => void,
  ms?: number,
  ...args: unknown[]
) => number;
declare var clearTimeout: (handle: number | undefined) => void;
declare var clearInterval: (handle: number | undefined) => void;

// ---- the error contract (protocol/command.ts; Kotlin mirror: WireError.kt) ----

/** The wire error object a classified failure crosses the boundary as. */
interface WireError {
  code: string;
  message: string;
  name?: string;
  userMessage?: string;
  stack?: string;
  cause?: { name?: string; message?: string };
}

interface JsEngineErrorOptions {
  userMessage?: string;
  cause?: unknown;
}

interface JsEngineError extends Error {
  /** A category ("parse", "network", …) or a site code ("E2xxxx"). */
  code: string;
  userMessage?: string;
}

/** A call handler: reads its own head off `src` (format not assumed), writes the result to `out`. */
type CommandHandlerFn = (src: PipeSource, out: PipeSink, pipe: Pipe) => void | Promise<void>;

// ---- HTTP (protocol/http.ts; host side: HttpProtocol.kt) ----

type HeaderEntry = [string, string];

interface HttpBodyStream {
  /** Up to `len` bytes into `buffer` at `offset`; resolves the count, -1 at EOF. */
  read(buffer: Uint8Array, offset: number, len: number): Promise<number>;
}

interface HttpResponse {
  readonly status: number;
  readonly statusText: string;
  /** Final URL, after redirects. */
  readonly url: string;
  readonly ok: boolean;
  readonly headers: {
    entries: HeaderEntry[];
    get(name: string): string | null;
    has(name: string): boolean;
  };
  /** Host disk-cache provenance: "hit" | "conditional"; undefined = network. */
  readonly cache?: string | undefined;
  text(): Promise<string>;
  bytes(): Promise<Uint8Array>;
  jsonObject(): Promise<Record<string, unknown>>;
  jsonArray(): Promise<unknown[]>;
  stream(): HttpBodyStream;
  cancel(): void;
  close(): void;
}

interface HttpCall {
  execute(): Promise<HttpResponse>;
  cancel(): void;
}

/** The callback form streams to the body pipe's sink; its return value is awaited (so a returned
 *  MaybePromise backpressure promise is honored). */
type HttpBody = string | Uint8Array | ((io: PipeSink) => unknown);

interface HttpRequestBuilder {
  setHeader(name: string, value: string): this;
  addHeader(name: string, value: string): this;
  setHeaderSanitized(name: string, value: string): this;
  addHeaderSanitized(name: string, value: string): this;
  headers(h: HeaderEntry[] | Record<string, string>): this;
  body(value: HttpBody): this;
  json(value: unknown): this;
  timeout(ms: number): this;
  followRedirects(follow: boolean): this;
  logger(l: import("./protocol/logger").JsLogger): this;
  build(): HttpCall;
}

interface HttpRequestApi {
  get(url: string): HttpRequestBuilder;
  post(url: string): HttpRequestBuilder;
  put(url: string): HttpRequestBuilder;
  delete(url: string): HttpRequestBuilder;
  patch(url: string): HttpRequestBuilder;
  head(url: string): HttpRequestBuilder;
  method(m: string, url: string): HttpRequestBuilder;
  addInterceptor(fn: import("./protocol/http/intercept").HttpInterceptor): { remove(): void };
}

// WHATWG fetch (logic/yt/fetch.ts) — quoted names at runtime for eval'd base.js.
// In-bundle callers must use bracket access so Closure does not rename them apart.
interface FetchHeaders {
  get(name: string): string | null;
  has(name: string): boolean;
  set(name: string, value: string): void;
  append(name: string, value: string): void;
  delete(name: string): void;
  forEach(cb: (value: string, name: string) => void): void;
}

interface FetchAbortSignal {
  aborted: boolean;
  onabort: (() => void) | null;
  addEventListener(type: string, fn: () => void): void;
  removeEventListener(type: string, fn: () => void): void;
}

interface FetchAbortController {
  signal: FetchAbortSignal;
  abort(): void;
}

interface FetchReader {
  read(): Promise<{ value?: Uint8Array; done: boolean }>;
  cancel(): Promise<unknown>;
  releaseLock(): void;
}

interface FetchReadableStream {
  readonly locked: boolean;
  getReader(): FetchReader;
  cancel(): Promise<unknown>;
}

interface FetchResponse {
  readonly status: number;
  readonly ok: boolean;
  readonly statusText: string;
  readonly url: string;
  readonly headers: FetchHeaders;
  readonly body: FetchReadableStream;
  readonly bodyUsed: boolean;
  readonly redirected: boolean;
  readonly type: string;
  text(): Promise<string>;
  json(): Promise<unknown>;
  arrayBuffer(): Promise<ArrayBuffer>;
  clone(): FetchResponse;
}

interface FetchRequest {
  readonly url: string;
  readonly method: string;
  readonly headers: FetchHeaders;
  readonly body: string | Uint8Array | undefined;
  readonly signal: FetchAbortSignal | null;
}

interface FetchInit {
  method?: string;
  headers?: unknown;
  body?: unknown;
  signal?: FetchAbortSignal | null;
  redirect?: string;
}

declare var Headers: { new (init?: unknown): FetchHeaders };
declare var Request: { new (input: unknown, init?: FetchInit): FetchRequest };
declare var Response: { new (body?: unknown, init?: FetchInit): FetchResponse };
declare var ReadableStream: { new (underlying?: unknown): FetchReadableStream };
declare var ReadableStreamDefaultReader: { new (): FetchReader };
declare var AbortSignal: { new (): FetchAbortSignal };
declare var AbortController: { new (): FetchAbortController };
declare function fetch(input: unknown, init?: FetchInit): Promise<FetchResponse>;

interface XMLHttpRequest {
  readyState: number;
  status: number;
  statusText: string;
  response: unknown;
  responseText: string;
  responseType: string;
  timeout: number;
  withCredentials: boolean;
  onreadystatechange: (() => void) | null;
  onload: (() => void) | null;
  onerror: (() => void) | null;
  ontimeout: (() => void) | null;
  onabort: (() => void) | null;
  open(method: string, url: string): void;
  send(body?: unknown): void;
  setRequestHeader(name: string, value: string): void;
  getResponseHeader(name: string): string | null;
  getAllResponseHeaders(): string;
  abort(): void;
  addEventListener(type: string, fn: () => void): void;
  removeEventListener(type: string, fn: () => void): void;
}

interface XMLHttpRequestConstructor {
  new (): XMLHttpRequest;
  prototype: XMLHttpRequest;
  UNSENT: number;
  OPENED: number;
  HEADERS_RECEIVED: number;
  LOADING: number;
  DONE: number;
}

declare var XMLHttpRequest: XMLHttpRequestConstructor;

// Fake page DOM (env.raw.js). In-bundle callers use dotted names; the matching Closure
// externs keep getElementById / getElementsByTagName / getElementsByClassName /
// createElement / appendChild from being mangled.
interface Element {
  id: string;
  className: string;
  tagName: string;
  clientWidth: number;
  clientHeight: number;
  offsetWidth: number;
  offsetHeight: number;
  parentNode: Element | null;
  appendChild(child: Element): Element;
}

interface Location {
  href: string;
  protocol: string;
  host: string;
  hostname: string;
  port: string;
  pathname: string;
  search: string;
  hash: string;
  origin: string;
  toString(): string;
  valueOf(): string;
}

interface HTMLCollection {
  readonly length: number;
  [index: number]: Element;
}

interface Document {
  URL: string;
  documentURI: string;
  domain: string;
  location: Location;
  body: Element;
  head: Element;
  documentElement: Element;
  getElementById(id: string): Element | null;
  getElementsByTagName(name: string): HTMLCollection;
  getElementsByClassName(name: string): HTMLCollection;
  createElement(tagName: string): Element;
}

declare var document: Document;
declare var location: Location;
declare var origin: string;
