#!/usr/bin/env bash
# Builds the release ymusic and assembles YMusic.app around it: Info.plist (ymusic:// links, media
# files under Open With at rank Alternate), the icon, the libav dylibs in Contents/Frameworks and the
# packed ffmpeg next to the executable (where downloads look for it), relinked to load only from
# inside the bundle, then signed. --dmg also packs it into the disk image the JVM app's updater
# installs (MacDmgInstaller: the one .app at the image root, copied out and swapped in).
#
# usage: tools/bundle-macos.sh [--no-build] [--debug] [--dmg] [--release] [--out <dir>]
#   --debug    bundle target/debug/ymusic (local update tests; dev hooks stay on)
#   The release profile also leaves ymusic.dSYM in <out>/debug for the Sentry upload.
#   --release  the shipped identity: the JVM app's bundle id (its Keychain item and updater carry
#              over), a DMG, a real signing identity, notarized (the Apple ID inputs are required)
# env:   YMUSIC_V4_DIR      YMusicV4 checkout (default: the one this workspace sits in): icon sources, the version line
#        FFMPEG_BUILD_DIR   ffmpeg-build checkout (default: beside the YMusicV4 checkout), its out/macos-<arch>
#        CODESIGN_IDENTITY  default "-" (ad-hoc); a real identity also gets the hardened runtime.
#                           The JVM updater swaps in only a bundle signed by the installed app's team.
#        YMUSIC_VERSION_NAME, YMUSIC_VERSION_CODE  as for the build (crates/ymusic/build.rs)
#        YMUSIC_MAC_APPLE_ID, YMUSIC_MAC_APPLE_PASSWORD, YMUSIC_MAC_TEAM_ID  notarization (--release)
#        CARGO_TARGET_DIR   default <workspace>/target
set -euo pipefail

DEV_BUNDLE_ID=io.ymusic.desktop.rust
RELEASE_BUNDLE_ID=io.ymusic.desktop
APP_NAME=YMusic

root=$(cd "$(dirname "$0")/.." && pwd)
build=1
profile=release
dmg=0
release=0
out=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --no-build) build=0 ;;
        --debug) profile=debug ;;
        --dmg) dmg=1 ;;
        --release) release=1; dmg=1 ;;
        --out) out=$2; shift ;;
        *) echo "usage: $0 [--no-build] [--debug] [--dmg] [--release] [--out <dir>]" >&2; exit 2 ;;
    esac
    shift
done

v4=$(cd "${YMUSIC_V4_DIR:-$root/..}" && pwd)
arch=$(uname -m)
ffmpeg_build=$(cd "${FFMPEG_BUILD_DIR:-$root/../../ffmpeg-build}" && pwd)
ffmpeg_root=$ffmpeg_build/out/macos-$arch
target_dir=${CARGO_TARGET_DIR:-$root/target}
out=${out:-$target_dir/bundle}
identity=${CODESIGN_IDENTITY:--}
bundle_id=$DEV_BUNDLE_ID
[[ $release == 1 ]] && bundle_id=$RELEASE_BUNDLE_ID

die() { echo "bundle-macos: $*" >&2; exit 1; }
[[ -f $ffmpeg_root/pack/ffmpeg ]] || die "no packed ffmpeg at $ffmpeg_root/pack/ffmpeg (set FFMPEG_BUILD_DIR)"
[[ -f $v4/desktopApp/ymusic.icns ]] || die "no icon at $v4/desktopApp/ymusic.icns (set YMUSIC_V4_DIR)"
[[ $release == 1 && $identity == - ]] && die "--release needs CODESIGN_IDENTITY (the JVM app's Developer ID)"
[[ $release == 1 && $profile == debug ]] && die "--release ships the release profile, not --debug"
[[ $release == 1 && ( -z ${YMUSIC_MAC_APPLE_ID:-} || -z ${YMUSIC_MAC_APPLE_PASSWORD:-} || -z ${YMUSIC_MAC_TEAM_ID:-} ) ]] &&
    die "--release needs YMUSIC_MAC_APPLE_ID, YMUSIC_MAC_APPLE_PASSWORD and YMUSIC_MAC_TEAM_ID to notarize"
# The JVM app's floor: its updater offers this build to every Mac the JVM app runs on.
min_os=$(sed -n 's/.*const val macosMin = "\([0-9.]*\)".*/\1/p' "$v4/buildSrc/src/main/kotlin/Dependencies.kt")
[[ -n $min_os ]] || die "no macosMin in $v4/buildSrc/src/main/kotlin/Dependencies.kt"

if [[ $build == 1 ]]; then
    cargo_profile=()
    [[ $profile == release ]] && cargo_profile=(--release --features production)
    # Line tables go into ymusic.dSYM, which Sentry symbolicates with; the binary keeps its symbols only.
    (cd "$root" && MACOSX_DEPLOYMENT_TARGET=$min_os YMUSIC_V4_DIR=$v4 FFMPEG_BUILD_DIR=$ffmpeg_build \
        CARGO_PROFILE_RELEASE_DEBUG=line-tables-only CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=packed \
        cargo build ${cargo_profile[@]+"${cargo_profile[@]}"} -p ymusic)
