# Host bridge protocols — HTTP / FS / SQL (design, for review)

Application-layer protocols the app implements **on top of** `:quickjs` pipes (see
[`PIPE_STREAMING.md`](PIPE_STREAMING.md)). Not part of the module — the module deliberately ships
only the pipe transport + native JSON. This doc pins down the byte protocol so the Kotlin server
side and the JS client side agree. The error contract (§0), HTTP (§1), Cookies (§2), Cache (§3), and host→JS `"event"` (§3.7) are
implemented in `:dynamic-engine`; the rest is design-only until built (each section marks its status).

Direction throughout: the QuickJS **engine (JS) is the client** — it wants a capability. **Kotlin is
the server** — it owns the network, the filesystem, and the database. JS `ymix.open(command)`,
Kotlin `onAccept(command) { pipe -> ... }`.

---

## 0. Shared envelope

The pipe's own buffered ends (`pipe.source`/`pipe.sink`) drive everything. There are two message
shapes; each command picks one:

- **One-shot** (HTTP, FS metadata) — one pipe = one request/response, each direction delimited by
  **EOF** (half-close). Used when the whole request precedes the whole response.
- **Session** (file I/O §4.1, SQLite §5) — one pipe = one open handle held across **many** ops until
  `close`. The pipe never half-closes between ops, so EOF can't delimit them; messages are
  **length-framed** instead: a head line + exactly `len` body bytes when the head declares one.

### One pipe = one operation (or one session)

Each pipe **is** the correlation handle — no request-ids in the protocol. Concurrent calls =
concurrent pipes; the mux keeps them independent. A one-shot pipe carries a single exchange; a
session pipe carries a whole handle's lifetime.

### One-shot framing (EOF-delimited)

The one-shot commands (HTTP, FS metadata) are strictly *client sends the whole request, then the
server sends the whole response* — never full-duplex on one pipe. That lets each direction be
delimited by its own EOF:

```
client:  write head line ─┬─ [write body bytes] ─── close()   // half-close = "request complete"
server:                   └───────── (reads) ──── write head line ─┬─ [write body bytes] ─── close()
client:                                        (reads response) ───┘
```

- **Head** = one **compact** JSON object. It is self-framing — the JS `readJsonObject` and the
  Kotlin `JsonReader` (`:kmpHttp:json`, over the pipe's async source) both stop exactly at the
  value's end, leaving what follows readable — so no terminator is needed; a trailing `\n` is
  optional where line-reading is more convenient. Ordered multimaps (HTTP headers) are arrays of
  `[name, value]` pairs.
- **Body** = raw bytes after the head line, until that direction's EOF (the peer's half-close).
  Body meaning is per-protocol: raw entity bytes (HTTP, file), or **JSONL** — one JSON value per
  line — for row/entry streams (SQL rows, directory listings). JSONL is what keeps a huge result set
  from ever being held whole: read a line, parse it (`__parseJson` on the line, or the streaming
  `readJsonObject`), emit it, drop it.
- If an operation has **no body** in a direction, that side writes only the head line then
  `close()`s; the peer reads the head then sees immediate EOF.

### Errors — two channels, deliberately

1. **Operation-level errors that still produced a well-formed answer** ride in the **response head**:
   HTTP `status`, or `{"ok":false,"code":"ENOENT","error":"..."}`. The client always gets structured
   info — a 404, a constraint violation, a missing file are *normal responses*, not exceptions.
2. **Transport / mid-stream failures** (DNS failure, socket reset, disk I/O error mid-body,
   cancellation) use a pipe **RESET** — `sink.close(reason)` with a non-null reason string.

✅ **RESET is first-class in the transport (§6 — landed).** `sink.close(reason)` crosses the boundary;
the peer's pending data is discarded and its parked/next read **throws** the reason (Kotlin:
`PipeResetException`; JS: the promise rejects) instead of faking a clean EOF. A handler that throws
RESETs its pipe with the failure message automatically. So a mid-stream failure simply throws on
the next read; the length declaration for raw bodies and the terminal sentinel line for JSONL
bodies (`{"_end":…}` / `{"_error":…}`) are belt-and-suspenders, not load-bearing.

### Errors across the boundary — the unified code contract  *(implemented in `:dynamic-engine`)*

Every failure resolves to a **code** — one error object, minted once at the origin, passed through
every tier (server ↔ JS ↔ Kotlin) unchanged. A tier that receives a coded error **wraps, never
re-mints** (cause chain; the deepest code wins), so a user report is answered by one global search.

```json
{ "code": "E90210",            // category ("network") or site code (E#####) — required
  "name": "JsEngineError",     // JS constructor name, when it originated in JS
  "message": "quota exceeded", // technical, for logs/grep — NEVER shown to the user
  "userMessage": "…",          // OPTIONAL: localized, display-ready (e.g. server-minted)
  "stack": "…",                // capped at 4 KB
  "cause": { "name": "…", "message": "…" } }
```

Codes come in two kinds (AGENTS.md "Error Handling"):
- **Categories** — the small shared vocabulary all tiers classify into, localized by the app:
  `network` (transport; retryable), `parse` (scraped shape not understood → ship new JS), `oom`
  (engine gave out). Open set: an unknown category (newer remote JS/server than this app build)
  degrades to the generic message + code, never a crash.
