# iosApp — the shared Compose UI on iOS

The iOS product shell: runs the **same** shared Compose `App` (`:commonUi`) as the desktop and Android
shells, on a real iPhone/iPad, over the AVFoundation local + MSE YouTube engines. Symmetric with
`:desktopApp` and `:androidApp`.

`iosApp/iOSApp.swift` (SwiftUI `@main`) hosts `MainViewControllerKt.MainViewController()` from the
`:commonUi` **YMusicApp** framework, which builds the iOS `AppGraph` and runs `App(graph)`.

## SDK / Xcode requirement

The whole Kotlin chain (`:quickjs` / `:media-store` / `:dynamic-engine` / `:player` + the shared Compose
UI) **compiles for `iosArm64`** here. Linking the Compose framework is the subtle part:

Compose Multiplatform 1.11.1 (with Kotlin/Native 2.4.0, whose toolchain is pinned to **Xcode 26.4**)
links `ui-uikit` against `UIViewLayoutRegion` — an **iOS 26** UIKit class. It is absent from the iOS
17.4 (Xcode 15.3) **and** iOS 18.2 (Xcode 16.2) SDKs, so a normal link fails with:

```
Undefined symbols for architecture arm64:
  "_OBJC_CLASS_$_UIViewLayoutRegion", referenced from: … ui-uikit … CMPLayoutRegion.o
```

- **Clean build: Xcode 26 (iOS 26 SDK).** That needs macOS 15/26 — newer than this host's macOS 14.8,
  so it's a macOS upgrade or a CI runner with Xcode 26.
- **Works today on Xcode 16.2 (this Mac) via a guarded workaround:** `-PiosAllowUndefined=true` (baked
  into the Gradle build phase) links with `-undefined dynamic_lookup`, allowing the iOS 26 symbol to
  stay unresolved. Compose `@available`-guards its use, so on a sub-iOS-26 device (e.g. the iPad on iOS
  17.5) it is never referenced. **Verified: a complete arm64 `.app` builds** (arm64 binary + embedded
  `YMusicApp.framework` + `compose-resources`). Runtime on-device is still to be confirmed; this is a
  dev/test build, **not** App-Store-shippable (dynamic_lookup) — drop the flag on Xcode 26.

## Simulator

The project has **no `iosX64`** target (neither Compose MP 1.11.1 nor the static iOS ffmpeg exists for
x86_64), so this app **cannot run in an Intel Mac's Simulator**. Apple Silicon hosts use
`iosSimulatorArm64` (Xcode destination → any iPhone/iPad sim). On Intel, use a plugged-in device.

## Run on a device (Xcode 16.2, today)

1. `ruby gen_xcodeproj.rb` (regenerates `iosApp.xcodeproj`; needs the `xcodeproj` gem)
2. `open iosApp.xcodeproj`
3. Target **iosApp** → **Signing & Capabilities** → pick your **Team** (change the bundle id if
   `com.ymusic.iosapp` is taken).
4. Plug in + unlock + **Trust** the device; select it as the run destination.
5. **Run** (⌘R). First launch: **Settings → General → VPN & Device Management** → trust the profile.

The Gradle build phase runs `:iosAppKotlin:embedAndSignAppleFrameworkForXcode -PkmpTargets=all
-PiosAllowUndefined=true` to build + embed + sign `YMusicApp.framework`. `-PkmpTargets=all` configures
`iosArm64` on this Intel host; `JAVA_HOME` is set to `$HOME/.sdkman/candidates/java/current`.

Signing is the only manual step — this environment can't enter your Apple ID; Xcode does it locally.

## Notes

- `mediaStore` is null in the iOS graph for now (Library shows empty) — keeps the shell lean; the
  media-store SQLDelight native driver can be wired later (it already compiles for `iosArm64`).
- Local video isn't hosted in the shared surface on iOS yet (AVFoundation renders via `AVPlayerLayer`
  in the player-lab spike); YouTube picture is MSE `copyFrame`. Audio (the music default) is fine.
- Settings → Link devices uses the camera (`NSCameraUsageDescription`) to scan a desktop QR, then
  PIN/Face ID (`NSFaceIDUsageDescription`) before POSTing the encrypted YouTube cookies.