fi
binary=$target_dir/$profile/ymusic
[[ -f $binary ]] || die "no $profile binary at $binary"
rm -rf "$out/debug"
if [[ $profile == release ]]; then
    [[ -d $binary.dSYM ]] || die "no $binary.dSYM (built without CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=packed?)"
    mkdir -p "$out/debug"
    ditto "$binary.dSYM" "$out/debug/ymusic.dSYM"
fi
# The version line the binary reports (crates/ymusic/build.rs reads the same inputs).
version=${YMUSIC_VERSION_NAME:-$(sed -n 's/^ymusic\.versionName=//p' "$v4/gradle.properties" | tr -d '[:space:]')}
version_code=${YMUSIC_VERSION_CODE:-$(sed -n 's/^ymusic\.versionCode=//p' "$v4/gradle.properties" | tr -d '[:space:]')}
[[ -n $version && -n $version_code ]] || die "no version in $v4/gradle.properties"

app=$out/$APP_NAME.app
contents=$app/Contents
frameworks=$contents/Frameworks
rm -rf "$app"
mkdir -p "$contents/MacOS" "$contents/Resources" "$frameworks"
cp "$binary" "$contents/MacOS/YMusic"
cp "$ffmpeg_root/pack/ffmpeg" "$contents/MacOS/ffmpeg"
cp "$v4/desktopApp/ymusic.icns" "$contents/Resources/YMusic.icns"
# macOS 26+ draws CFBundleIconName from Assets.car; older ones the .icns.
[[ -f $v4/desktopApp/macos/Assets.car ]] && cp "$v4/desktopApp/macos/Assets.car" "$contents/Resources/Assets.car"
printf 'APPL????' > "$contents/PkgInfo"