- **Site codes** — `E#####`, one hardcoded literal at one throw/classification site; grep is the
  registry. `E2xxxx` = `:dynamic-engine` incl. its remote JS, `E9xxxx` = server-minted. Reserved so
  far: `E20001` uncaught script error (commandHandler's classifier fallback), `E20002` no engine handler for
  the command, `E20003` unstructured pipe reset (decoder fallback), `E20010` / `E20011` / `E20154` — §1.

Carriers, per hop:
- **JS**: `throw JsEngineError.parse("…")` / `throw new JsEngineError("…", "E29999", {userMessage})`
  (message first, then code); http.js adopts the host's origin-classified code (`network`, …) from
  the structured RESET reason automatically.
  **`ymix.onAccept(command, commandHandler(handler))`** wraps a handler in the contract — the handler
  gets `(src, out[, pipe])` and reads its own head off `src` (args format not assumed) — and
  classifies ANY throw — explicit `.code` wins, then a site code embedded in the message,
  `InternalError` → `oom`, runtime `SyntaxError` → `parse`, else `E20001` — and RESETs the pipe with
  the object as JSON. EPIPE (the caller hung up) is the one silent path.
- **Pipe**: the structured RESET reason. Plain-text reasons (transport-generated, raw
  `ymix.onAccept` backstop) stay legal — the decoder salvages an embedded `E#####` or falls back
  to `E20003` with the text as message.
- **Kotlin**: a per-command job method opens the pipe, sends the JSON args head, reads the result,
  and on a `PipeResetException` calls `decodeEngineError(reason)` to rebuild the object as a sealed
  `EngineError` (`Network`/`Parse`/`Oom`/`Technical(E#####)`/`Other(unknown category)`), which
  implements `projectcommon.error.CodedError`; `Throwable.errorCode()` walks the cause chain (deepest
  wins).
- **Server**: responds `{"ok":false,"error":{…same object…}}`; the JS API wrapper rethrows it as an
  `JsEngineError` carrying the object as-is — a server-minted code + localized `userMessage` reach
  the UI with zero app or JS changes. (Third parties — YouTube — can't mint our codes: the JS that
  understands their failure is the minting/classifying site.)

Rendering, at the UI edge only, fixed precedence: known category → localized string; `userMessage`
present → show it verbatim + code; else → generic "Something went wrong (code)".

### Logging context — the caller's trail, threaded down  *(implemented in `:dynamic-engine`)*

The value channel above goes **up**; the logging channel goes **down** (AGENTS.md "Error
Handling"). A Kotlin task call (`jsonCommand`) **parks** its caller's `kmpcommon.log.Logger` for
exactly the command's lifetime and sends the key in the args head as `"logKey": <int>`; the bundle
mints a `JsLogger` from it (`engine-js/src/protocol/logger.ts`), forks its own hops
(`[tag, {props}|null, enteredAtMillis]` — the host stamps a hop without a time, from an older
bundle, at graft time; tags are STABLE names, `js:`-prefixed and slash-free, anything variable goes
in props), and attaches it to every host call as a head field. The trail always opens with the
bundle's own revision (`js:<BACKEND_VERSION>`, baked in at logger construction — the version is
the bundle's fact, not the host's), so every report names the bundle that produced it:

```
"log": {"key": 3, "trail": [["js:20", null, 1793000000000], ["js:yt-home", null, 1793000000000], ["js:browse", {"page": 2}, 1793000000412]]}
```

The host protocol resolves the key back to the parked logger (absent/unknown/late → the engine
root logger; JS-initiated work — session bootstrap, crons — is keyless by design), grafts the JS
trail on **by value**, appends its own hop, and tags every birth-site log with the result — one
job's story, stitched across the pipe.

### Cancellation

Client abandons an in-flight call → it RESETs its pipe (`sink.close(reason)`), the server's read
throws the reason, and it aborts the underlying call/query/read. (Supported since §6 landed;
pair it with `source.close()` to also EPIPE a server that is mid-write.)

### Engine lifecycle (`"init"` / `"shutdown"`, bundle-served) — *implemented*

The two commands the HOST opens on the bundle (`engine-js/src/lifecycle.ts`), the reverse of every
other section here:

- **`"init"`** — the post-boot health check. No head; the bundle writes
  `{"backendVersion": N}` (its self-declared revision) and ENDs. `DynamicEngine.create` drives it
  with a deadline right after the boot eval: a refused open / RESET / malformed head / timeout is a
  typed rejection (`E20021`; a RESET carrying the bundle's own code keeps it), and the round-trip
  proves open routing, handler dispatch, JSON encode, and END — not just that the eval ran.
- **`"shutdown"`** — SIGINT as a pipe, sent when `DynamicEngineManager` retires an engine after a
  swap. The bundle sets its `shuttingDown` flag, runs the `onShutdown(cb)` registrations, and
  ENDs. **Ownership rule**: whoever starts repeat work (a timer, a cron) clears it in its own
  `onShutdown` callback — nothing mass-clears on the owner's behalf; a timer nobody clears keeps
  its pipe open and rides into the force-close deadline. The ack is **observed, not replied**:
  finished work closes its pipes, and the host waits — bounded — on `QuickJsRuntime.awaitIdle()`
  (the pipe map emptying IS "quiesced"), then closes. A bundle without the module refuses the
  open; the host skips the signal and the deadline + `close()` backstop. Bundle-side rule:
  cleanup that matters goes in `onShutdown` — after the runtime closes there is no JS.

---

## 1. HTTP  (`command = "http"`)  — *implemented in `:dynamic-engine`*

One **control pipe** per exchange plus, when the request has a body, one **body pipe** — control
never carries bulk data, so the cancel signal can never queue behind body bytes. Heads are compact
**JSON** (self-framing: `readJsonObject` / `JsonReader` stop at the value's end); both bodies are
**EOF-delimited raw bytes** — no framing anywhere. Headers cross as an ordered
`[["name","value"], …]` multimap (a repeated `Set-Cookie` is representable), and a CR/LF inside a
value is just data — head injection is impossible by construction.

### Control pipe  (`"http"`)
```
JS → K:  {"method":"POST","url":"https://…","headers":[["k","v"],…],
          "opts":{"timeoutMs":5000,"followRedirects":false},"bodyCmd":"http/body/8",
          "log":{"key":3,"trail":[["js:yt-home"],["js:browse"]]}}
         …then NOTHING; END = done, RESET = cancel                   (write side stays open)
K → JS:  {"status":200,"message":"OK","url":"<final URL after redirects>","headers":[["k","v"],…],
          "cache":"hit"|"conditional"?}
         <raw response body bytes> … then close() (END)
```
- `"cache"` (diagnostic, optional): `"hit"` = served from the host's HTTP disk cache without the
  network, `"conditional"` = a revalidated cache entry; absent = a plain network response.
- The server's cancel **watcher arms right after the head** — the JS→K direction is otherwise
  empty, so a RESET of that direction is observable at **every** stage, including a
  transport parked in `execute` with the body never pulled. Clean EOF = the client closed after
  consuming the response; anything else on the control pipe is a protocol violation (exchange torn
  down, RESET with the reason).
- On the response body's EOF the client half-closes its cancel-channel write side so the pipe
  reaps.
- Request `"opts"` (all optional; server defaults otherwise): `timeoutMs` (deadline until
  the response head, and until the body is first consumed — an unused body is cancelled;
  a streaming read after the first consume is not cut off), `followRedirects`. Response
  `"url"` = the final URL after redirects (`Response.request.url`). When `timeoutMs` is
  omitted the bundle still aborts an unused body after 15s (the host client's default
  execute timeout). A `Response` collected without being consumed also cancels
  (FinalizationRegistry); fetch and XHR wrap `HttpRequest`, so they inherit both.
  A fire-and-forget `fetch(url)` (promise never awaited) still runs until headers;
  the unused-body timer / GC then abort. The fetch Promise is not itself registered
  — after `await fetch()`, the Promise can be collected while the Response is still
  being read.

### Body pipe  (`command = head.bodyCmd`, only when the request has a body)
- The client registers a **one-shot accept** for a unique command (`"http/body/" + controlPipeId`)
  before sending the head; the server opens it **lazily, on the transport's first body pull** — a
  response that never needs the body (cache hit, early error) never transfers it. Raw bytes,
  END = body complete; a JS-side body failure RESETs the body pipe and the exchange fails with
  that reason.
- The registration is retired on every terminal path (response consumed, cancel, failure) so a
  never-opened body command cannot leak; a server opening it after retirement gets a refused open,
  which lands in an already-dead exchange.

```js
const res = await HttpRequest.get("https://example.com/api").setHeader("User-Agent", "…").build().execute();
if (!res.ok) { /* res.status / res.headers.get(name) — body still readable */ }
const data = await res.json();        // whole body; or res.stream().read(buf, off, len) to stream
```
(`HttpRequest`/`Response` — the ergonomic wrapper over this wire shape — is installed by
`:dynamic-engine`'s `HTTP_JS`; §7 lists the surface.)

### Errors
Expected failures RESET the control pipe with a **structured, origin-classified reason**
(the WireError shape, `{code, message}` — the server decides the code where the real exception is
understood; http.js adopts it, never guesses from text). The stage tells the client what it can
still trust:
- HTTP-level (any status, redirects, 4xx/5xx) → normal response, code in the status line; `res.ok`
  is `status ∈ [200,300)`.
- Head the server can't parse, or a URL/header the host client rejects → `{code:"E20011",
  message:"invalid request: …"}` (a caller bug — site-coded; nothing reached the HTTP client).
- Connection failure before the response (DNS/connect/TLS/timeout — the routine failure) →
  `{code:"network", message:"request failed: …"}`; the client's head read throws (§0).
- Failure mid-body → `{code:"network", message:"response body failed: …"}`; the client's in-flight
  read throws the reason (§6). Never a clean EOF — that would read as a complete, silently
  truncated body.
- The exchange cancelled by anything but the client's RESET → `{code:"E20154", message:"exchange
  aborted: …"}` — likewise never a clean EOF.
- Protocol violations (any data after the head → `E20010`) and server bugs throw
  out of the handler; the runtime's backstop RESETs with the raw message.
- Client abandons the body → `res.close()` / `res.cancel()` = RESET of the control pipe's request direction + closing our read end: the
  watcher aborts the exchange promptly (even mid-transport), and any in-flight server write fails
  EPIPE. The same abort runs if the `Response` is garbage-collected without being consumed, or if
  `timeoutMs` elapses before the first body read (15s when omitted).
- `HttpCall.cancel()` before a `Response` exists RESETs the same direction (legal mid-write).
  Either way the body registration/pipe is retired.

### Host client
The server side runs directly on a `kmphttp.KmpHttpClient` (`installHttp(client)`): request built
with `RequestBuilder`, response streamed via `ResponseBody.asyncSource()`, opts mapped onto
`execute(request, RedirectOptions, timeout)`. On Android the jvm variant of `:kmpHttp:core`
resolves, which is real OkHttp (the typealias layer).

---

## 2. Cookies  (`command = "cookie/get"` / `"cookie/set"`)  — *implemented in `:dynamic-engine`*

The engine (JS) reads and writes cookies against an **app-provided** `kmphttp.CookieJar`
(`installCookies(cookieJar)`) — the platform cookie store the WebView player shares (Android
`CookieManager`, desktop `CefCookieManager`). The engine's own HTTP client carries **no jar**: the
JS owns cookie *policy* — it reads this store, cooks each request's `Cookie` header itself
(consent/SOCS synthesis included — engine-js `logic/yt/cookies.ts`), and writes response
`Set-Cookie`s back — so cookie behavior ships in the remotely-updatable bundle, and scraping +
playback present one identity. The host owns cookie *parsing*: OkHttp's domain/path/expiry grammar
(`kmphttp.parseCookie`) applies on the way in. `CookieJar.NO_COOKIES` drops writes and reads back
empty. Two **one-shot** commands (§0 EOF-delimited), one pipe per call — the pipe is the correlation
handle, so concurrent reads/writes are just concurrent pipes (no request-ids, no session state; the
jar is long-lived, the pipes are not).

JS client: `Cookies.get(url): Promise<Record<string,string>>` / `Cookies.set(url, setCookie)`
(`setCookie` = one `Set-Cookie` string or an array of them — a response's whole set), in engine-js
`protocol/cookie.ts`.

### `cookie/get`
```
JS→K  head: {"url":"https://…","log":{…}?}   then half-close (no body)
K→JS  {"<name>":"<value>", …}                    then EOF          // a bare name→value object
```
`cookieJar.loadForRequest(url)` → the name→value map. An empty jar for that URL is `{}`.

### `cookie/set`
```
JS→K  head: {"url":"https://…","setCookie":["SID=…; Path=/; …", …],"log":{…}?}   then half-close
K→JS  (no body — clean END)
```
Each value is `parseCookie(url, …)`d (unparseable ones dropped, OkHttp-style) and handed to
`cookieJar.saveFromResponse(url, cookies)`; the host ENDs, JS reads to EOF to observe completion.

### Errors
Structured RESET (§0 boundary contract), classified at the host where the failure is understood:
- `E20040` — the head has a missing/blank/invalid `url`, no `setCookie` (for set), or is malformed
  JSON. A caller bug.
- `E20041` — the jar itself threw (a persistent jar's I/O failure). A `CodedError` from the jar keeps
  its own code (wrap, never re-mint); otherwise `E20041`.

The bundle's `callHost`/`hostError` adopt the code onto the thrown error, so JS branches on `.code`.

---

## 3. Cache  (`command = "cache/get"` / `"cache/put"` / `"cache/delete"` / `"cache/list"`)  — *implemented in `:dynamic-engine`*

A **scoped** keyed blob cache for the scraping engine. Every op names a **scope** — an independent
partition with its own keys, listing, and eviction — so unrelated caches coexist (e.g. "keep the last
10 home-feed items" alongside "a 100 MB download cache"). Keyed, **not** a filesystem: no paths, no
seek/append. **Values stream** both ways (head then body on the one pipe, §0 EOF-delimited), so a big
JSON blob never lands whole in Kotlin. Each entry carries host-stamped metadata — `size` (bytes),
`updatedAt` (last write), `lastUsed` (last access — **bumped on `get`**, so an LRU evictor sees it) —
plus a caller `meta` string→string map, stored verbatim.

**Eviction lives entirely in the JS**, not the host. The store (`CacheStore`, shipped as
`DiskCacheStore` — the **metadata index in SQLite** (SqlDelight) so `list`/`init` is one indexed
`SELECT` instead of a per-file dir scan, while **value blobs stay on the filesystem**, `sha256(scope,key)`
filenames, atomic writes; a 100 MB download never streams through the DB) is **unbounded**; the bundle's `Cache` client
trims via `list` + `delete`. `scopedCache(scope, evictor)` drives a **stateful `CacheEvictor`** —
`init(entries)` seeds it from the scope (persisted entries survive restarts), `onAdded(meta)` after
every write returns what to evict, `onRead(meta)` after every get refreshes `lastUsed` (no eviction — a
read adds nothing), `onDeleted(key)` on an external delete — so it never re-lists the store. Built-ins
`keepCount(n)` (by `updatedAt`), `lruCount(n)` / `lruSize(bytes)` (by `lastUsed`), or hand-write a
`CacheEvictor`. So the whole policy is remotely updatable with the rest of the engine JS. The app wires
the store with `DynamicEngine.create(cacheStore = …)`; absent = no cache capability (the `ymix.open` is
refused).

JS client (engine-js `protocol/cache.ts`): every method takes the scope first —
`Cache.setJson(scope, key, value, meta?)` / `getJson(scope, key)` / `setText` / `getText` /
`delete(scope, key)` / `list(scope)`, plus the streaming primitives `Cache.put(scope, key, write, meta?)`
(a sink callback — `sink.writeObjectAsJson(obj)` streams the encode straight to the file) and
`Cache.get(scope, key, read)` (a source callback). `scopedCache` binds the scope so callers pass just
`key`.

```
cache/get     JS→K {"scope":…,"key":…}                        K→JS {"hit":true,"size":N,"updatedAt":T,"lastUsed":T,"key":…,"meta":{…}} + value bytes   (or {"hit":false})
cache/put     JS→K {"scope":…,"key":…,"meta":{…},"idleTimeoutMs"?:N} + bytes, half-close   K→JS {"size":N,"updatedAt":T,"lastUsed":T,"key":…,"meta":{…}}
cache/delete  JS→K {"scope":…,"key":…}                        K→JS (no body — END)
cache/list    JS→K {"scope":…}                                K→JS [{"size","updatedAt","lastUsed","key","meta"}, …]
```

### Errors
Structured RESET (§0 boundary contract), classified at the host:
- `E20050` — a missing/blank `scope`/`key`, or a malformed head. A caller bug.
- `E20051` — the `CacheStore` threw (disk I/O). A `CodedError` from the store keeps its own code.
- `E20052` — a `cache/put` body sent no bytes for `idleTimeoutMs` (default 30 s; `0` = no limit, for a
  deliberately long-open stream). The staged value is dropped.
- `E20053` — the put was cancelled by a newer `cache/put` or `cache/delete` of the same scope+key (the
  last to start wins). The staged value is dropped.

A put commits only on a clean half-close (END). A RESET from the writer, the engine closing, or any
error above drops the staged value — an incomplete body is never stored.

---

## 3.5 App settings (`command = "settings/get"` / `"settings/set"`) — *implemented in `:dynamic-engine`*

The app passes its existing typed `SettingsStore` when creating the engine. This is the same live,
persistent store used by native UI—not a JS-only copy. Both commands are one-shot.

```
settings/get  JS→K: {"key":"…","type":"string|boolean|int","default":…}
              K→JS: {"value":…} then EOF
settings/set  JS→K: {"key":"…","type":"string|boolean|int","value":…}
              K→JS: empty clean END
```

`Settings.get(key, defaultValue)` infers the wire type from the default; `Settings.set(key, value)`
infers it from the value. Numbers must be integers. Missing/invalid fields RESET with `E20060`; a
store failure RESETs with `E20061` (or preserves the store's own `CodedError`).

---

## 3.5.1 Remote config features (`command = "remote-config/get"`) — *implemented in `:dynamic-engine`*

Read-only and live: every call answers from the config the app holds now, so a value can change
between two reads. One-shot.

```
remote-config/get  JS→K: {"key":"…"}
                   K→JS: {"value":…} then EOF; {} when the key is absent
```

It serves any key of the config's `features`, whether or not the app build declares a property for
it. `RemoteConfig.get(key, defaultValue)` returns the default when the key is absent or null, or
the read fails, including on hosts older than this command, which refuse the open. A failed read RESETs
with `E20062`.

---

## 3.6 MSE player — native C host, not a pipe protocol

The bundle's JS `MediaSource` / `SourceBuffer` / `HTMLMediaElement` stubs (engine-js `protocol/mse.ts`)
call QuickJS `__mse*` trampolines, which hit `:mse` C (`mse_host.c`) through a vtable the host
passes in (`attachMseHost(host, vtable)`). JNI from the app is **only** `setVideoSurface`. Seek is
JS (`player/seek` → `HTMLMediaElement.currentTime` → `__mseSetCurrentTime`).

App control (`player/load|play|pause|seek|stop|setQuality|setSpeed|setSpeedPitch|setLoop|setViewport`)
talks to the stub media element. Speed/pitch: `player/setSpeedPitch` → YouTube
`setPlaybackRate` and `video.pitch` → `__mseSetSpeed` / `__mseSetPitch`. Volume and EQ stay
on `MseVideoSurface.setVolume` / `setAudioEffect`. Kotlin must not call `MseHostNative.setSpeed`.
`HTMLMediaElement.ended` is a native push: C `mse_host_ended` (endOfStream and the
audio leftover + DAC ring are empty; video leftover is ignored) → `MseJsEventBridge.fire("mse_ended")`
→ `"event"` pipe → `ymix.onEvent("mse_ended")`. `__mseEnded()` is the same predicate for the JS poll backup.
Display viewport defaults to 640×360; the host
sends `player/setViewport` `{width,height}` (also accepted on `player/init` / `player/load`) so SABR
ABR matches the real hole. `player/load` defaults quality `"zero"`: base.js
still builds a normal video SourceBuffer; MSE does not append video to C; dummy `buffered` leads
the playhead so the player is not waiting on video bytes the server will not send (`ClientAbrState`
field 40 = 1). Field 17 stays. `player/setQuality("zero")` drops decoded video and does not call
YouTube's quality API (that would no-op after 144p).

## 3.7 Host → JS events (`command = "event"`) — *implemented*

Opposite of the usual JS-opens direction (same as `listen/player/status`). C or Kotlin calls
`MseJsEventBridge.fire(name)` / `DynamicEngine.fire(name)` (any thread). The runtime hops onto the
engine dispatcher, opens `"event"`, writes the UTF-8 name, END. JS `ymix.onAccept("event")` reads
to EOF and dispatches `ymix.onEvent(name)`. Player create wires
`MseJsEventBridge.setJsEventBridge { engine.fire(it) }`. Native MSE uses the name `"mse_ended"`.

---

## 4. Filesystem — sandboxed, session-based

### Sandbox: one app-exposed root, relative plain paths

JS can touch files **only inside a single directory the app exposes** for it — e.g. Android
`context.filesDir/js_store`, desktop an app-data dir, iOS an app-support subdir. That root is chosen
by the host and is **never seen by JS**. Every path in this protocol is a **relative, `/`-separated
plain path** (`"cache/feed.json"`, `"a/b.txt"`) — no `file:` URIs, no SAF document URIs, no absolute
paths.

**The server enforces the jail (non-bypassable, this is the security boundary for remotely-updatable
JS):** it rejects absolute paths (leading `/` or `\`, drive/UNC), rejects any `..` segment,
normalizes, resolves against the root, and canonicalizes the result to verify it still lives under
the root (symlink defense). Any escape attempt → `{"ok":false,"code":"EINVAL"}` (never touch the
file). No `DocumentsProvider`/`DocumentInfo` here — this is a private scratch/cache area, backed by
plain okio `FileSystem` on desktop/Android and `NSFileManager`/okio on iOS.

Two access shapes:
- **File session** (`command = "file"`) — one pipe = one open file handle, held open across many
  read/write/seek ops until `close`. This is what you asked for; it's the primary path.
- **One-shot metadata ops** (`command = "fs"`) — list/stat/delete/mkdir/rename/exists, each a single
  request→response on its own pipe (the §0 EOF-delimited model).

### 4.1 File session (`command = "file"`)

Because the pipe stays open across ops, EOF can't delimit them — so a session uses **length-framed
messages**: every message is a **head line** (compact JSON + `\n`, read with `readUtf8Line`)
optionally followed by **exactly `len` body bytes** declared in that head. (This is the concrete
instance of §0's "stateful, length-framed" variant.)

**Open** — the first exchange, right after `ymix.open("file")`. **A handle is read-only *or*
write-only, never both** — `mode` picks one:
```
client → {"path":"cache/feed.json","mode":"r"}          // then reads the reply
server → {"ok":true,"size":12345}                        // or {"ok":false,"code":"ENOENT"} then close
```
`mode` ∈ `r` (read, must exist) · `w` (write; create + truncate) · `a` (append; create, position at
end). No combined read+write. Position starts at 0 (end for `a`).

**Ops** (each: request head [+ body] → response head [+ body]); the handle keeps a file position. An
op that doesn't match the open mode → `{"ok":false,"code":"EBADF"}`:

| op      | mode  | request                             | response                                                        |
|---------|-------|-------------------------------------|-----------------------------------------------------------------|
| `read`  | `r`   | `{"op":"read","len":65536}`         | `{"len":M}` + M bytes · `M=0` (no body) = EOF; advances pos by M |
| `seek`  | `r`   | `{"op":"seek","pos":P}`             | `{"ok":true}` · absolute byte offset (ranged reads)             |
| `size`  | `r`   | `{"op":"size"}`                     | `{"size":S}`                                                    |
| `write` | `w`/`a` | `{"op":"write","len":N}` + N bytes | `{"ok":true}` · appends at pos, advances pos by N               |
| `flush` | `w`/`a` | `{"op":"flush"}`                   | `{"ok":true}` · durability                                      |
| `close` | any   | `{"op":"close"}`                    | `{"ok":true}` · server flushes + closes the file, both close the pipe |

- Read handles are seekable (ranged reads); write handles are **sequential** (truncate-on-open for
  `w`, append for `a`) — no random writes, which keeps the write path a pure stream.
- **Streaming is the loop**: read a big file by looping `read` at e.g. 64 KB; write one by looping
  `write` chunks. Memory stays at one chunk on both sides — no whole-file buffer, ever.
- Dropping the pipe (half-close/RESET) without a `close` op still releases the handle server-side;
  the explicit `close` just acks and gives a flush error a place to surface.

### 4.2 One-shot metadata ops (`command = "fs"`)

Each is a single request→response, §0 model. `path` is a sandbox-relative plain path.

| op       | request head                             | response                                              |
|----------|------------------------------------------|-------------------------------------------------------|
| `list`   | `{"op":"list","path":"sub"}`             | `{"ok":true}` + JSONL entries + `{"_end":N}`          |
| `stat`   | `{"op":"stat","path":"a/b.txt"}`         | `{"ok":true,"stat":<entry>}`                          |
| `exists` | `{"op":"exists","path":"a/b.txt"}`       | `{"ok":true,"exists":true}`                           |
| `mkdir`  | `{"op":"mkdir","path":"a/b"}`            | `{"ok":true}` · parents created                       |
| `delete` | `{"op":"delete","path":"a","recursive":false}` | `{"ok":true}`                                   |
| `rename` | `{"op":"rename","from":"a","to":"b"}`    | `{"ok":true}`                                         |

- **entry** (plain, no URI/mime): `{"name":"feed.json","dir":false,"size":123,"mtime":1700000000000}`.
- `list` streams as JSONL (one entry per line) + a terminal `{"_end":N}` (or `{"_error":"…"}`), so a
  large directory never materializes as one array.

### Errors
Operation-level errors ride in the response head: `{"ok":false,"code":"…","error":"…"}`, `code` ∈ a
stable set (`ENOENT`, `EEXIST`, `EISDIR`, `ENOTDIR`, `EACCES`, `EINVAL` for a bad/escaping path,
`EIO`). In a session the op's `ok:false` reply lets the session continue; a fatal I/O failure RESETs
the pipe. Open failure replies `ok:false` then closes.

---

## 5. SQLite — the engine's own database, session-based  (`command = "sqlite"`)

The engine gets its **own** SQLite database — a private file in the sandbox area (§4), **not** the
app's media DB. So there is no `BetterMediaStore`, no typed RPC, and no injection/schema-tampering
concern about app data: the engine owns this DB outright and raw SQL against it is fine. (The app
still decides *whether* to expose it and can cap its size / put it on its own disk quota.)

Like a file, a DB is a **session**: `ymix.open("sqlite")` opens a connection, JS runs
query/exec/transaction ops on it, then `close`. One pipe = one open connection, **length-framed**
messages (head line + optional `len`-declared body), same framing as §4.1.

### 5.1 Open — with managed init/upgrade

The first exchange declares the schema the engine expects; the host migrates the file up to it
(SQLiteOpenHelper / SQLDelight-style), keyed by `PRAGMA user_version`:
```
client → {"op":"open","name":"engine","version":3,
          "migrations":["<v0→v1 SQL>","<v1→v2 SQL>","<v2→v3 SQL>"]}
server → {"ok":true,"version":3}          // or {"ok":false,"code":"...","error":"..."} then close
```
- `name` → a DB file in the sandbox (`{name}.db`); isolated per name.
- `migrations[k]` is the SQL (one or more statements) that upgrades **from version k to k+1**, so
  `migrations.length` must be ≥ `version`. The host reads `user_version`, runs
  `migrations[user_version .. version-1]` in **one transaction**, then sets `user_version = version`.
  A fresh DB (`user_version 0`) runs the whole chain; an up-to-date DB runs nothing.
- `user_version > version` (an older engine against a newer file) → `{"ok":false,"code":"EDOWNGRADE"}`.

### 5.2 Ops within the session

Each op is a request head [+ body] → response head [+ body]. `args` is a JSON array bound positionally
to `?` placeholders (parameterized — never string-interpolated).

| op         | request                                              | response                                             |
|------------|------------------------------------------------------|------------------------------------------------------|
| `query`    | `{"op":"query","sql":"SELECT …","args":[…]}`         | `{"ok":true,"columns":[…]}` + JSONL rows + `{"_end":N}` |
| `exec`     | `{"op":"exec","sql":"INSERT/UPDATE/DELETE …","args":[…]}` | `{"ok":true,"rowsAffected":N,"lastInsertRowId":M}` |
| `begin`    | `{"op":"begin"}`                                     | `{"ok":true}`                                        |
| `commit`   | `{"op":"commit"}`                                    | `{"ok":true}`                                        |
| `rollback` | `{"op":"rollback"}`                                  | `{"ok":true}`                                        |
| `close`    | `{"op":"close"}`                                     | `{"ok":true}` · closes the connection, both close the pipe |

- **`query` rows stream as JSONL** — one **positional** array per line (column names are in the head,
  so rows stay compact) terminated by `{"_end":N}` (or `{"_error":"…","code":"…"}` if the cursor
  fails partway). The client reads a line, parses it, emits it, drops it → memory = one row, not the
  whole result set. The `_end` sentinel delimits the body on the persistent pipe (no EOF between ops).
  ```
  {"ok":true,"columns":["id","title"]}
  [1,"Song A"]
  [2,"Song B"]
  {"_end":2}
  ```
- **Transactions are natural** here because the session is one connection: `begin` … `exec`/`query` …
  `commit`/`rollback`. (No separate batch op needed — the round-trips share the connection. Add a
  JSONL `batch` later only if round-trip latency ever matters.)

### 5.3 arg & cell types
JSON `null`→NULL, number→INTEGER/REAL, string→TEXT, bool→INTEGER 0/1. **BLOB** args and BLOB result
cells: a base64 string tagged `{"$blob":"<base64>"}`. Kept out of v1 unless a caller needs it.

### Errors
SQL errors (syntax, constraint) at/ before execution → `{"ok":false,"code":"SQLITE_CONSTRAINT",
"error":"…"}` in the response head; the session stays open. A cursor that fails mid-stream →
`{"_error":…}` terminal line. A fatal connection error RESETs the pipe. Open/migration failure
replies `ok:false` then closes (a failed migration rolls back — the file is left at its old version).

---

## 6. Transport: RESET reason — ✅ LANDED (part of the pipe-protocol rewrite)

The transport threads the reason through `closeWrite(id, reason)`; on the JS side `__jsClose(id)`
carries only the id — the reason crosses through the C pipe layer (see PIPE_STREAMING.md). A
non-null reason is a **RESET**: the peer's pending data is discarded and its parked/next `receive`
throws (Kotlin: `PipeResetException(reason)`; JS: the promise rejects with the reason). Both sides
expose `sink.close(reason: String? = null)`, and a handler that throws RESETs its pipe with the
failure message automatically. Buffered readers propagate naturally: a `read`/`readUtf8Line`/`readUtf8`/`readBytes`/
`readJsonObject` in flight throws the reason. Clean END (reason `null`) still returns `-1`/`null` —
no behavior change on the happy path. See `quickjs/PIPE_STREAMING.md` §1.

---

## 7. Intended JS surface (built on the above)

Thin ergonomic wrappers the engine code actually calls — the wire protocol stays invisible:

```js
// HTTP (one-shot) — implemented (:dynamic-engine HTTP_JS)
const call = HttpRequest.get(url).setHeader(n, v)   // .post/.put/…; body: string | Uint8Array | (io) => …
  .timeout(5000).followRedirects(false)             // per-request opts (server defaults otherwise)
  .build();                                         // -> HttpCall
const res = await call.execute();                   // resolves the Response (once)
call.cancel();                                      // aborts at ANY stage, even parked inside the client
res.status; res.ok; res.url; res.headers.get(n); await res.json(); await res.text();
await res.bytes();
res.stream().read(buf, off, len);
res.close();  // abort an unused body (cancel)

// App preferences — the same persistent typed store native UI uses
const region = await Settings.get("app.gl", "US");
await Settings.set("app.gl", "VN");

// FS — file session (paths are sandbox-relative; a handle is read-only OR write-only)
const rf = await host.file.open("cache/feed.json", "r");
let chunk; while ((chunk = await rf.read(65536))) { … }  // Uint8Array, null at EOF
await rf.close();
const wf = await host.file.open("cache/feed.json", "w");
await wf.write(bytes); await wf.close();
// FS — one-shot metadata
for await (const e of host.fs.list("sub")) { … }         // {name,dir,size,mtime}
await host.fs.stat(path); await host.fs.mkdir(path); await host.fs.delete(path);

// SQLite — DB session (engine's own DB)
const db = await host.sqlite.open("engine", 3, migrations);
for await (const row of db.query(sql, args)) { … }       // streams rows
const { rowsAffected, lastInsertRowId } = await db.exec(sql, args);
await db.begin(); …; await db.commit();
await db.close();
```

Each is a thin wrapper over `ymix.open` (its pipe ends already carry the buffered read/write
helpers). Kept out of `:quickjs` — they live in
whatever module wires the host handlers (the engine host, not the binding).

---

## Summary

| Protocol   | command  | shape                          | notes                                                    |
|------------|----------|--------------------------------|----------------------------------------------------------|
| HTTP       | `http`   | control pipe + lazy body pipe  | JSON heads; raw EOF-framed bodies; RESET on control = cancel at any stage |
| Cookies    | `cookie/get`, `cookie/set` | one-shot, EOF-framed | on `client.cookieJar`; get → name→value object, set → `Set-Cookie[]`, no body; `E20040`/`E20041` |
| Cache      | `cache/get\|put\|delete\|list` | one-shot, **streamed** values | **scoped** keyed blob store; host-stamped size/updatedAt/lastUsed + custom `meta`; JS owns eviction (scopedCache + evictors); `E20050`/`E20051` |
| Settings   | `settings/get`, `settings/set` | one-shot, EOF-framed | app's shared typed string/boolean/int store; `E20060`/`E20061` |
| Remote config | `remote-config/get` | one-shot, EOF-framed | live read-only `features` values; `E20062` |
| File I/O   | `file`   | **session**, length-framed     | open(path, r\|w\|a) — read-only *or* write-only → read/seek/size *or* write/flush → close; chunks stream |
| FS meta    | `fs`     | one-shot, EOF-framed           | list/stat/delete/mkdir/rename/exists; list streams JSONL |
| SQLite     | `sqlite` | **session**, length-framed     | open(name,version,migrations) → query/exec/begin/commit/close; rows stream JSONL |

Sandbox: `file`/`fs`/`sqlite` all operate under one app-exposed root, **relative plain paths**, no
escaping (server-enforced) — the security boundary for remotely-updatable JS.

Common: one pipe per op · compact-JSON head line + `readUtf8Line(strict)` · raw or JSONL body to EOF ·
op-errors in head, transport-errors as RESET · RESET throws on read (§6, landed).

Open decisions for review: **(1)** SQL raw-bridge (A) vs typed-RPC (B), and the sandbox/allowlist if
(A) · **(2)** is FS write in scope now, or defer until a bytes-capable provider exists?
