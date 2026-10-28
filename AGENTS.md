# AGENTS.md

This file provides guidance to AI coding agents working with code in this repository.

Machine-local dev notes are imported when present: @~/.agents/AGENTS.md

## Project Overview

YMusic V4 is a complete rewrite of `YMusicLegacy` (the previous YMusic Android app) on new tech stacks: Kotlin Multiplatform + Compose Multiplatform, targeting Android, desktop JVM, and iOS. The legacy sources are not published; if you have a copy it sits at `YMusicLegacy/` (gitignored) for reference.

## Modules

Each module has a one-line summary here; deeper detail lives in the module's own doc (linked). Read the detail doc before working inside that module.

**RULE — this section must not grow back.** This file loads into context on every prompt, module-relevant or not. When documenting module work, update the module's own `README.md` (create it if missing, link it here), never this list: a bullet stays ONE sentence — what the module is, where the detail lives. Anything longer belongs in the module doc.

- **`:kmpHttp`** — a port of the OkHttp API to Kotlin Multiplatform: OkHttp-shaped public API (`Request`/`Response`/`Headers`/…), coroutine-based instead of blocking, on JVM (typealias to real OkHttp), Apple (NSURLSession), and desktop-native (libcurl). Sub-modules: `:core`, `:curl`, `:json`, `:json-benchmark`. → [`kmpHttp/ARCHITECTURE.md`](kmpHttp/ARCHITECTURE.md)
- **`:kmpCommon`** — shared KMP primitives used across modules: `kmpcommon.uri.Uri` / `UriBuilder` (an RFC3986 URI type), and `kmpcommon.io.AsyncSource`/`AsyncSink` + `BufferedAsyncSource` (suspend-based okio-Buffer I/O, moved here from `:kmpHttp`; `:quickjs` adapts `Pipe` to them via `asAsyncSource()`/`asAsyncSink()`).
- **`:projectCommon`** — app-wide helpers grouped by package (`projectcommon.json` readers, `projectcommon.error` error codes, `projectcommon.log` telemetry plumbing); app logic lives here, never in the publishable `kmp*` libraries. → [`projectCommon/README.md`](projectCommon/README.md)
- **`:kmpFfmpeg`** — KMP wrapper over ffmpeg for metadata extraction / (future) transcoding. No-op on iOS (no ffmpeg there).
- **`:media-store`** — a media-metadata database (buckets / media items / playlists) on SQLite via SQLDelight; CRUD, queries, and reactive flows. Playlist membership is content-addressed (a `code` derived from `youtubeVid` or `fileSize`+duration), not row-FK'd. No scanning.
- **`:media-scanner`** — walks a document tree and reconciles it into `:media-store`, extracting metadata via `:kmpFfmpeg`. Storage-agnostic via a SAF-shaped `DocumentsProvider` (Android SAF, desktop okio filesystem, iOS no-op). Concurrent and cancelable.
- **`:engine-js`** — scrape `engine.js` (remotely replaceable) and MSE `player.js` (plus `engine-test.js` = both + fixtures): TypeScript → esbuild → Closure ADVANCED. → [`engine-js/README.md`](engine-js/README.md)
- **`:dynamic-engine`** — the engine host: `DynamicEngine` + `DynamicEngineManager` (remote-update) and `DynamicEnginePlayer` (JS MSE stubs → C `:mse`); host protocols http/cookies/cache/settings. → [`dynamic-engine/README.md`](dynamic-engine/README.md), protocol spec [`quickjs/HOST_PROTOCOLS.md`](quickjs/HOST_PROTOCOLS.md)
- **`:quickjs`** — our own QuickJS binding (vendored Bellard QuickJS, V8-shaped toString/stack/eval(TrustedScript)): a duplex flow-controlled pipe transport (JNI on jvm/android, cinterop on Apple/linux) under a multiplexed `Pipe` layer in commonMain (`QuickJsRuntime`), streaming native JSON, bytecode precompile; ships NO JS (the `:engine-js` bundle is evaled once at create). → [`quickjs/README.md`](quickjs/README.md)
- **`:player`** — the cross-platform playback layer: narrow `MediaEngine` SPI + `Player` facade, all tunables in one `PlaybackConfig` (audio-only = quality `"zero"`), one shared video container each engine owns its surface inside; backends AVFoundation / ExoPlayer / ffmpeg local (desktop) + MSE YouTube (Android/desktop/iOS). → [`player/README.md`](player/README.md)
- **`:mse`** — C leftover+ffmpeg MSE host for JS `__mse*` (`mse/native`); QuickJS attaches host+vtable. JNI is `setVideoSurface`. JVM blits bitmaps, Android Media3 AudioSink + MediaCodec into TextureView (libav blit if that fails), iOS polls `copyFrame`. → [`mse/README.md`](mse/README.md)
- **`:yt-media`** — pure-Kotlin googlevideo SABR/UMP protocol + the download logic and player cache built on it; knows nothing about WebView/JS/player — everything per video is fed in as plain data (`SabrStreamSpec`). → [`yt-media/README.md`](yt-media/README.md)
- **`:download`** — the offline Download Manager: task queue + store over `:media-store`, SABR downloads via `:yt-media`, ffmpeg mux via `:kmpFfmpeg` (audio-only stays raw m4a, no ffmpeg).
- **`:image-loader`** — our own simple Compose image loader: everything is a fetcher (network on DiskLruCache, file, custom schemes), view-size scaled decode, memory LRU + optional scaled disk cache, LIFO concurrency gate, `AsyncImage`. → [`image-loader/README.md`](image-loader/README.md)
- **`:commonUi`** — the shared Compose Multiplatform app UI (drawer nav, Discover feed/search, Library, mini-player + Now Playing, settings) over `AppGraph`; localized into every `values-*` locale via compose-resources — NEVER hardcode UI text; a new string goes into ALL locales. → [`commonUi/README.md`](commonUi/README.md)
- **`:desktopApp`** — the JVM desktop product shell (`./gradlew :desktopApp:run`): MSE YouTube + ffmpeg local engines, file-backed media store + scanner. → [`desktopApp/README.md`](desktopApp/README.md)
- **`:androidApp`** — the Android product shell (plain AGP 9 application module) booting the shared `App` over ExoPlayer + MSE YouTube (WebView is login + cookie store). → [`androidApp/README.md`](androidApp/README.md)
- **`iosApp`** *(not a Gradle module — the Xcode shell)* — the iOS product shell: SwiftUI `@main` hosting the same shared Compose `App` from the `:commonUi` framework, over AVFoundation local + MSE YouTube engines. → [`iosApp/README.md`](iosApp/README.md)
- **`:settings`** — typed key-value `SettingsStore` (StateFlow reads, per-key distinct), in-memory + JVM Properties-file impls, `AppSettings` descriptors (hl/gl/theme accent/uiLanguage — hl/gl is InnerTube content language/region, uiLanguage is the app chrome; kept independent). Android persistence = the shell's SharedPreferences-backed store (`androidApp/PrefsSettingsStore`).
- **`:playground`** — Compose MP MSE YouTube lab (`YtMsePlayerScreen` + native blit hole) on JVM, iOS, and Android (`:playground-android`). → [`playground/README.md`](playground/README.md)

