# ios-player-lab

A native **SwiftUI iOS app** that drives `:player` through the exported `Player.framework` in the
**iOS Simulator** — no Compose. The iOS counterpart of `spikes/macos-player-lab`, and the runnable
proof that the shared engine stack builds and runs on iOS without the shared Compose UI.

- **Local** — the AVFoundation `LocalPlayer` playing a bundled `sample.mp4` (`AVPlayerLayer` video +
  transport + audio-only). Verified: `state=Ready`, `video=320x180`, position advances to `Ended`.

Muted by default (config volume 0) — unmute with the slider.

## Run on a real device (Xcode) — arm64

`PlayerLab.xcodeproj` installs to a physical iPhone/iPad from this Intel Mac (Kotlin/Native
cross-compiles `iosArm64`).

1. `open PlayerLab.xcodeproj`
2. Target **PlayerLab** → **Signing & Capabilities** → pick your **Team** (Xcode auto-manages the
   provisioning profile; change the bundle id if `com.ymusic.iosplayerlab` is taken).
3. Plug in the device, unlock it, tap **Trust**. Select it as the run destination.
4. **Run** (⌘R). First launch: on the device, **Settings → General → VPN & Device Management** →
   trust your developer profile.

A Gradle build phase compiles + embeds + signs `Player.framework` for the SDK/arch Xcode targets
(`:player:embedAndSignAppleFrameworkForXcode -PkmpTargets=all` — `-PkmpTargets=all` so `iosArm64` is
configured on this Intel host). Regenerate the project after changing sources/resources/settings with
`ruby gen_xcodeproj.rb` (the `.xcodeproj` is generated, not hand-edited). It needs Java on PATH; the
phase sets `JAVA_HOME=$HOME/.sdkman/candidates/java/current`.

Signing is the only manual step — this environment can't enter your Apple ID; Xcode does it locally.

## Run in the Simulator (script, Apple Silicon)

```
./build.sh            # framework → swiftc (iphonesimulator) → .app → boot sim → install → launch
./build.sh --no-run   # build + bundle only
```

Requires an Apple Silicon Mac, Xcode, and an available iPhone simulator (there is no `iosX64`
target); `ffmpeg` on PATH to generate the sample clip. The
build links `player/build/bin/iosSimulatorArm64/debugFramework/Player.framework`
(`:player:linkDebugFrameworkIosSimulatorArm64`), compiles `Sources/PlayerLab.swift` for
`arm64-apple-ios*-simulator`, assembles a flat `.app`, and
launches it via `simctl` (streaming the app log — the `[ios-lab]` lines show playback advancing).
