#!/usr/bin/env bash
# Builds the release ymusic on Linux and stages it as the JVM app's .deb laid it out: /opt/ymusic/bin/YMusic
# (the path the JVM updater relaunches) beside the packed ffmpeg (where downloads look for it), the
# libav DSOs in /opt/ymusic/lib, both binaries loading them through $ORIGIN/../lib.
#   --deb       the .deb the JVM app's updater installs over its own: package ymusic, version
#               <versionName>-<versionCode> (a release that bumps only the code still upgrades), the
#               same paths (dpkg drops jpackage's leftovers) and the JVM's desktop entry name, so dash
#               pins and Open With defaults carry over.
#   --appimage  the same files as an AppImage (AppRun is the executable; its rpath finds the DSOs).
#   The release profile also leaves the unstripped binary in <out>/debug for the Sentry upload.
#
# usage: tools/package-linux.sh [--no-build] [--debug] [--deb] [--appimage] [--out <dir>]
# env:   YMUSIC_V4_DIR      YMusicV4 checkout (default: the one this workspace sits in): icon, version line
#        FFMPEG_BUILD_DIR   ffmpeg-build checkout (default: beside the YMusicV4 checkout), its out/linux-<x86_64|arm64>
#        APPIMAGETOOL       appimagetool (default: on PATH), APPIMAGE_RUNTIME its type2 runtime file
#        YMUSIC_VERSION_NAME, YMUSIC_VERSION_CODE  as for the build (crates/ymusic/build.rs)
#        CARGO_TARGET_DIR   default <workspace>/target
set -euo pipefail

APP_NAME=YMusic
APP_ID=io.ymusic.YMusic
DEB_PACKAGE=ymusic
# The JVM app's entry (jpackage's <package>-<app>.desktop); its prerm uninstalls it on upgrade.
DEB_ENTRY=ymusic-YMusic.desktop
# The JVM app's list (desktopApp linuxMimeTypes): the demuxers the packed ffmpeg has.
MIME_TYPES="audio/mpeg;audio/mp4;audio/x-m4a;audio/x-m4b;audio/aac;audio/flac;audio/x-flac;audio/wav;audio/x-wav;\
audio/ogg;audio/opus;audio/webm;audio/x-matroska;video/mp4;video/x-m4v;video/quicktime;video/webm;video/x-matroska;\
x-scheme-handler/ymusic;"

root=$(cd "$(dirname "$0")/.." && pwd)
build=1
profile=release
deb=0
appimage=0
out=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --no-build) build=0 ;;
        --debug) profile=debug ;;
        --deb) deb=1 ;;
        --appimage) appimage=1 ;;
        --out) out=$2; shift ;;
        *) echo "usage: $0 [--no-build] [--debug] [--deb] [--appimage] [--out <dir>]" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "package-linux: $*" >&2; exit 1; }
v4=$(cd "${YMUSIC_V4_DIR:-$root/..}" && pwd)
case $(uname -m) in
    x86_64) slug=linux-x86_64; deb_arch=amd64; asset_arch=x86_64; appimage_arch=x86_64 ;;
    aarch64 | arm64) slug=linux-arm64; deb_arch=arm64; asset_arch=arm64; appimage_arch=aarch64 ;;
    *) die "unsupported architecture $(uname -m)" ;;
esac
ffmpeg_build=$(cd "${FFMPEG_BUILD_DIR:-$root/../../ffmpeg-build}" && pwd)
ffmpeg_root=$ffmpeg_build/out/$slug
target_dir=${CARGO_TARGET_DIR:-$root/target}
out=${out:-$target_dir/bundle}
icon=$v4/desktopApp/ymusic.png
pack=$ffmpeg_root/pack
[[ -x $pack/ffmpeg ]] || die "no packed ffmpeg at $pack/ffmpeg (set FFMPEG_BUILD_DIR)"
[[ -f $icon ]] || die "no icon at $icon (set YMUSIC_V4_DIR)"
command -v patchelf > /dev/null || die "patchelf is missing"

if [[ $build == 1 ]]; then
    cargo_profile=()
    [[ $profile == release ]] && cargo_profile=(--release --features production)
    # Built with line tables; the packaged copy loses them below, the unstripped one goes to Sentry.
    (cd "$root" && YMUSIC_V4_DIR=$v4 FFMPEG_BUILD_DIR=$ffmpeg_build \
        CARGO_PROFILE_RELEASE_DEBUG=line-tables-only CARGO_PROFILE_RELEASE_STRIP=none \
        cargo build ${cargo_profile[@]+"${cargo_profile[@]}"} -p ymusic)
fi
binary=$target_dir/$profile/ymusic
[[ -f $binary ]] || die "no $profile binary at $binary"
rm -rf "$out/debug"
if [[ $profile == release ]]; then
    command -v strip > /dev/null || die "strip is missing"
    mkdir -p "$out/debug"
    cp "$binary" "$out/debug/ymusic"
fi
version=${YMUSIC_VERSION_NAME:-$(sed -n 's/^ymusic\.versionName=//p' "$v4/gradle.properties" | tr -d '[:space:]')}
version_code=${YMUSIC_VERSION_CODE:-$(sed -n 's/^ymusic\.versionCode=//p' "$v4/gradle.properties" | tr -d '[:space:]')}
[[ -n $version && -n $version_code ]] || die "no version in $v4/gradle.properties"