## Coding Rules

### Why questions
When the user asks **why** / **what** / **how** / **wtf is** (explain a path, a log, a function), answer from the code. Do **not** patch, refactor, or "fix" it in the same turn unless they explicitly ask to change it.

### Code style
- **Kotlin**: official Kotlin style (4-space indent), enforced by ktlint via Spotless — run `./gradlew spotlessApply` after Kotlin changes; `spotlessCheck` must pass. Line length is not enforced; aim for ~120.
- **TS** (`engine-js/src/`): TypeScript (strict + `noUncheckedIndexedAccess`/`noPropertyAccessFromIndexSignature`/`exactOptionalPropertyTypes`), prettier-formatted (`.prettierrc`, printWidth 100, `quoteProps: preserve`; enforced by the pre-commit hook) — `npx prettier@3 --write <file>`. The bundle follows a **statically-analyzable discipline** (required by the Closure ADVANCED pass):
  - **Structure is dotted, data is bracketed/quoted.** Declared interfaces/classes: dotted access, literal property names only — never `obj[expr]` on a structured type; ADVANCED renames them all consistently. `Record`/map collections, parsed JSON, and every wire object: bracket access / quoted literal keys (Closure never renames quoted; dotted here = renamed = corrupted wire). The compiler enforces reads via `noPropertyAccessFromIndexSignature`; writes are on the author.
  - **Every boundary shape is declared exactly once** — wire JSON ↔ Kotlin (`wire.ts`, `WireError`), C natives (`natives.d.ts`), globals (`types.d.ts`). Nothing ad-hoc crosses a boundary.
  - **No reflection over structure**: no `Object.keys`/`for-in` driving logic on interface-typed objects; `JSON.parse`/`stringify` touch declared wire shapes only. No `eval`/`Function`, no prototype patching.
