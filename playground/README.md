# `:playground`

Compose Multiplatform **MSE YouTube** lab: engine-js `DynamicEnginePlayer` + C leftover/ffmpeg.
One screen (`YtMsePlayerScreen`) on JVM, iOS, and Android. Video paints in a native hole
(SwingPanel / UIKitView / TextureView); Compose chrome does not recompose each frame.

## Run

- **Desktop JVM** — `./gradlew :playground:run`

BotGuard miller steps log at INFO (`bg interp` / `bg snap start` / `bg snap`
/ `bg generateIT` / `bg mint` / `bg sps=N`). Remill data is a per-session
`cache/put` file (scope `yt.bg.session`): JSONL `prefix <json>\\n`, fire-and-forget.
Remote feature `enable_bg_dump`, read at each minter build, gates the file and E20088:
`false` = off, a number N = only player.js revisions ≥ N, absent/`true` = on.
SPS=1 stops entropy and keeps the file (keepCount 4). SPS=2 (attestation pending)
waits for the next verdict. SPS=3 appends the SABR request, closes, `log/capture` E20088. Then:

```
python3 harness/po_replay.py mill DIR
python3 harness/po_replay.py sps DIR
```
- **iOS** — `MainViewController()` in `iosMain` (framework `MsePlayground`); host app is local, not in git
- **Android** — `./gradlew :playground-android:installDebug` (needs an emulator/device and
  `ffmpeg-build/out/android-<abi>`)

## Layout

- `commonMain` — `YtMsePlayerScreen`, `MseNativeVideo` expect, session expect
- `jvmMain` — SwingPanel blit (`contains() = false` so clicks stay in Compose)
- `iosMain` — UIKitView + `AVSampleBufferDisplayLayer` (`isInteractive = false`)
- `androidMain` — TextureView + ANativeWindow (`setVideoSurface`)
- `:playground-android` — thin AGP application shell (AGP 9 cannot combine `application` with KMP)
