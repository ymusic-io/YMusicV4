# iosApp — the shared Compose UI on iOS

The iOS product shell: runs the **same** shared Compose `App` (`:commonUi`) as the desktop and Android
shells, on a real iPhone/iPad, over the AVFoundation local + MSE YouTube engines. Symmetric with
`:desktopApp` and `:androidApp`.

`iosApp/iOSApp.swift` (SwiftUI `@main`) hosts `MainViewControllerKt.MainViewController()` from the
`:commonUi` **YMusicApp** framework, which builds the iOS `AppGraph` and runs `App(graph)`.

## SDK / Xcode requirement

The whole Kotlin chain (`:quickjs` / `:media-store` / `:dynamic-engine` / `:player` + the shared Compose
UI) **compiles for `iosArm64`** here. Compose Multiplatform resolves its iOS 26 UIKit class
(`UIViewLayoutRegion`) at runtime since 1.12, so the framework links on older Xcode SDKs with no extra
flags.

## Simulator

Compose MP 1.11.1 has **no `iosX64`**. The Intel iOS Simulator is x86_64, so this app **cannot run
in Simulator on this Mac**. Apple Silicon hosts use `iosSimulatorArm64` (Xcode destination → any
iPhone/iPad sim). On Intel, skip the sim and use a plugged-in device.

## Run on a device (Xcode 16.2, today)

1. `ruby gen_xcodeproj.rb` (regenerates `iosApp.xcodeproj`; needs the `xcodeproj` gem)
2. `open iosApp.xcodeproj`
3. Target **iosApp** → **Signing & Capabilities** → pick your **Team** (change the bundle id if
   `com.ymusic.iosapp` is taken).
4. Plug in + unlock + **Trust** the device; select it as the run destination.
5. **Run** (⌘R). First launch: **Settings → General → VPN & Device Management** → trust the profile.

The Gradle build phase runs `:iosAppKotlin:embedAndSignAppleFrameworkForXcode -PkmpTargets=all` to build
+ embed + sign `YMusicApp.framework`. `-PkmpTargets=all` configures
`iosArm64` on this Intel host; `JAVA_HOME` is set to `$HOME/.sdkman/candidates/java/current`.

Signing is the only manual step — this environment can't enter your Apple ID; Xcode does it locally.

## Notes

- `mediaStore` is null in the iOS graph for now (Library shows empty) — keeps the shell lean; the
  media-store SQLDelight native driver can be wired later (it already compiles for `iosArm64`).
- Local video isn't hosted in the shared surface on iOS yet (AVFoundation renders via `AVPlayerLayer`
  in the player-lab spike); YouTube picture is MSE `copyFrame`. Audio (the music default) is fine.
- Settings → Link devices uses the camera (`NSCameraUsageDescription`) to scan a desktop QR, then
  PIN/Face ID (`NSFaceIDUsageDescription`) before POSTing the encrypted YouTube cookies.