# One tree, two prefixes: /opt/ymusic for the .deb, usr/ inside the AppImage.
stage_app() {
    local bin=$1/bin lib=$1/lib
    mkdir -p "$bin" "$lib"
    cp "$binary" "$bin/$APP_NAME"
    [[ $profile == release ]] && strip --strip-debug "$bin/$APP_NAME"
    cp "$pack/ffmpeg" "$bin/ffmpeg"
    local file name
    for file in "$pack"/lib*.so.*; do
        name=${file##*/}
        # The soname files only (libavcodec.so.63): what the binaries and each other ask for.
        [[ $name =~ ^lib.+\.so\.[0-9]+$ ]] && cp -L "$file" "$lib/$name"
    done
    patchelf --set-rpath '$ORIGIN/../lib' "$bin/$APP_NAME" "$bin/ffmpeg"
    local missing
    missing=$(env -u LD_LIBRARY_PATH ldd "$bin/$APP_NAME" "$bin/ffmpeg" | grep 'not found' || true)
    [[ -z $missing ]] || die "unresolved libraries in $1: $missing"
    "$bin/ffmpeg" -hide_banner -version > /dev/null || die "the packed ffmpeg does not run from $bin"
}

desktop_entry() {
    printf '%s\n' "[Desktop Entry]" "Type=Application" "Name=$APP_NAME" "Comment=Music player" \
        "Exec=$1 %U" "Icon=$APP_ID" "Terminal=false" "Categories=AudioVideo;Audio;Player;" \
        "MimeType=$MIME_TYPES" "StartupWMClass=$APP_ID"
}

rm -rf "$out/linux"
mkdir -p "$out/linux"
work=$out/linux

if [[ $deb == 1 ]]; then
    tree=$work/deb
    stage_app "$tree/opt/ymusic"
    mkdir -p "$tree/usr/bin" "$tree/usr/share/applications" "$tree/usr/share/icons/hicolor/512x512/apps" "$tree/DEBIAN"
    ln -s /opt/ymusic/bin/$APP_NAME "$tree/usr/bin/ymusic"
    desktop_entry /opt/ymusic/bin/$APP_NAME > "$tree/usr/share/applications/$DEB_ENTRY"
    cp "$icon" "$tree/usr/share/icons/hicolor/512x512/apps/$APP_ID.png"
    # The newest glibc symbol any shipped binary needs: the build host's floor, not the developer's.
    glibc=$(objdump -T "$tree"/opt/ymusic/bin/* "$tree"/opt/ymusic/lib/* | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' |
        sort -Vu | tail -n 1)
    # wgpu draws through Vulkan or EGL and audio goes to PulseAudio (both loaded at runtime).
    cat > "$tree/DEBIAN/control" << EOF
Package: $DEB_PACKAGE
Version: $version-$version_code
Architecture: $deb_arch
Maintainer: YMusic <Unknown>
Installed-Size: $(du -sk --exclude=DEBIAN "$tree" | cut -f1)
Depends: libc6 (>= $glibc), libgcc-s1, zlib1g, libxcb1, libxkbcommon0, libxkbcommon-x11-0, libwayland-client0, libwayland-egl1, libegl1, libpulse0
Recommends: libvulkan1, mesa-vulkan-drivers
Section: sound
Priority: optional
Homepage: https://ymusic.io
Description: Music player
EOF
    deb_file=$out/$APP_NAME-$version-linux-$asset_arch.deb
    dpkg-deb --root-owner-group -Zxz --build "$tree" "$deb_file" > /dev/null
    echo "package-linux: $deb_file"
fi

if [[ $appimage == 1 ]]; then
    appimagetool=${APPIMAGETOOL:-$(command -v appimagetool || true)}
    [[ -n $appimagetool && -x $appimagetool ]] || die "no appimagetool (set APPIMAGETOOL)"
    [[ -n ${APPIMAGE_RUNTIME:-} && -f $APPIMAGE_RUNTIME ]] || die "no AppImage runtime (set APPIMAGE_RUNTIME)"
    dir=$work/$APP_NAME.AppDir
    stage_app "$dir/usr"
    mkdir -p "$dir/usr/share/applications" "$dir/usr/share/icons/hicolor/512x512/apps"
    desktop_entry $APP_NAME > "$dir/usr/share/applications/$APP_ID.desktop"
    cp "$icon" "$dir/usr/share/icons/hicolor/512x512/apps/$APP_ID.png"
    cp "$dir/usr/share/applications/$APP_ID.desktop" "$dir/$APP_ID.desktop"
    cp "$icon" "$dir/$APP_ID.png"
    ln -s "$APP_ID.png" "$dir/.DirIcon"
    ln -s "usr/bin/$APP_NAME" "$dir/AppRun"
    image=$out/$APP_NAME-$version-linux-$asset_arch.AppImage
    # Extract-and-run: appimagetool is an AppImage itself, and CI containers have no FUSE.
    ARCH=$appimage_arch APPIMAGE_EXTRACT_AND_RUN=1 "$appimagetool" --no-appstream \
        --runtime-file "$APPIMAGE_RUNTIME" "$dir" "$image" > "$work/appimagetool.log" 2>&1 ||
        { cat "$work/appimagetool.log" >&2; die "appimagetool failed"; }
    echo "package-linux: $image"
fi
