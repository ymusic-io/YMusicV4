# desktopRust

The YMusic desktop app for macOS, Windows and Linux, written in Rust. Downloads and the setup shared
with the other apps (JDK, Node.js, ffmpeg-build) are in the [root README](../README.md).

## Run

Build the engine bundles from the YMusicV4 root once (again after changing `engine-js/`), then run the app:

```sh
./gradlew :engine-js:closureJs :engine-js:closurePlayerJs
cd desktopRust && cargo run -p ymusic
```

Needs the Rust toolchain CI uses (`RUST_TOOLCHAIN` in [`release.yml`](../.github/workflows/release.yml)), and:

- **macOS**: Xcode with its Metal toolchain.
- **Linux**: `sudo apt install build-essential cmake libpulse-dev libxkbcommon-dev libxkbcommon-x11-dev libwayland-dev libxcb1-dev libfreetype-dev libfontconfig-dev libvulkan-dev`.
- **Windows**: the `x86_64-pc-windows-gnu` toolchain with MSYS2's MinGW gcc on `PATH` (ARM64:
  `aarch64-pc-windows-gnullvm` with CLANGARM64's clang).

The build reads the YMusicV4 checkout (C sources, engine bundles, strings, the version) from
`YMUSIC_V4_DIR` and links ffmpeg-build's `out/<os>-<arch>` from `FFMPEG_BUILD_DIR`. They default to `..`
and `../../ffmpeg-build` (`.cargo/config.toml`); an environment variable of the same name overrides them.

Debug builds keep their data in `YMusicRust` under the OS's local data dir, apart from the installed
app's `~/.ymusic`; `--data-dir=` or `YMUSIC_DATA_DIR` picks another. Their window title names the branch
they were built from.

To run several debug builds side by side (one per worktree), give each worktree a data slot with
`tools/dev-slot.sh claim` from the YMusicV4 root. It writes `.ymusic-dev-slot` (`slot=<N>`,
`root=<slot folder>`) at the worktree root, which a debug build reads at launch: its data dir becomes
`<root>/rust`, its title `YMusic · <branch> · #<N>`, and it takes its own Dock badge, Windows
AppUserModelID (`io.ymusic.windows.dev<N>`), Linux app id (`io.ymusic.YMusic.dev<N>`) and MPRIS name.
Slots after the first leave the OS media session and media keys to slot 1. `--print-data-dir` prints
the data dir, slot and title a launch would use and exits.

Without YMusicV4's `secrets.properties` (its
`remote.config.url`, `bundle.signing.public.key`, `update.public.key`, `sentry.dsn`) the app runs on its
embedded bundles, with no config service, QR sign-in, self-update or crash reports.

## Package

The release workflow builds every package with these scripts; they package a local build too:

```sh
tools/bundle-macos.sh --dmg                 # .dmg
tools/package-linux.sh --deb --appimage     # .deb and .AppImage
tools/package-windows.sh --setup            # installer
```

- The AppImage needs [appimagetool](https://github.com/AppImage/appimagetool) (`APPIMAGETOOL`, or on
  `PATH`) and its type2 runtime (`APPIMAGE_RUNTIME`); CI pins both (`APPIMAGETOOL_VERSION`,
  `APPIMAGE_RUNTIME_VERSION`).
- The `.deb` needs only `dpkg-deb` (no fakeroot).
- The Windows installer needs [Inno Setup 6](https://jrsoftware.org/isinfo.php), found where it installs
  or through `ISCC`.

## License

GPL-3.0 ([LICENSE](LICENSE)), like the rest of YMusicV4. The vendored gpui crates in `vendor/` keep
their own Apache-2.0 license ([vendor/README.md](vendor/README.md)).
