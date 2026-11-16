#!/usr/bin/env bash
# Builds the release ymusic on Windows (Git Bash; MinGW gcc, or clang on arm64, on PATH) and stages
# the app folder: YMusic.exe, the packed ffmpeg.exe and the libav / MinGW runtime DLLs beside it,
# plus any runtime DLL the executable imports from the compiler's bin folder. --setup also
# compiles the Inno Setup installer that the JVM app's updater runs over its own install
# (tools/windows/ymusic.iss), named as the release asset YMusic-<version>-windows-<x64|arm64>-setup.exe.
#
# usage: tools/package-windows.sh [--no-build] [--debug] [--setup | --setup-only] [--arch <x86_64|arm64>] [--out <dir>]
#   --debug       stage target/debug/ymusic.exe (local update tests; dev hooks stay on)
#   --setup-only  no build or staging: compile the installer from an app folder already at <out>/YMusic
#   --arch        the app folder's architecture (default: rustc's host)
# env:   YMUSIC_V4_DIR      YMusicV4 checkout (default: the one this workspace sits in): icon, version line
#        FFMPEG_BUILD_DIR   ffmpeg-build checkout (default: beside the YMusicV4 checkout), its out/windows-<arch>
#        ISCC               Inno Setup's compiler (default: the newest installed Inno Setup)
#        YMUSIC_VERSION_NAME, YMUSIC_VERSION_CODE  as for the build (crates/ymusic/build.rs)
#        CARGO_TARGET_DIR   default <workspace>/target
#        SIGN_SCRIPT        a bash script that Authenticode-signs the files it is given in place; set, it signs
#                           the app folder's executables and DLLs, then the uninstaller and the setup (Inno's SignTool)
set -euo pipefail

APP_NAME=YMusic

root=$(cd "$(dirname "$0")/.." && pwd)
build=1
profile=release
setup=0
stage=1
arch=""
out=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --no-build) build=0 ;;
        --debug) profile=debug ;;
        --setup) setup=1 ;;
        --setup-only) setup=1; stage=0 ;;
        --arch) arch=$2; shift ;;
        --out) out=$2; shift ;;
        *) echo "usage: $0 [--no-build] [--debug] [--setup | --setup-only] [--arch <x86_64|arm64>] [--out <dir>]" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "package-windows: $*" >&2; exit 1; }
v4=$(cd "${YMUSIC_V4_DIR:-$root/..}" && pwd)
if [[ -z $arch ]]; then
    # rustc's host, not uname: Git Bash may run emulated as x64 on an arm64 machine.
    case $(rustc -vV | sed -n 's/^host: //p') in
        aarch64-*) arch=arm64 ;;
        *) arch=x86_64 ;;
    esac
fi
case $arch in
    arm64) inno_arch=arm64; asset_arch=arm64 ;;
    x86_64) inno_arch=x64compatible; asset_arch=x64 ;;
    *) die "unknown --arch $arch (x86_64 or arm64)" ;;
esac
target_dir=${CARGO_TARGET_DIR:-$root/target}
out=${out:-$target_dir/bundle}
app=$out/$APP_NAME
icon=$v4/desktopApp/ymusic.ico
[[ -f $icon ]] || die "no icon at $icon (set YMUSIC_V4_DIR)"
# The version line the binary reports (crates/ymusic/build.rs reads the same inputs).
version=${YMUSIC_VERSION_NAME:-$(sed -n 's/^ymusic\.versionName=//p' "$v4/gradle.properties" | tr -d '[:space:]')}
[[ -n $version ]] || die "no version in $v4/gradle.properties"