- **C** (`quickjs/native/*.c/.h`): 4-space indent, K&R braces; match the existing files. Never reformat `quickjs/native/quickjs/` (vendored) or `YMusicLegacy/`.

### Error Handling
- **Never catch and silently ignore exceptions.** A caught exception must be propagated somewhere the caller can see it — rethrown, returned, or surfaced in a result (e.g. collected into a `ScanError`/errors list), never reduced to a bare counter or swallowed. The only exception is deliberate, documented fire-and-forget cleanup (e.g. `closeQuietly()`).
- **Propagation exists so the user gets a meaningful message when a task fails.** Meaningful is exactly one of two shapes:
  - a **well-known category** the user can act on (Network error, Disk full, …) — classified where the failure is understood, rendered as a localized message at the UI;
  - an **error code** for everything technical: "Something went wrong (E12375)". Never show technical detail to the user ("HTTP protocol frame malformed" can't be localized and only confuses) — the detail stays in the exception message and logs; only the code goes on screen, for the user to report.
- **Error codes**: `E` + 5 digits, a unique hardcoded literal at exactly one throw/classification site — a user report is answered by a global search for the code. Mint via [`error-codes.txt`](error-codes.txt) — the ledger, one row per code `E##### <flag> <explanation>`: grep it for a free number, add a `-` row in the same commit as the throw site. The flag is `D` (deployed = shipped in a release → burned forever, never reuse/renumber/remove) or `-` (not deployed → free to remove, renumber, or reallocate). On release, flip every `-` row of the shipped module to `D`. Nothing is deployed yet, so all codes are currently `-` (free); a removed non-shipped throw site just deletes its row. Backstop for a forgotten flip: `git log --all -S "E12345"`. Ranges by module: E1xxxx `:quickjs`, E2xxxx `:dynamic-engine` (incl. its remote JS), E3xxxx `:kmpHttp`, E4xxxx `:kmpFfmpeg`, E5xxxx `:media-store` / `:media-scanner`, E6xxxx `:player`, E7xxxx `:download`, E8xxxx `:mse`.
- **Two channels, opposite directions.** Across a boundary (Kotlin ↔ JS ↔ server), an error has two independent flows: the **value goes up** (host → caller) — the `{code, userMessage?}` the *user* sees, minted once at the origin, wrapped-never-re-minted; the **logging context goes down** (caller → host) — a forked `kmpcommon.log.Logger` (a `Crumb` trail) the *developer* sees, consumed only when something is logged. They never mix: nothing logged is ever shown to the user, and the code never carries developer detail.
- **Log at the birth site, with context.** A failure is logged **where it is born**, in the frame that caught it, with full native fidelity (the real `Throwable`) — a stringified error round-tripped across a boundary and rebuilt has lost its stack. To make a birth-site log meaningful the caller's `Logger` is threaded *down* to it (the boundary severs the stack, so the host frame can't see the caller): each layer `fork`s its own hop onto the trail and hands the child down; across the JS wire the trail rides the head and the host protocol grafts it back with `forkAll` + its own hop (see `:dynamic-engine`'s `HttpProtocol` + `HttpRequest.logger` → the `log` head field). Crumb **tags are stable names** (grouping keys); anything variable — including the job's natural id (`task.id`, videoId) — goes in the fork's `prop` map, which is how concurrent jobs' logs stay separable. Birth-site logs are **breadcrumbs/warnings**, not crash records — a failure the caller recovers from must not count against crash-free rate; `Logger.captureError` is the one crash record of a failed job, called exactly once in the frame that declares the job dead. Full design of record: `docs/telemetry.md` (private docs).

### Backward compatibility (engine JS ↔ host)
`engine.js` / `player.js` are replaced remotely and run on **every shipped host** — an old app can't be fixed, only the bundle can. Any edit to `engine-js/`, a QuickJS/`:mse` native, a host protocol (http/cookie/cache/settings), or a Kotlin command/decoder in `:dynamic-engine` must keep the newest bundle working on every supported app version.
- Before finishing such an edit, walk the checklist in [`engine-js/README.md`](engine-js/README.md) § "Backward compatibility checklist" and state the compat verdict in the commit message (`compat: additive` / `compat: guarded by versions.app >= N` / `compat: BREAKING — <why it's acceptable>`).
- Never rename or remove a wire field, command, or native in one step; add the new one and keep the old one until every app version using it is below the support floor.
- **Bump the bundle revision with every fix or feature in it**, in the same commit, without being asked: `Versions.engineJsVersion` for engine.js, `Versions.playerJsVersion` for player.js (`buildSrc/.../Dependencies.kt`); shared code (`transport.ts`, `protocol/`) bumps both.

### Coroutines
- **Suspend functions must be safe to call from the main thread.** Use `withContext(Dispatchers.IO)` for I/O or `withContext(Dispatchers.Default)` for CPU-bound work.
- **Don't fake async.** A purely-blocking function (SQLite, files) whose body is `withContext(Dispatchers.X) { … }` with no real suspend point inside should NOT be `suspend` — that just advertises "safe from main" while hopping a dispatcher the caller could hop itself. Declare it a plain `fun` annotated `@WorkerThread` (in `kmpcommon`) and let the caller — already off the main thread — own threading. Reserve `suspend` for genuine suspend points (network, channels, async `Source` reads). A `suspend` fn may freely call these blocking ones. Reference: `MediaBlobCache` / `BlobIndex` / `SegmentIndex` in `:yt-media` are non-suspend `@WorkerThread`; their SABR-session / proxy callers are what suspend.
- **The QuickJS engine thread is a main thread — treat it exactly like the UI one.** Everything on a `DynamicEngine`/`QuickJsRuntime` dispatcher shares ONE thread with all JS execution, every pipe and every timer: host-protocol `onAccept` handlers run there, and `create`/`healthCheck` run on it by contract. Any I/O or potentially-blocking platform call inside that code (cookie store, SQLite, SharedPreferences, files) hops off with a plain `withContext(Dispatchers.IO) { … }` — one blocking call on the engine thread wedges every command silently (the "yt commands hang pre-HTTP" zombie). Pipe `send`/`receive` are fine from anywhere; they hop internally. This is a settled decision: handlers deliberately run ON the engine dispatcher (re-dispatching them to a pool was considered and rejected — it would introduce real handler concurrency and thread-safety risk); hop off per call instead.
- **Mutex is NOT reentrant** — nested acquisition deadlocks. Guard specific state only, keep critical sections small, no nested lock calls. For complex cases use `limitedParallelism` or an actor/channel pattern.
  ```kotlin
  class SafeState<T>(initial: T) {
      private var value = initial
      private val mutex = Mutex()
      fun get() = value  // non-blocking read
      suspend fun update(block: (T) -> T) = mutex.withLock { value = block(value) }
  }
  ```

### I/O
- **Choose carefully between ByteArray and okio Source/Sink — streams by default, ByteArray only when a platform API forces it.** A `ByteArray` in a signature means the whole payload sits in the heap at once; a `Source`/`Sink` bounds memory to a segment and composes (file, network, cache entry, adapter). Legitimate ByteArray sites carry a justification comment naming the forcing API (e.g. Skia's bytes-only codec). This applies to interfaces most of all — an interface typed `ByteArray` forces every implementation to buffer.

### Moving app data
When an on-disk location (app dir, cache dir, database file, prefs file) moves from A to B, don't forget to clean up A — like a database migration.

### Common mistakes
- Don't use `Char.isLetterOrDigit` to check for `[a-zA-Z0-9]` — it includes non-ASCII letters like `'β'.isLetterOrDigit() == true`.

### Comments
**Default to no comment.** The code shows *what* it does; a comment earns its place only when it states something the code *cannot* — a genuine **hack**, or a **non-obvious constraint/invariant** the next reader would otherwise miss. When unsure, delete it.
- **The story goes in the commit message, never the code.** Why the old code was wrong, what it used to do, what you tried instead — that is commit-message material, and pure noise the moment the PR merges (it explains a defect the next reader never saw, not the code as it stands). Banned in code: "used to…", "this would…", "instead of…", "previously…", before/after descriptions, or any reference to the change itself.
- **A comment that restates the name or signature is narration too** — `writeBytes` → "write bytes" adds nothing the reader can't see. Cut it.
- **Survivors are short** — one line stating the constraint, not a paragraph.
- **A refactor deletes narration, it doesn't carry it** — re-apply this rule to every line you move or touch; never preserve an old verbose style just because it was already there.

### Tests
Do **not** add tests to the project unless the user asked for them. A small throwaway test or script to verify the current job is allowed — keep it out of the tree (`/tmp`, untracked) and delete it when done. Do not land it in `src/*Test`, CI, or a commit.

### Git / commits
Commit every change before the session ends. One logical change per commit.
Never leave a working tree of mixed experiments uncommitted: later we cannot
tell what used to work, what broke it, or why (engine-js vs native MSE vs player vs UI).

- Message: what changed and why, not a diary of tool calls.
- Do not mix unrelated work (engine-js vs `:mse` host vs player vs UI) in one commit.
- Do not `git push` unless the user asks.
- Do not commit secrets or session dumps (`log*.txt`, cookies, captured `base.js`, mitm traffic).
- **This is a public repository — every commit is publicly visible.** Never commit personal data (recorded fixtures carrying a real account's history or identity — fixtures must hold mock data only), credentials or tokens, machine-local paths/infra notes, or private working notes. When pushing, push ONLY the current public branch — never `git push --all` / `--mirror`: other local branches may hold unpublished history.
- **Never push. The user pushes.** Agents commit locally only — no `git push`, no force-push, ever, even when the push seems like the natural completion of a requested merge or rewrite. State the push command(s) for the user to run instead.
- **Never make one big commit that bundles everything.** Split a batch into the smallest still-meaningful units and commit them separately, in dependency order — e.g. a toolchain/dependency bump (Kotlin/Gradle/AGP) is its own commit, separate from standalone refactors, feature work, and tests. Use path-scoped `git add <paths>` (or `git add -p` when one file's hunks belong to different units). Keep each commit building. When asked to "commit", default to this rather than a single sweep.

### Release flow
On the current working branch:
1. Bump `ymusic.versionName` (`gradle.properties`) and versionCode +1 in `androidApp/build.gradle.kts`, `desktopApp/build.gradle.kts` (`desktopVersionCode`), `iosApp/iosApp/Info.plist`.
2. In `CHANGELOG.md`, rename `## Unreleased` to the version.
3. In `error-codes.txt`, flip shipped `-` rows to `D`.
4. Tag the commit: `hotfix-v<version>` for a hotfix, `release-v<version>` for a release. The tag message is the update dialog's release notes, **per platform**: each platform's lines go under its `[android]` / `[macos]` / `[windows]` header (repeat a line under each header it applies to); a message without headers goes to every platform, and an unknown header fails the release job. Each platform's notes: at most 7 lines of ≤40 chars (the dialog cuts off the rest), no title (the dialog shows the version); when there is more, the last line says so and points to the full changelog:
   ```
   git tag -a release-v4.0.6 -m "[android]
   Fix: crash on picture-in-picture
   Fix: downloads with long titles
   And more, see the full changelog
   [macos]
   Fix: downloads with long titles
   And more, see the full changelog
   [windows]
   Fix: downloads with long titles
   And more, see the full changelog"
   ```
