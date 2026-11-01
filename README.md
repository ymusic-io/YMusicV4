# YMusic

A Kotlin Multiplatform music player for Android, desktop (MacOs, Windows, Linux), and iOS/iPad.

> [!CAUTION]
> **Only install YMusic from trusted sources:** [ymusic.io](https://ymusic.io) or [GitHub Releases](https://github.com/ymusic-io/YMusicV4/releases). Anyone can build a modified copy — bad actors can ship a fake app that steals your Google account credentials when you sign in.
>
> **Misuse Disclaimer:**
> The authors are not responsible for any misuse of the application.

## Building

Every platform needs **JDK 17+** (CI uses 21), **Node.js** (CI uses 22) and **CMake 3.20+**. Packaging
an installer needs a full JDK that ships `jpackage` (e.g. Temurin; not a JBR). The desktop app is
built on, and for, the machine's own OS and CPU.

### FFmpeg

FFmpeg comes from [ffmpeg-build](https://github.com/ymusic-io/ffmpeg-build), checked out next to this
repository (`../ffmpeg-build`) and built locally — nothing is downloaded. Check out the commit CI builds
against (`FFMPEG_BUILD_REF` in [`release.yml`](.github/workflows/release.yml)), then build the targets
you need (its README has the details). A missing build fails with the command that produces it.

```sh
# Windows: first `git config --global core.autocrlf false`, or the build scripts break on CRLF
git clone --recursive --shallow-submodules https://github.com/ymusic-io/ffmpeg-build ../ffmpeg-build
git -C ../ffmpeg-build checkout "$(sed -n 's/^ *FFMPEG_BUILD_REF: //p' .github/workflows/release.yml)"

cd ../ffmpeg-build
scripts/build-desktop.sh            # desktop: macOS, Linux, or Windows from an MSYS2 shell
scripts/build-android.sh arm64-v8a  # Android, once per ABI: armeabi-v7a, arm64-v8a, x86, x86_64
scripts/build-ios.sh                # iOS
```

### Per platform

- **macOS** — Xcode (the iOS app, and the command-line tools for the desktop natives). ffmpeg-build:
  `brew install nasm automake autoconf libtool pkg-config meson ninja`.
- **Linux** (Debian/Ubuntu) — the app: `sudo apt install build-essential cmake libpulse-dev`, plus
  `fakeroot` to package the `.deb`. ffmpeg-build:
  `sudo apt install nasm automake autoconf libtool pkg-config meson ninja-build patchelf`.
- **Windows** — [MSYS2](https://www.msys2.org). The app's native libraries are GCC/Clang-only C, so they
  build with MinGW-w64, not MSVC. The build finds it in `C:/msys64/mingw64` (`C:/msys64/clangarm64` on
  ARM), or wherever `mingw.dir` in `local.properties` or `MINGW_HOME` points.
  - x64, in the MSYS2 MINGW64 shell: `pacman -S git make diffutils autoconf automake libtool patch mingw-w64-x86_64-toolchain mingw-w64-x86_64-make mingw-w64-x86_64-nasm mingw-w64-x86_64-pkgconf mingw-w64-x86_64-meson mingw-w64-x86_64-ninja mingw-w64-x86_64-zlib`
  - ARM64, in the CLANGARM64 shell: the same with `mingw-w64-clang-aarch64-` packages (no nasm), and
    `HOST_CC=clang` when running `scripts/build-desktop.sh`.
  - The installer needs [Inno Setup 6](https://jrsoftware.org/isinfo.php): `inno.iscc=<path to ISCC.exe>`
    in `local.properties`, or ISCC on `PATH`.
- **Android** — the Android SDK with NDK r28 (`28.2.13676358`). ffmpeg-build needs `ANDROID_NDK_ROOT`
  pointing at it, `nasm`, the autotools and `patchelf`.

### Run

```sh
./gradlew :desktopApp:run            # desktop app
./gradlew :androidApp:installDebug   # Android, on a connected device
```

The iOS app builds from `iosApp/iosApp.xcodeproj` in Xcode; its Kotlin framework comes from
`:iosAppKotlin` automatically.

## License

YMusic is open source under the [GNU General Public License v3.0](LICENSE), except the
foundation libraries below, which are Apache-2.0 so any project can reuse them:

| Directory | License |
|---|---|
| `kmpCommon/` | [Apache-2.0](kmpCommon/LICENSE) |
| `kmpHttp/` | [Apache-2.0](kmpHttp/LICENSE) |
| everything else | [GPL-3.0](LICENSE) |

Nothing under `kmpCommon/` or `kmpHttp/` may depend on a GPL module.

By contributing, you agree that your contribution is licensed under the same license as the
directory it modifies.
