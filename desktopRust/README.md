# desktopRust

The YMusicV4 desktop app, written in Rust.

## Download

From the [YMusicV4 releases](https://github.com/ymusic-io/YMusicV4/releases/latest):

| Platform | Architectures | Package |
| --- | --- | --- |
| macOS 11 or later | Apple silicon, Intel | `.dmg` |
| Windows 10 or later | x64, arm64 | `-setup.exe` |
| Linux, glibc 2.35 or later (Ubuntu 22.04, Debian 12) | x86_64, arm64 | `.deb`, `.AppImage` |

## Build

This workspace lives inside the YMusicV4 checkout; ffmpeg-build sits beside it:

```
projects/
├── ffmpeg-build/        the libav the app links and the ffmpeg it packs
└── YMusicV4/            C sources, engine bundles, strings, version
    └── desktopRust/     this workspace
```

### 1. ffmpeg

```sh
git clone --recursive --shallow-submodules https://github.com/ymusic-io/ffmpeg-build.git
cd ffmpeg-build && scripts/build-desktop.sh      # → out/<os>-<arch>
```

Needs nasm, autoconf, automake, libtool, pkg-config, meson and ninja. On Windows, run it in MSYS2
MINGW64 (arm64: CLANGARM64 with `HOST_CC=clang`).

### 2. Engine bundles

```sh
git clone https://github.com/ymusic-io/YMusicV4.git
cd YMusicV4 && ./gradlew :engine-js:closureJs :engine-js:closurePlayerJs      # → engine-js/build/dist
```

Needs JDK 21, Node 22 and the Android SDK.

### 3. The app

```sh
cd YMusicV4/desktopRust && cargo run -p ymusic
```

Needs Rust 1.99.1, and:

- **macOS**: Xcode with its Metal toolchain.
- **Linux**: `libxkbcommon-dev libxkbcommon-x11-dev libwayland-dev libxcb1-dev libfreetype-dev libfontconfig-dev libpulse-dev libvulkan-dev`.
- **Windows**: the `x86_64-pc-windows-gnu` toolchain with MSYS2's MinGW gcc on `PATH` (arm64: `aarch64-pc-windows-gnullvm` with CLANGARM64's clang).

YMusicV4 and ffmpeg-build are found through `YMUSIC_V4_DIR` and `FFMPEG_BUILD_DIR`, set in
`.cargo/config.toml` to `..` and `../../ffmpeg-build`; an environment variable of the same name
points elsewhere.

Debug builds keep their own data dir, apart from the installed app's `~/.ymusic`; `YMUSIC_DATA_DIR`
picks another. Without a `secret.properties` (keys in `secret.properties.example`) the app runs on
its embedded bundles, with no config service, QR sign-in, self-update or crash reports.

## Release

YMusicV4's GitHub Actions build and publish the desktop packages from this workspace. The scripts
they run package a local build too:

```sh
tools/bundle-macos.sh --dmg                 # DMG
tools/package-linux.sh --deb --appimage     # .deb + AppImage
tools/package-windows.sh --setup            # installer, needs Inno Setup
```

## License

The desktop app is open source under the [GNU General Public License v3.0](LICENSE), like YMusicV4,
whose native player, engine bundles and strings it builds in. The vendored gpui crates in `vendor/`
keep their own Apache-2.0 license ([vendor/README.md](vendor/README.md)).
