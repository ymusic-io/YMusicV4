#!/usr/bin/env bash
# Build + bundle the native iOS player-lab .app and run it in the iOS Simulator (iosSimulatorArm64).
#   ./build.sh              build, install, and launch (streams the app log for ~25s, then detaches)
#   ./build.sh --no-run     build + bundle only
# Requires an Apple Silicon Mac, Xcode, and an available iPhone simulator; there is no iosX64 target.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$HERE/build"
FW_DIR="$ROOT/player/build/bin/iosSimulatorArm64/debugFramework"
APP="$BUILD/PlayerLab.app"
BUNDLE_ID="com.ymusic.iosplayerlab"
TARGET="arm64-apple-ios15.0-simulator"
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"

echo "==> Building Player.framework (iosSimulatorArm64)..."
(cd "$ROOT" && ./gradlew :player:linkDebugFrameworkIosSimulatorArm64 --console=plain -q)
[ -d "$FW_DIR/Player.framework" ] || { echo "framework not found at $FW_DIR"; exit 1; }

# The WKWebView (YouTube) tab injects the built webview-js bundle; build it if missing.
ENGINE_JS="$ROOT/webview-js/dist/webview_ymusic.js"
if [ ! -f "$ENGINE_JS" ]; then
    echo "==> Building webview-js bundle..."
    (cd "$ROOT/webview-js" && { [ -d node_modules ] || npm install; } && npm run build:prod)
fi

# A short local sample for the AVFoundation tab (test pattern + tone); generated once via ffmpeg.
mkdir -p "$BUILD"
SAMPLE="$BUILD/sample.mp4"
if [ ! -f "$SAMPLE" ]; then
    if command -v ffmpeg >/dev/null 2>&1; then
        echo "==> Generating sample.mp4..."
        ffmpeg -y -f lavfi -i "testsrc=size=320x180:rate=15:duration=20" \
               -f lavfi -i "sine=frequency=440:duration=20" \
               -c:v h264 -pix_fmt yuv420p -c:a aac -shortest "$SAMPLE" >/dev/null 2>&1
    else
        echo "!! ffmpeg not found — the Local tab will report a missing sample."
    fi
fi

echo "==> Compiling SwiftUI app (iphonesimulator)..."
swiftc \
    -parse-as-library \
    -sdk "$SDK" \
    -target "$TARGET" \
    -F "$FW_DIR" \
    -framework Player \
    -Xlinker -rpath -Xlinker @executable_path/Frameworks \
    -o "$BUILD/PlayerLab" \
    "$HERE/Sources/PlayerLab.swift"

echo "==> Assembling $APP..."
rm -rf "$APP"
mkdir -p "$APP/Frameworks"
cp "$BUILD/PlayerLab" "$APP/PlayerLab"
cp -R "$FW_DIR/Player.framework" "$APP/Frameworks/"
cp "$ENGINE_JS" "$APP/webview_ymusic.js"
[ -f "$SAMPLE" ] && cp "$SAMPLE" "$APP/sample.mp4"

cat > "$APP/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>PlayerLab</string>
    <key>CFBundleIdentifier</key><string>com.ymusic.iosplayerlab</string>
    <key>CFBundleName</key><string>PlayerLab</string>
    <key>CFBundleDisplayName</key><string>YMusic iOS Lab</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>15.0</string>
    <key>UIDeviceFamily</key><array><integer>1</integer></array>
    <key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
    <key>DTPlatformName</key><string>iphonesimulator</string>
    <key>UILaunchScreen</key><dict/>
    <key>UISupportedInterfaceOrientations</key>
    <array><string>UIInterfaceOrientationPortrait</string></array>
</dict>
</plist>
PLIST

# Ad-hoc sign so the embedded framework loads in the simulator.
codesign --force --sign - --timestamp=none "$APP/Frameworks/Player.framework" >/dev/null 2>&1 || true
codesign --force --sign - --timestamp=none "$APP" >/dev/null 2>&1 || true

echo "==> Built $APP"
[ "${1:-}" = "--no-run" ] && exit 0

# Pick a booted simulator, else boot an available iPhone.
UDID="$(xcrun simctl list devices booted -j | /usr/bin/python3 -c 'import json,sys;d=json.load(sys.stdin)["devices"];print(next((x["udid"] for v in d.values() for x in v if x["state"]=="Booted"), ""))' 2>/dev/null || true)"
if [ -z "$UDID" ]; then
    UDID="$(xcrun simctl list devices available -j | /usr/bin/python3 -c 'import json,sys;d=json.load(sys.stdin)["devices"];print(next((x["udid"] for v in d.values() for x in v if "iPhone" in x["name"]), ""))')"
    echo "==> Booting simulator $UDID..."
    xcrun simctl boot "$UDID" 2>/dev/null || true
fi
open -a Simulator 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1 || true

echo "==> Installing + launching (bundle $BUNDLE_ID) on $UDID..."
xcrun simctl install "$UDID" "$APP"
echo "==> App log (Ctrl-C to stop; auto-detaches):"
xcrun simctl launch --console-pty "$UDID" "$BUNDLE_ID" || true
