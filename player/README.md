# `:player`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

The cross-platform playback layer.

- **MediaEngine SPI** — the common SPI is **`MediaEngine`** (a deliberately NARROW core both playback backends
  satisfy — status/progress + `init`/`reset` + `load` when `readyToLoad` / play/pause/seek/stop/`setConfig`/release).
  `PlayerImpl` waits for `readyToLoad` like UniPlayer (`init` cold, `reset` on `needReset`).

- **PlaybackConfig** — **all runtime-tunable settings travel through one object**, `PlaybackConfig`.
  - A faithful port of the legacy `BasicMediaPlayer.Config` (`preferVideoQuality: Pair<String,Long>` /
    `captionVisible` / `volume` / `speed` / `pitchFactor` / `eqBands` / `bassBoostDb` / `virtualizer` / `loop` / `preferCaptionLanguage: Pair<String,Long>` /
    `scaleMode`; the `.second` of each pair is a monotonic stamp so the freshest user choice wins a restore).
  - Pushed via `setConfig` (the same object `load` applies atomically pre-first-frame; each engine diffs old→new and
    applies only what changed, ignoring fields it can't honor).

- **Audio-only is not a boolean** — it is `preferVideoQuality.first == VideoQualityState.AUDIO_ONLY` (`"zero"`),
  the music-app default.

- **Capability state** — the remaining engine-specific surface is **read-only capability STATE**
  (`VideoQualitySelectable`/`CaptionSelectable`/`AudioTrackSelectable` expose the *available*
  qualities/captions/audio-tracks the engine discovered, queried `engine as? Cap`); the only immediate capability
  *action* is `AudioTrackSelectable.selectAudioTrack` (a one-shot switch, not a persisted preference).

- **Player facade** — keeps granular public setters (`volume`/`speed`/`setAudioOnly`/`selectVideoQuality`/…) that
  read-modify-write its held `config` and push it — the UI reads `Player.config` for the audio-only/quality state
  (there is no `PlaybackStatus.audioOnly`). Volume and speed are getter/setters, not flows. SponsorBlock skip ranges ride `setSkipSegments` /
  `skipSegments`: on a play-head tick in the segment's start second the facade delays to the exact
  start then seeks to the end (ranges under 1 s are dropped). "Previous" restarts after a 0s intro
  skip when one is armed.

- **Queue growth** — `setQueue(…, source = QueueSource)` lets a queue grow past its first items (a playlist's next
  pages); `QueueState.more` carries total/loading/error/end for the UI, `Player.loadMore()` is its button/retry.
  Whenever the current item is within the queue's last 10, the next page loads (every mode). Known-length
  shuffle also rolls once per track: with probability unloaded / (unplayed + unloaded) it loads ONE page and
  plays the next track from it (later pages are under-weighted — accepted for simplicity). A failed page is retried once per track or by `loadMore`.

- **PlayableItem** — the sealed source type (`LocalUri` | `YtVideoId`; the router picks an engine by variant).

- **LocalPlayer** — today the one implementation is **`LocalPlayer : MediaEngine`** — the native-local engine that
  plays ONE local file (`PlayableItem.LocalUri`; **no** queue/gapless/playlist — a higher layer drives one engine
  per item).

- **Threading** — **main-thread-only by contract** (mirrors the legacy player): create and drive every method on the
  main thread; the underlying native player (ExoPlayer/AVPlayer/ffmpeg) does its own decoding on its own threads.
  - Impls do NOT marshal — ExoPlayer hard-enforces main, AVPlayer/ffmpeg are thread-safe and merely expect it
    (their internal poll/tickers run off-main).
  - Flows collect from anywhere.

- **Loading** — `load` is **fire-and-forget** (non-suspend): it returns immediately and readiness/failure is observed
  via `status` (Buffering→Ready/Error, `status.error` — loading never throws).

- **Modes** — supports **both video+audio and audio-only**: audio-only (config `preferVideoQuality == "zero"`)
  disables the video renderer/decode (the music default) and `PlaybackStatus.videoSize` reports the current track's
  dimensions.

- **Video surface** — video *rendering* follows the legacy `UniPlayer` model.
  - The shell hands **one shared video container** to the engines (via each engine's context —
    Android `LocalPlayerContext.videoContainer`, JVM `LocalPlayerContext.videoSurface`; MSE owns `MseVideoSurface`).
  - Android / iOS: **each engine owns its own surface inside it** — attaches it, and shows/hides it from its own
    lifecycle (visible while that engine is the active one, hidden when idle/stopped or another engine takes
    over) so only the playing engine's picture shows. Android local = a `SurfaceView` in a `FrameLayout` (holder
    wires `ExoPlayer.setVideoSurface`); YouTube = MSE into a TextureView (Android) / `copyFrame` (iOS).
  - JVM desktop: **one video Canvas for both engines** — the shell's `DesktopVideoSurface` (`:mse`) owns its
    presenter and binds it to the active engine: the local player claims it from load to stop, YouTube
    (`MseVideoSurface(videoSurface)`) has it otherwise. See [`mse/README.md`](../mse/README.md).

