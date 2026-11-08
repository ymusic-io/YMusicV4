# `:dynamic-engine`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Role** — the engine host: wraps `:quickjs` (`DynamicEngine`) and serves host protocols to the
  remotely-updatable scraping JS (YouTube feed-shape changes ship without an app release).

- **Boot** — `DynamicEngine.create(client, appVersion, logger, engineJs = ENGINE_JS)` boots scrape `engine.js`
  (handlers wired BEFORE the eval via `QuickJsRuntime.create`'s configure hook, `versions` installed from C
  at create) and then health-checks it over the bundle-served `"init"` pipe (returns `backendVersion`, the bundle's
  self-declared revision; typed rejections `E20020` load / `E20021` health check).
  `DynamicEnginePlayer.create(..., engineJs = PLAYER_JS)` boots MSE `player.js` on a second QuickJS runtime.

- **Remote update / lifecycle** — `engineJs` is the remote-update seam; `DynamicEngineManager` owns the current
  engine:
  - `start(remoteJs?)` falls back to the baked-in baseline on a rejected remote bundle.
  - `swap(newJs)` flips new work onto the new engine first, then retires the old one (`"shutdown"` pipe = SIGINT:
    the bundle's `lifecycle.ts` clears timers + runs `onShutdown` callbacks; bounded `awaitIdle` drain; force-close
    at the deadline).

- **HTTP** (implemented) — a global `HttpRequest` builder over the `"http"` pipe command, served directly on a
  `kmphttp.KmpHttpClient` (streaming both ways; Android resolves `:kmpHttp:core`'s jvm variant, i.e. real OkHttp).
  WHATWG `fetch` (and `Headers`/`Request`/`Response`/`ReadableStream`/`AbortController`) is a JS wrapper of that
  builder so eval'd `base.js` can call it.

- **Error contract** (implemented) — JS `commandHandler(handler)` (handler gets `(src, out)`)/`JsEngineError` ↔ a
  per-command Kotlin method that `decodeEngineError`s the RESET into a typed `EngineError` (implements
  `projectcommon.error.CodedError`) over structured RESET reasons (the typed `WireError` shape) — codes cross all tiers
  unchanged, classified at the origin (HOST_PROTOCOLS "Errors across the boundary").

- **Cookies** (implemented) — the bundle's `Cookies.get(url): Record<string,string>` / `.set(url, setCookie)` (one
  `Set-Cookie` string or an array) over the one-shot `"cookie/get"` / `"cookie/set"` commands.
  - Served on an **app-provided** `cookieJar` (the platform store the WebView player shares — Android
    `CookieManager`, desktop `CefCookieManager`; values parsed via `kmphttp.parseCookie`).
  - The engine's own HTTP client carries **NO jar** — the JS owns cookie POLICY: `logic/yt/cookies.ts` cooks every
    YouTube request's `Cookie` header from this store (CONSENT/SOCS synthesis — the legacy
    `YTHttpClientCookieStoreAndHeadersManager` role) and writes `Set-Cookie`s back, so cookie behavior ships in the
    remotely-updatable bundle and scraping + playback present one identity.
  - `NO_COOKIES` = capability inert; `E20040` bad head/url / `E20041` jar threw.

- **Cache** (implemented) — a **scoped** streaming keyed blob cache (every op takes a scope first — independent
  partitions, e.g. a 10-item home-feed scope + a 100MB download scope; `Cache.setJson`/`getJson`/`setText`/
  `getText`/`delete`/`list` + streaming `put`/`get` callbacks) over one-shot `"cache/get|put|delete|list"`, values
  streamed to/from disk, each entry host-stamped `size`/`updatedAt`/`lastUsed` (lastUsed bumped on get → LRU) + a
  caller `meta` string-map.
  - The store (`CacheStore`, shipped as `DiskCacheStore` — **metadata index in SQLite** (SqlDelight, so
    `list`/`init` is one indexed SELECT not a per-file dir scan) with **blobs on the okio `FileSystem`**,
    sha256(scope,key) filenames, atomic writes; the app passes a platform `SqlDriver` + blob dir) is **unbounded**.
  - **Eviction lives in the JS** — `scopedCache(scope, evictor)` drives a stateful `CacheEvictor` (`init(entries)`
    seeds from the scope, `onAdded(meta)` after every write→trim, `onRead(meta)` after every get refreshes
    lastUsed, `onDeleted(key)`), built-ins `keepCount(n)`/`lruCount(n)`/`lruSize(bytes)` or hand-written, so the
    policy is remotely updatable.
  - Wired via `DynamicEngine.create(cacheStore = …)` (absent → no cache; `E20050` bad head / `E20051` store threw).
  - Its home is the InnerTube session params (client version / `sts` / `visitorData` / bootstrap feed).

- **Player** — `DynamicEnginePlayer` is a sibling of `DynamicEngine`: same HTTP/cookies/cache/settings
  plus native `:mse` (or pipe MSE in tests). App commands are
  `player/init|load|play|pause|seek|stop|setSpeed|status|mintPo`. Host opens `listen/player/status` and
  `listen/player/catalog` to subscribe (JS pushes; host close unsubscribes), same as
  `player/caption`. The status `gain` group is base.js's loudness normalization: the value it writes to
  the media element's `volume`, which the host multiplies into the app volume. Native MSE events: C/JNI `MseJsEventBridge.fire` → `DynamicEngine.fire` →
  `"event"` pipe → JS `ymix.onEvent`. Pass the scraping [DynamicEngineManager] as
  `main` so player JS `open("host/main/"+command)` is a one-way host proxy (homepage
  `get_player_config` for minter EVENT_ID + `ytAtN` program). Content PO is homepage minter
  (GenerateIT + OWL `wpc.m`) **before** `base.js`; KEVLAR_WATCH `Application.create` then
  `loadVideoById`. Minter internals stay on `HttpRequest`; eval'd `base.js` SABR uses `fetch`.
  `orig/env.raw.js` is the Chrome 151 snapshot surface (filled PDF `navigator.plugins`, not empty
  `{length:5}`). Do **not** carry discover `CONFIG_BBD` traps. Playground **MSE** tab:
  `./gradlew :playground:run`.

- **Planned** — file / fs / sqlite; `base.js` bootstrap inside `DynamicEnginePlayer`.

- **Targets** — pure commonMain; targets = `:quickjs`'s (jvm/android/ios/macos/linux).

- **Protocol spec** — [`quickjs/HOST_PROTOCOLS.md`](../quickjs/HOST_PROTOCOLS.md).
