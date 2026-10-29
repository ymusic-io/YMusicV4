# `:desktopApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Run** — the JVM desktop product shell: `./gradlew :desktopApp:run`.

- **Boot sequence** — boots:
  - `DynamicEngineManager` (QuickJS + engine-js)
  - a file-backed `:media-store` + `:media-scanner` "Scan folder…" (packed ffmpeg, or `-Dio.ymusic.ffmpeg` / PATH)
  - assembles the facade over the libav local player + MSE YouTube, both presenting on the one video surface below.

- **Video** — one video `Canvas` for both engines: `MseVideoPanel` in the `VideoUnderlay` frame behind the transparent Compose window, moved under the video box (`setHole`). It is attached once (`wireVideoOnce`) and never re-parented — a new Canvas peer is a new HWND / NSView, hence a new swapchain or overlay. The process-wide `DesktopVideoSurface` (`:mse`) owns its presenter; YouTube reaches it through `MseVideoSurface(videoSurface)`, local files through `LocalPlayerContext(videoSurface)`, and the local player claims it from load to stop. `mode` drives the panel: GPU = the presenter paints the Canvas, CPU = the panel paints the `onCpuFrame` images. A window resize covers the Canvas with a `snapshot()` still until it ends. Presenter and decode detail: [`mse/README.md`](../mse/README.md).

- **Settings, cookies, keys** — one SQLite `app.db` (`AppStore`): settings rows, the AES-GCM sealed `PersistentCookieJar` document (`CookieVault`, OS-user or password key) and the Windows DPAPI / Linux fallback key; the macOS key stays in the Keychain. Pre-4.0.7 loose files are imported once and removed. No Chromium. Sign-in is owned here (`DesktopCookieSignIn` via `AppGraph.cookieSignIn`): scan a QR from the phone app (Settings → Link devices; matching confirmation code + phone unlock), or paste a Copy as cURL dump from Chrome / Firefox / Safari Network. The encrypted handoff goes through the YMusic service's one-shot `/v1/link` slot, deleted as the desktop reads it.

- **Assets** — needs the built `engine-js` bundle in the jar.

- **macOS icon** — `ymusic.icns` is the `CFBundleIconFile`; macOS 26+ draws `CFBundleIconName` from `macos/Assets.car` instead, compiled from the Icon Composer source `macos/YMusic.icon` by `macos/build-icon.sh` (Xcode 26). The build copies the committed `.car` into the bundle after jpackage and re-signs it. Edit the `.icon`, rerun the script, and commit both.