- **Surface hosting** — the UI never observes which engine is active — the facade exposes **no** `currentEngine`.
  - `AppGraph.playerSurface(modifier)` hosts the one container, and the shell keeps it hosted **persistently while
    a source needs it** (docked above the mini-player when Now Playing is closed, handed to the big video box when
    open — the same container re-parents, which the engines tolerate), never only inside the on-demand Now Playing.
  - (Apple `player0: AVPlayer` for the SwiftUI spike's `AVPlayerLayer`.)

- **Pure-common surface** — `LocalPlayer` interface + `Progress`/`PlaybackStatus`/`PlaybackState`/`PlayerError`/
  `VideoSize` model + `expect LocalPlayerContext` (Android carries a `Context` + optional video `ViewGroup`; JVM an
  optional `DesktopVideoSurface`; empty on Apple) + `createLocalPlayer(context)` factory.

- **State flows** — state is **split by cadence into two flows**: `progress: StateFlow<Progress>`
  (position/duration/buffered — the ~5 Hz ticker writes ONLY this) and `status: StateFlow<PlaybackStatus>`
  (state/playWhenReady/videoSize/error — discrete events only; volume/speed are `Player` getter/setters
  that push `PlaybackConfig`), so an observer of a rarely-changing field is never woken by a position tick (avoids)
  per-tick `map`/`distinctUntilChanged` + Compose recomposition churn).

- **Backends**:
  - **Apple** `appleMain` = one AVFoundation `AVPlayer` impl for iOS **and** macOS (readiness + EOF detected by
    *polling* `AVPlayerItem.status`/play-head vs duration, so nothing depends on run-loop-delivered KVO — but
    AVFoundation still advances item readiness on the **main dispatch queue**, so a headless test must pump the
    main run loop; audio-only disables the video `AVPlayerItemTrack`s).
  - **Android** `androidMain` = Media3 ExoPlayer, bound to the main looper (caller must drive from main; no
    internal marshaling), `Player.Listener`-driven state (audio-only via `setTrackTypeDisabled(TRACK_TYPE_VIDEO)`).
  - **JVM desktop** `jvmMain` = `JvmLocalPlayer` over the in-process libav player `file_av.c` (`:mse`, JNI
    `FileAvNative`): demux, audio decode + `audio_fx` DSP into the platform audio queue (AudioQueue / waveOut /
    PulseAudio) whose played samples are the clock, and video decoded through `vpipe` (hardware where the
    platform backend takes it) onto the shared `DesktopVideoSurface`. One `player` thread makes every native
    call; audio-only stops video decode.

- **Targets** — jvm/android/ios/macos (`withIosTargets()`+`withMacosTargets()`).

- **Verification** — verified end-to-end on macOS (`:player:macosX64Test` loads/plays/seeks a generated WAV);
  iOS-simulator suites run on an Apple-Silicon box with real-playback tests gated off (`canPlayRealMedia` — AVFoundation can't
  open the media pipeline in a bare simctl-spawned sim process, CoreMedia -12746; app-context sim playback = the
  ios-player-lab spike); android/jvm compile-verified.