if [[ $stage == 1 ]]; then
    ffmpeg_build=$(cd "${FFMPEG_BUILD_DIR:-$root/../../ffmpeg-build}" && pwd)
    ffmpeg_root=$ffmpeg_build/out/windows-$arch
    [[ -f $ffmpeg_root/pack/ffmpeg.exe ]] || die "no packed ffmpeg at $ffmpeg_root/pack/ffmpeg.exe (set FFMPEG_BUILD_DIR)"
    # By arch, not by what PATH has first: a runner may carry another gcc further down its PATH.
    if [[ $arch == arm64 ]]; then
        compiler=$(command -v clang) || die "no CLANGARM64 clang on PATH (the C sources need GCC/Clang)"
        objdump=$(command -v llvm-objdump) || die "no llvm-objdump on PATH"
    else
        compiler=$(command -v gcc) || die "no MinGW gcc on PATH (the C sources need GCC/Clang)"
        objdump=$(command -v objdump) || die "no objdump on PATH"
    fi

    if [[ $build == 1 ]]; then
        cargo_profile=()
        [[ $profile == release ]] && cargo_profile=(--release --features production)
        (cd "$root" && YMUSIC_V4_DIR=$v4 FFMPEG_BUILD_DIR=$ffmpeg_build cargo build ${cargo_profile[@]+"${cargo_profile[@]}"} -p ymusic)
    fi
    binary=$target_dir/$profile/ymusic.exe
    [[ -f $binary ]] || die "no $profile binary at $binary"

    rm -rf "$app"
    mkdir -p "$app"
    cp "$binary" "$app/$APP_NAME.exe"
    cp "$ffmpeg_root"/pack/*.dll "$ffmpeg_root/pack/ffmpeg.exe" "$app/"
    # Every DLL the executable imports is shipped here or part of Windows (api-ms-win-* are API sets);
    # a toolchain runtime the ffmpeg pack lacks (libunwind.dll under clang) comes from beside the compiler.
    missing=$("$objdump" -p "$app/$APP_NAME.exe" | sed -n 's/^\s*DLL Name: //p' | while read -r dll; do
        [[ $dll == api-ms-win-* || -f $app/$dll || -f /c/Windows/System32/$dll ]] && continue
        if [[ -f $(dirname "$compiler")/$dll ]]; then cp "$(dirname "$compiler")/$dll" "$app/"; else echo "$dll"; fi
    done)
    [[ -z $missing ]] || die "imports neither shipped nor in System32: $missing"
    echo "package-windows: $app"
fi

[[ $setup == 1 ]] || exit 0
[[ -f $app/$APP_NAME.exe ]] || die "no app folder at $app"
iscc=${ISCC:-}
if [[ -z $iscc ]]; then
    for candidate in "${LOCALAPPDATA:-}"/Programs/Inno\ Setup\ */ISCC.exe "/c/Program Files (x86)"/Inno\ Setup\ */ISCC.exe \
        "/c/Program Files"/Inno\ Setup\ */ISCC.exe; do
        [[ -f $candidate ]] && iscc=$candidate
    done
fi
[[ -n $iscc && -f $iscc ]] || die "no Inno Setup compiler (install Inno Setup or set ISCC)"
win() { cygpath -w "$1"; }
signing=()
if [[ -n ${SIGN_SCRIPT:-} ]]; then
    bash "$SIGN_SCRIPT" "$app"/*.exe "$app"/*.dll
    # ISCC runs the command itself: $q is a quote, $f the quoted file to sign.
    signing=("/DSignTool=signer" "/Ssigner=\$q$(cygpath -m "$BASH")\$q \$q$(cygpath -m "$SIGN_SCRIPT")\$q \$f")
fi
# Git Bash would rewrite ISCC's /D… switches as POSIX paths.
MSYS2_ARG_CONV_EXCL='*' "$iscc" /Qp "/DAppVersion=$version" "/DAppSourceDir=$(win "$app")" "/DIconFile=$(win "$icon")" \
    "/DOutputDir=$(win "$out")" "/DArch=$inno_arch" "/DOutputBaseName=$APP_NAME-$version-windows-$asset_arch-setup" \
    ${signing[@]+"${signing[@]}"} "$(win "$root/tools/windows/ymusic.iss")"
echo "package-windows: $out/$APP_NAME-$version-windows-$asset_arch-setup.exe"