# One file per library, named by its major version: ymusic links libav by its full version and
# ffmpeg by its major one, and two names for one library would load it twice.
for real in "$ffmpeg_root"/lib/lib*.dylib; do
    [[ -L $real ]] && continue
    file=$(basename "$real")
    base=${file%%.*}
    rest=${file#*.}
    cp "$real" "$frameworks/$base.${rest%%.*}.dylib"
done

rpaths() { otool -l "$1" | awk '/cmd LC_RPATH/ { getline; getline; print $2 }'; }

relink() {
    local macho=$1 dep name base candidates
    for dep in $(otool -L "$macho" | tail -n +2 | awk '{ print $1 }' | grep '^@rpath/' || true); do
        name=${dep#@rpath/}
        base=${name%%.*}
        candidates=("$frameworks/$base".*.dylib)
        [[ -f ${candidates[0]} ]] || die "$(basename "$macho") needs $name, not in $ffmpeg_root/lib"
        [[ $name == "$(basename "${candidates[0]}")" ]] ||
            install_name_tool -change "$dep" "@rpath/$(basename "${candidates[0]}")" "$macho" 2>/dev/null
    done
    local foreign
    foreign=$(otool -L "$macho" | tail -n +2 | awk '{ print $1 }' | grep -v -e '^@rpath/' -e '^/usr/lib/' -e '^/System/' || true)
    [[ -z $foreign ]] || die "$(basename "$macho") links outside the bundle and the OS: $foreign"
}

for dylib in "$frameworks"/*.dylib; do
    install_name_tool -id "@rpath/$(basename "$dylib")" "$dylib" 2>/dev/null
    relink "$dylib"
done
for executable in "$contents/MacOS/YMusic" "$contents/MacOS/ffmpeg"; do
    for rpath in $(rpaths "$executable"); do
        install_name_tool -delete_rpath "$rpath" "$executable" 2>/dev/null
    done
    install_name_tool -add_rpath @executable_path/../Frameworks "$executable" 2>/dev/null
    relink "$executable"
done

for macho in "$contents/MacOS/"* "$frameworks"/*.dylib; do
    needs=$(otool -l "$macho" | awk '/cmd LC_BUILD_VERSION/ { found = 1 } /cmd LC_VERSION_MIN_MACOSX/ { legacy = 1 }
        found && $1 == "minos" { print $2; exit } legacy && $1 == "version" { print $2; exit }')
    [[ -n $needs ]] || die "no minimum macOS in $macho"
    [[ $(printf '%s\n%s\n' "$needs" "$min_os" | sort -V | tail -n 1) == "$min_os" ]] ||
        die "$(basename "$macho") needs macOS $needs, above the JVM app's $min_os"
done

cat > "$contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>YMusic</string>
    <key>CFBundleIdentifier</key>
    <string>$bundle_id</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>$APP_NAME</string>
    <key>CFBundleDisplayName</key>
    <string>$APP_NAME</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>$version</string>
    <key>CFBundleVersion</key>
    <string>$version</string>
    <key>CFBundleIconFile</key>
    <string>YMusic</string>
    <key>CFBundleIconName</key>
    <string>YMusic</string>
    <key>LSMinimumSystemVersion</key>
    <string>$min_os</string>
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.music</string>
    <key>NSPrincipalClass</key>
    <string>NSApplication</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSSupportsAutomaticGraphicsSwitching</key>
    <true/>
    <key>CFBundleURLTypes</key>
    <array>
        <dict>
            <key>CFBundleURLName</key>
            <string>$bundle_id</string>
            <key>CFBundleURLSchemes</key>
            <array><string>ymusic</string></array>
        </dict>
    </array>
    <key>CFBundleDocumentTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeName</key>
            <string>Media file</string>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>LSHandlerRank</key>
            <string>Alternate</string>
            <key>LSItemContentTypes</key>
            <array>
                <string>public.mp3</string>
                <string>com.apple.m4a-audio</string>
                <string>public.mpeg-4-audio</string>
                <string>public.aac-audio</string>
                <string>org.xiph.flac</string>
                <string>com.microsoft.waveform-audio</string>
                <string>public.mpeg-4</string>
                <string>com.apple.m4v-video</string>
                <string>com.apple.quicktime-movie</string>
            </array>
        </dict>
        <dict>
            <key>CFBundleTypeName</key>
            <string>Media file</string>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>LSHandlerRank</key>
            <string>Alternate</string>
            <key>CFBundleTypeExtensions</key>
            <array>
                <string>m4b</string><string>ogg</string><string>oga</string><string>opus</string>
                <string>mka</string><string>webm</string><string>mkv</string>
            </array>
        </dict>
    </array>
</dict>
</plist>
PLIST
plutil -lint -s "$contents/Info.plist"

sign_flags=(--force --sign "$identity")
if [[ $identity == - ]]; then
    sign_flags+=(--timestamp=none)
else
    sign_flags+=(--timestamp --options runtime)
fi
# Inside out: nested code first, the bundle seal last.
for dylib in "$frameworks"/*.dylib; do
    codesign "${sign_flags[@]}" "$dylib"
done
codesign "${sign_flags[@]}" --identifier "$bundle_id.ffmpeg" "$contents/MacOS/ffmpeg"
codesign "${sign_flags[@]}" "$app"
codesign --verify --deep --strict "$app"

echo "$app ($version / $version_code, $bundle_id, macOS $min_os+): $(du -sh "$app" | cut -f1)"

[[ $dmg == 1 ]] || exit 0
# The release asset name (YMusicV4 release.yml): macos-arm64 / macos-intel.
dmg_arch=intel
[[ $arch == arm64 ]] && dmg_arch=arm64
dmg_file=$out/$APP_NAME-$version-macos-$dmg_arch.dmg
staging=$(mktemp -d "${TMPDIR:-/tmp}/ymusic-dmg.XXXXXX")
trap 'rm -rf "$staging"' EXIT
# One .app at the image root: the JVM updater copies out the first one it finds.
ditto "$app" "$staging/$APP_NAME.app"
ln -s /Applications "$staging/Applications"
rm -f "$dmg_file"
hdiutil create -quiet -volname "$APP_NAME" -srcfolder "$staging" -fs HFS+ -format UDZO -ov "$dmg_file"
if [[ $identity != - ]]; then
    codesign --force --sign "$identity" --timestamp "$dmg_file"
fi
if [[ $release == 1 ]]; then
    notary=(--apple-id "$YMUSIC_MAC_APPLE_ID" --password "$YMUSIC_MAC_APPLE_PASSWORD" --team-id "$YMUSIC_MAC_TEAM_ID")
    # A rejected submission can exit non-zero too; its id still leads to the log.
    submission=$(xcrun notarytool submit "$dmg_file" "${notary[@]}" --wait --output-format plist) || true
    submission_id=$(plutil -extract id raw -o - - <<<"$submission" 2>/dev/null) || die "notarytool submit failed: $submission"
    status=$(plutil -extract status raw -o - - <<<"$submission" 2>/dev/null) || status=unknown
    if [[ $status != Accepted ]]; then
        xcrun notarytool log "$submission_id" "${notary[@]}" >&2 || echo "bundle-macos: no log for $submission_id" >&2
        die "notarization $submission_id: $status"
    fi
    # Right after notarytool accepts, stapler can fail to fetch the fresh ticket (exit 68).
    for attempt in 1 2 3 4 5 6; do
        xcrun stapler staple "$dmg_file" && break
        [[ $attempt == 6 ]] && die "no notarization ticket to staple to $dmg_file"
        echo "bundle-macos: staple failed; retry $attempt in 30 s" >&2
        sleep 30
    done
    xcrun stapler validate "$dmg_file"
fi
echo "$dmg_file: $(du -sh "$dmg_file" | cut -f1)"
