# AGENTS.md

Guidance for AI coding agents in `desktopRust/`, the Rust workspace inside YMusicV4.

## Project

The YMusic desktop app rewritten in Rust (core + gpui-kit UI), replacing the Kotlin/Compose desktop
app in YMusicV4. It builds C sources and the `engine-js` bundle straight from the YMusicV4 checkout it
sits in (`YMUSIC_V4_DIR`, default `..`) — never copy them here; a C fix lands in YMusicV4's module.

## Crates

One sentence each; detail lives in the crate's own README.

- **`ym-quickjs`** — QuickJS binding: the YMusicV4 C (vendored engine + pipe transport) built with `cc`, the async `Pipe` layer on a dedicated engine thread, host timers.
- **`ym-common`** — shared primitives: the `Logger` crumb trail (+ `TeeSink`, `DeferredSink`), the app's reqwest clients (no cookie store; following / non-following / raw for the HTTP cache), the JVM app's SQLDelight-style database open (`sqlite`: `user_version` + migrations) and its exact `file://` URI encoding (`file_uri`).
- **`ym-logstore`** — the app log (`logs.db`, the JVM schema): non-blocking enqueue, one writer, trim to a cap, claim lines for an error event, gzip export for feedback.
- **`ym-telemetry`** — Sentry, as the JVM app reports: warnings/errors as breadcrumbs, `capture_error` as the job's one event (trail + error code tags, grouped by the code; network category never sent), panics by the SDK, each event's newest app-log lines as Sentry Logs on its launch's trace (`LogShipper`: pending in `logs.db` until delivered, retried, a dead run's left for the next start), the engine/player bundle and remote-config tags on every event, the crash-reports switch applied live.
- **`ym-engine`** — the engine host: boots `engine.js` (`DynamicEngine`), wire errors, logger grafting, host protocols (http + `HttpCache` (OkHttp's on-disk cache format, shared with the JVM app), cookie, cache + `DiskCacheStore`, settings, remote-config, log/capture), `EngineManager` (boot the best bundle, swap a newer one without restart, retire with shutdown + drain), the typed `yt/*` / `ytmusic/*` command calls (`ym_engine::yt`), and the player bundle's `player/*` commands + `listen/player/*` streams (`ym_engine::player`; MSE attach, `host/main/*` proxy and the `MediaProxy` hand-off for tagged media requests, ahead of the HTTP cache, via `EngineConfig`).
- **`ym-store`** — the local library: `:media-store`'s schema (version 6) on SQLite and `scan_folder`, which reconciles a directory tree into buckets and items (in-process probe, unchanged files skipped, vanished ones soft-deleted, excluded folders skipped, per-file errors in the report), folder remove / exclude, playlists keyed by a content code (`MediaItem::playlist_code`), `scan_file` (one file into a known folder bucket: a finished download) and `changes()`, a tick after every write that open Library views reload on.
- **`ym-mse`** — YMusicV4's `:mse` C host (MSE over ffmpeg, audio DAC, video decode/presenters) built with `cc` against `$FFMPEG_BUILD_DIR/out/<slug>` (the ffmpeg-build checkout, default `../../ffmpeg-build`, beside YMusicV4; macOS, Linux: PulseAudio output via `aq_pulse.c`, Windows: D3D11VA decode and waveOut output via `aq_winmm.c`); owns one host, the presenters (`Presenter`: the VideoToolbox layer on macOS, a D3D11 composition swap chain on Windows, pictures as decoded on Linux (`Presenter::frames`: Vulkan images of a decoder on gpui's device, handed over by `decode_on_vulkan`, or CPU planes), CPU BGRA pictures to a callback), the local file player (`LocalAv` over `file_av.c`), the scan probe (`probe`, `native/ym_probe.c` on libavformat), and the process-wide C→JS event and log hooks; `native/vt_overlay_stub.c` fills the JVM-only JAWT overlay symbols.
- **`ym-ytmedia`** — googlevideo SABR/UMP: schemaless protobuf, UMP framing streamed off the body, the SABR messages, `SabrSession` (appends each format's contiguous stream to its file until `clen`) and the SQLite segment index that resumes a session from the file length; the player media cache (`cache`: the JVM app's `player-cache` folder of immutable span files indexed in `cache-index.db`, LRU-capped, structure-checked, SABR hits planned from it) and `proxy::LocalMediaProxy`, which answers the player engine's `X-Ym-Af`-tagged SABR POSTs from it and mirrors misses into it.
- **`ym-download`** — the offline download manager: SQLite queue (`downloads.db`), `DownloadService` (slots, pause/cancel, startup recovery, broadcast changes), options probe, format selection, captions, cover art, segments reused read-only from the player cache, ffmpeg outputs (MP3 + USLT lyrics, M4A, Opus + picture/lyrics comments, MP4/WebM mux) over the packed ffmpeg; the app supplies a `SpecResolver` over the player engine.
- **`ym-history`** — `play-history.db` (the JVM schema v5): watch-time rankings for local files (by playlist code) and YouTube videos, per-video display snapshots, and Play again sessions (whole queues with their play-head).
- **`ym-remote-config`** — the config service (`/v1/config`: features, published bundles, app update; cached, throttled, an outage changes nothing), anonymous usage stats (`StatUsage`: `/v1/pulse`, the JVM app's `stat-queue.json`), ECDSA P-256 bundle signatures, and `BundleSource` (a verified cached remote bundle above the embedded one).
- **`ym-update`** — the desktop self-updater over the JVM app's feed: the config's ECDSA-signed `appUpdate` entry (`app-<platform>:<versionCode>:<sha256>`), a resumable verified installer download, and the JVM app's installers (macOS DMG → same-team check → `renamex_np` swap → `open -n`, Windows Inno Setup after exit, Linux .deb via pkexec apt-get; a copy outside `/opt`, an AppImage included, cannot update itself and links the download page, as the JVM app's).
- **`ym-signin`** — signing in without a browser: the phone app's device-link handoff (QR `ymusic-link:2:`, HMAC keystream + MAC, confirmation code, relay polling), cookie pastes (cURL, Cookie header, cookies.txt, DevTools table, JSON), and the sealed cookie vault (the JVM `YMC1U`/`YMC1P` AES-GCM format; the JVM app's OS key in release builds: Keychain, Secret Service, DPAPI).
- **`ym-settings`** — the `SettingsStore` trait shared by UI and engine (text values, typed reads), an in-memory store, and `AppDb`: `app.db` with the JVM app's `setting` / `secret` tables.
- **`ym-i18n`** — UI strings generated at build time from YMusicV4's compose-resources (`Str` / `Plural` keys, `t` / `tf` / `plural`, CLDR plurals, English fallback). Never hardcode UI text; add strings to every locale in YMusicV4.
- **`vendor/`** — gpui crates as published, then patched (`[patch.crates-io]`, outside the workspace): gpui-pre (video surfaces off macOS, `aria_live`: live regions, `LayoutDirection`: right-to-left layout) and gpui-pre-wgpu (Linux video decoded on gpui's Vulkan device, its pictures drawn in the scene), gpui-pre-windows (the DirectComposition underlay).
- **`ymusic`** — the executable: boot (tokio + engine), gpui image client on reqwest, the launch (`launch.rs`: the unlock screen first when the saved sign-in is password-sealed, `cookie_session.rs` / `cookie_lock.rs`; a guest launch has no sign-in, account or YouTube play history), the shell (section sidebar, per-section back/forward stacks, search with suggestions) and feed surfaces. Playback over two engines (`Playback` + `QueueItem`: YouTube via the player engine + MSE host booted on first play, local files via `local_engine.rs` on `file_av`; radio / section / local queues), the Library (`library.rs`: scanned folder tree, files, Scan folder via the native picker, song / folder menus, remembered sorts; Library playlists and their dialogs in `library_playlist.rs`, their backup / restore in `playlist_transfer.rs`) the mini player, Now Playing (video in an `AVSampleBufferDisplayLayer` over the window via `video_layer.rs`, on Windows a D3D11 composition swap chain under gpui's visual via `win_video_layer.rs` and the vendored gpui's `underlay`, on Linux a surface in gpui's own scene, `video_surface.rs`, decoded on gpui's Vulkan device without a copy, audio-only switch, Up next queue), and the OS media session (`media_session.rs`: Now Playing info, media keys and the playback rate the OS extrapolates at; SMTC on its own thread and hidden window on Windows, `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` on macOS, MPRIS over zbus on Linux), the Windows taskbar Jump List (`jump_list.rs`); the YouTube player's media cache (`player_cache.rs`: opened at boot, capped by `playerCache.maxMb`, handed to the player engine as its media proxy, Settings row). Debug builds take `YMUSIC_DEV_ROUTE` (`trending`, `music`, `search:<q>`, `play:<url>`, `nowplaying:<url>`, `download:<FORMAT>:<videoId>` with `YMUSIC_DEV_DOWNLOAD_DIR` (added to the Library as a picked download folder is; `YMUSIC_DEV_DOWNLOAD_LIBRARY_TAB=<n>` shows that Library tab meanwhile), `playagain`, `playagain:resume`, `downloads`, `downloaddialog:<videoId>`, `settings`, `settings:<accent|captions|region|licenses|bottom>`, `settings:playercache=<mb>` (sets the player cache cap), `shortcuts`, `signin`, `protect` (the password step after a sign-in), `signin:paste`, `checkupdate`, `installupdate` (install the offered update once it is downloaded), `speedpitch`, `equalizer`, `sleeptimer`, `feedback`, `librarytab:<n>`, `libraryact:<a;b>` (`LibraryView::dev_action`), `discovertab:<n>`, `searchinput`, `drawer`) to open a surface at start, floating, for screenshots, `YMUSIC_DEV_MUTE` to start muted, `YMUSIC_DEV_NOWPLAYING=captions|fullscreen[:key,key*n]` to open the caption picker or full screen once playing and press those keys, `YMUSIC_DEV_PLAYER` (`speed=,pitch=,preset=,loop=,shuffle=,sleep=,reload=<s>,seek=<after s>@<to s>,speedpitch=<s>,equalizer=<s>`) to set player state at start, `YMUSIC_DEV_WINDOW=WxH` for the window size, `YMUSIC_DEV_QUIT_AFTER=<s>` to quit normally (quit hooks run), `YMUSIC_DATA_DIR` for a throwaway data dir, and `YMUSIC_DEV_REMOTE_CONFIG_URL` to point the config service (and the update feed) at a test server; a self-update relaunch passes these on. The YouTube player session (`youtube_session.rs`) is shared by playback and downloads (`downloads.rs`). The data dir is the JVM app's: release builds use `~/.ymusic` in place, debug builds `~/Library/Application Support/YMusicRust` (or `YMUSIC_DATA_DIR`), with the JVM file names, schemas, `user_version`s, settings keys, cookie jar document and `window.properties` (`data_files.rs` renames what earlier builds named differently).

## Build inputs

`YMUSIC_REMOTE_CONFIG_URL`, `YMUSIC_BUNDLE_PUBLIC_KEY`, `YMUSIC_UPDATE_PUBLIC_KEY`, `YMUSIC_API_KEY` and `YMUSIC_SENTRY_DSN` are read at compile time (private release inputs; unset = embedded bundles and defaults only, no QR sign-in, no self-update). They come from YMusicV4's gitignored `secrets.properties`, then `local.properties` (which wins), as Gradle's `localProp` reads them (keys `remote.config.url`, `bundle.signing.public.key`, `update.public.key`, `api.key`, `sentry.dsn`); an env var of the same name wins over both. With `YMUSIC_REQUIRE_SECRETS=1` (YMusicV4's release workflow sets it) the build fails when any of them but `api.key` is empty. The embedded bundle revisions come from YMusicV4's `Versions.engineJsVersion` / `playerJsVersion`. The app's version continues the JVM desktop line: versionName and versionCode come from YMusicV4's `ymusic.versionName` and `ymusic.versionCode` (gradle.properties), overridden by `YMUSIC_VERSION_NAME` / `YMUSIC_VERSION_CODE`; the update feed compares the versionCode, so a release must report one above the last published build. The packaging scripts build their release profile with `--features production` (the JVM app's `-Pproduction`), which compiles `Logger::debug` lines out. The Sentry release is `ymusic-desktop@<versionName>+<commit>` (the JVM app's name; the commit is the checkout's `git rev-parse --short=10 HEAD`), and `YMUSIC_SENTRY_ENVIRONMENT` (the JVM app's `-PsentryEnvironment`, which YMusicV4's release workflow sets: `beta` for a prerelease tag) names a release build's environment, else `production`.

Linux builds link `ffmpeg-build/out/linux-<arch>` and need the development packages `libxkbcommon-dev libxkbcommon-x11-dev libwayland-dev libxcb1-dev libfreetype-dev libfontconfig-dev libpulse-dev libvulkan-dev`. `tools/package-linux.sh --deb --appimage` stages and packs them (`patchelf`, `dpkg-deb`, appimagetool with its type2 runtime); YMusicV4's `.github/workflows/release.yml` runs it in an ubuntu:22.04 container, so the binaries need glibc 2.35. That workflow builds every desktop package (macOS DMGs, Windows installers, Linux .deb + AppImage, arm64 and x86_64) and decrypts `secrets.enc` into `secrets.properties` when the repo has `SECRETS_PASSPHRASE`.

Windows builds use the `x86_64-pc-windows-gnu` Rust host with MSYS2 MinGW gcc on `PATH` (the C is GCC/Clang-only, as for the JVM app's natives), arm64 the `aarch64-pc-windows-gnullvm` host with CLANGARM64's clang, link `ffmpeg-build/out/windows-<arch>` and copy its packed DLLs beside the executable. Release builds compile gpui's shaders with the Windows SDK's `fxc.exe`; `tools/package-windows.sh --setup` stages `YMusic.exe` + DLLs + `ffmpeg.exe` and needs Inno Setup.

## Rules

### Style
- `cargo fmt` and `cargo clippy --workspace -- -D warnings` must pass.
- Descriptive names (`engine_player`, `error`), never single letters.
- **Comments**: default to none. Only a genuine hack or a non-obvious constraint/invariant earns one,
  in one line. The story of a change goes in the commit message, never the code ("used to",
  "instead of", "previously" are banned). Doc comments state contracts, not the name restated.

### Right to left
Arabic, Persian, Hebrew and Urdu mirror the whole UI, as the JVM app does: `cx.set_layout_direction` at the root, so gpui reads every `left`/`right` style (inset, margin, padding, border, corners, `text_left`) as start/end and runs rows and grids right to left. Write styles for left to right and let them mirror.
- What follows time or a physical axis stays left to right: transport rows, seek bars and their times, sliders (`FocusList::slider` pins it), the equalizer bank, pictures of a real LTR UI. Pin a subtree with `.layout_direction(LayoutDirection::Ltr)`.
- A glyph that points along the reading direction (back arrow, disclosure chevron) goes through `action_icon::auto_mirrored`.
- Coordinates a canvas paints or a pointer reports are physical; a mirrored horizontal scroll ranges over `ScrollHandle::offset_range_x()`, not `-max..0`.

### Accessibility (from day one)
Every UI change ships accessible; it is not a later pass.
- **Every control has a spoken name.** An icon-only `Button` needs `.accessibility_label(..)`: its tooltip is only the description, so without a label VoiceOver reads nothing. Sliders and switches take `.accessibility_label(..)` too. Names come from YMusicV4's `cd_*` / `a11y_*` strings, never hardcoded.
- **Clickable rows and cards are controls.** A clickable `div` gets `.role(Role::Button)` (or `a11y::list_item(.., position)` inside a list), one `.aria_label(..)` that says what it is (title · artist · duration · kind, the `a11y_kind_*` strings), and is keyboard reachable. Its children are not read one by one.
- **Focus goes through `a11y.rs`.** gpui's `.tab_stop(true)` alone does NOT make a div focusable (only `tab_index` / `track_focus` / `focusable` do), so it is never reached by Tab and never announced. Every list item, card and tab is `FocusList::item(index, ..)`; a `Button` that arrows should reach (chip, pill, tab-like toggle) is `FocusList::button(..)` (or `wrap` after `track_focus(&list.handle(..))` when it is a dropdown). Arrow keys then move geometrically to the nearest stop on screen (Compose / FocusFinder), across lists; `container(..)` adds Home/End. A gpui-kit `Button` writes its own Tab flag into the handle it tracks, so it goes through `FocusList` rather than `track_focus` alone. A single div control is `.keyboard_focusable(theme.ring)`. A focused element is announced only with an element id and a role.
- **Lists are named sets with one Tab stop.** A list of items (a feed section, the Library's songs, Up next, the download manager) is `FocusList::list_stops(range, ..)`, called before its items render, then `.wrap(container, name)`: a `Role::List` named by its heading, with its size, each item `a11y::list_item(.., position)` so the screen reader says "3 of 20". Tab enters and leaves it in one press, on the item focused last (the playing one first in Up next); the arrows move within; a row's own buttons (its ⋮) are `FocusList::trailing_button`, in the Tab order right after their row while it holds the stop.
- **Headings name the page and its sections.** `a11y::heading(id, level, text)`: 1 the page title in the bar, 2 a section (and a dialog's title), 3 a group inside a dialog. A string child alone leaves a heading nameless (gpui gives text no node), so the name is set too.
- **Text is read, not tabbed.** Tab and the arrows reach only what acts. Text a screen reader must reach is `a11y::label` (gpui gives a string child no node of its own; its reading keys find a label); a status, an empty page's message or a passing error is `a11y::announced` (a polite live region, spoken when it appears or changes), an error that stops the user's action `a11y::alert` (assertive). AccessKit announces nodes that appear in the same frame in no set order, so a screen that appears announces once: `a11y::announced_as` speaks its lines in order (an empty page's "title. message").
- **State is exposed, not just drawn.** Selected rail item / tab, toggled shuffle / repeat / sleep, checked menu items: set the component's selected/toggled/checked state, not only a color.
- **Images are decorative unless they carry information.** Thumbnails beside a title stay unlabeled; an image that is the only content (QR code, album art alone) gets a label (`cd_device_link_qr`).
- **Keyboard first.** Everything reachable by mouse is reachable by Tab / arrows / Enter / Space / Esc, in visual order; dialogs trap focus and close on Esc; focus returns to the opener.
- **No meaning by color alone** (errors also say so in text); no motion (the app runs with reduce-motion on) but reorder rows easing to their new slots (`reorder.rs`), dropped when the OS asks for reduced motion (`SystemMotion`).

### Errors
- Never swallow an error. Propagate it (`?`), return it, or collect it into a result. Only
  documented fire-and-forget cleanup may ignore one.
- What the user sees is a well-known category (network, disk full, …) rendered localized at the UI,
  or "Something went wrong (E12345)". Technical detail stays in logs.
- Error codes: `R` + 5 digits (e.g. R12345), a unique literal at exactly one throw site, minted in
  [`error-codes.txt`](error-codes.txt) — grep for a free number in the crate's range, add a `-` row
  in the same commit as the throw site; on release flip the shipped rows to `D` (burned forever).
  The `R` set is disjoint from YMusicV4's `E` codes. E codes minted by engine-js pass through this
  app unchanged — wrap them, never re-mint.
- Log at the birth site with the caller's forked logger trail; `capture_error` once, where a job is
  declared dead.

### Threads and async
- A QuickJS engine thread is a main thread: accept handlers must never block. SQLite, files,
  keychain and other blocking calls go through `tokio::task::spawn_blocking`.
- The gpui main thread is UI only.
- Don't fake async: a purely blocking function is a plain `fn`; `async` only with real suspension
  points (network, channels, pipes).
- Never hold a `RefCell` borrow across an FFI call that can run JS (it re-enters synchronously).

### I/O
- Stream by default (`Bytes` chunks, readers, pipes). A whole payload in memory only when an API
  forces it, with a comment naming that API.

### Engine bundle compatibility
- `engine.js` / `player.js` are replaced remotely and run on every shipped host. Host protocols
  here are wire-identical to `quickjs/HOST_PROTOCOLS.md` in YMusicV4; never rename or drop a wire
  field or command.

### Moving app data
- When an on-disk location moves (including from the JVM app's layout), clean up the old one.

### Tests
- Don't add tests unless asked. Throwaway verification lives outside the tree and is deleted.
- Mute audio in any playback test (`YMUSIC_DEV_MUTE=1`). Never mouse-control the running desktop app; screenshot it via `YMUSIC_DEV_ROUTE` and `screencapture -l <window id>` (Windows: `PrintWindow` on its HWND with `PW_RENDERFULLCONTENT`).

### Git
- Commit each finished change, one logical change per commit, path-scoped `git add <paths>`.
- Never push; the user pushes.
- Never commit secrets, cookies, captured `base.js`, session dumps or personal data.

### OS cookie key in development
- Debug builds keep the cookie key in `app.db` on every OS. On macOS every rebuild re-signs the binary, and a Keychain read from a re-signed or different binary stops on an access prompt. Never read the app's Keychain, Secret Service or DPAPI key from another binary; test against a throwaway service/account.
