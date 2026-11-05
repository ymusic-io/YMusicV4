# `:desktopApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Run** — the JVM desktop product shell: `./gradlew :desktopApp:run`.

- **Boot sequence** — boots:
  - `DynamicEngineManager` (QuickJS + engine-js)
  - a file-backed `:media-store` + `:media-scanner` "Scan folder…" (packed ffmpeg, or `-Dio.ymusic.ffmpeg` / PATH)
  - assembles the facade over the libav local player + MSE YouTube, both presenting on the one video surface below.

- **Video** — one video `Canvas` for both engines: `MseVideoPanel` in the `VideoUnderlay` frame behind the transparent Compose window, moved under the video box (`setHole`). It is attached once (`wireVideoOnce`) and never re-parented — a new Canvas peer is a new HWND / NSView, hence a new swapchain or overlay. The process-wide `DesktopVideoSurface` (`:mse`) owns its presenter; YouTube reaches it through `MseVideoSurface(videoSurface)`, local files through `LocalPlayerContext(videoSurface)`, and the local player claims it from load to stop. `mode` drives the panel: GPU = the presenter paints the Canvas, CPU = the panel paints the `onCpuFrame` images. A window resize covers the Canvas with a `snapshot()` still until it ends. Presenter and decode detail: [`mse/README.md`](../mse/README.md).

- **Settings, cookies, keys** — one SQLite `app.db` (`AppStore`): settings rows, the AES-GCM sealed `PersistentCookieJar` document (`CookieVault`, OS-user or password key) and the Windows DPAPI / Linux fallback key; the macOS key stays in the Keychain. Pre-4.0.7 loose files are imported once and removed. No Chromium. Sign-in is owned here (`DesktopCookieSignIn` via `AppGraph.cookieSignIn`): scan a QR from the phone app (Settings → Link devices; matching confirmation code + phone unlock), or paste a Copy as cURL dump from Chrome / Firefox / Safari Network. The encrypted handoff goes through the YMusic service's one-shot `/v1/link` slot, deleted as the desktop reads it.

- **OS media surface** — `media/DesktopMediaSession` publishes playback state and takes transport commands: macOS through MPNowPlayingInfoCenter / MPRemoteCommandCenter (JNA over the ObjC runtime), Linux as an MPRIS player (`org.mpris.MediaPlayer2.ymusic`) over a minimal in-tree D-Bus client (`DBusConnection`: JDK Unix sockets, no dependency, nothing reflective for ProGuard), Windows through SystemMediaTransportControls (JNA over the WinRT ABI, `GetForWindow` on a hidden window owned by one MTA thread). That window carries the app ID `io.ymusic.windows`; the media flyout shows the app's name and icon only when a Start Menu shortcut carries the same `System.AppUserModel.ID` — otherwise it borrows another session's (Chrome, Media Player). Check Linux with `busctl --user introspect org.mpris.MediaPlayer2.ymusic /org/mpris/MediaPlayer2`; Windows by listing `GlobalSystemMediaTransportControlsSessionManager` sessions, or the media flyout (Win+A / volume keys).

- **Native libraries that won't load** — `:mse` (libav and the JNI over it) is all audio and video, local and YouTube; `:quickjs` runs the engine. `NativeLoadProblem` keeps either from killing startup, which matters on Windows, where the binaries are unsigned and an App Control policy or Smart App Control can refuse them. Without `:mse` the app still runs and only playback is off: `MseLibrary.loadFailure` is read once in `main`, `PlayerEngines.unavailable` stops any engine being built, and a banner sits over the app. In-app updates still work there, which is the way out once a fixed build ships. Without `:quickjs` there is no app, so boot ends on a full-screen message. A policy block is a known state: the app log on every launch and one telemetry event per app version (`AppSettings.nativeBlockReportedVersion`). Anything else is a defect, reported with its code every launch. The block is recognized by Windows' own text for the error, read through `FormatMessageW`, because the JVM passes that text through in the system's language.

- **Assets** — needs the built `engine-js` bundle in the jar.

- **macOS icon** — `ymusic.icns` is the `CFBundleIconFile`; macOS 26+ draws `CFBundleIconName` from `macos/Assets.car` instead, compiled from the Icon Composer source `macos/YMusic.icon` by `macos/build-icon.sh` (Xcode 26). The build copies the committed `.car` into the bundle after jpackage and re-signs it. Edit the `.icon`, rerun the script, and commit both.

## Open With

Media files list the app under the OS's Open With without taking any default: `CFBundleDocumentTypes` in the macOS plist, `OpenWithProgids` in `installer/windows/ymusic.iss`, `MimeType` and `%F` added to the `.desktop` entry when the deb is built. The file types follow the packed ffmpeg's demuxers, so update all three together. Opened files arrive as arguments (handed to the running app by `SingleInstance`) or, on macOS, through the open-file handler, and play as a temporary queue (`IncomingFiles`). The OS reads these from an installed app, so `:desktopApp:run` never shows up; use `createDistributable`.
