#!/usr/bin/env bash
# Build + run the mixed-content probe in the iOS Simulator, streaming its [mixed] log.
# Intel host → x86_64 simulator (plain swiftc, no Kotlin framework). Usage: ./run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HERE/build"
APP="$BUILD/Probe.app"
BUNDLE_ID="com.ymusic.msgprobe"
TARGET="x86_64-apple-ios15.0-simulator"
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"

mkdir -p "$BUILD"


echo "==> Compiling Probe.swift..."
swiftc -parse-as-library -sdk "$SDK" -target "$TARGET" -o "$BUILD/Probe" "$HERE/Probe.swift"

echo "==> Assembling $APP..."
rm -rf "$APP"; mkdir -p "$APP"
cp "$BUILD/Probe" "$APP/Probe"
cat > "$APP/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>Probe</string>
    <key>CFBundleIdentifier</key><string>com.ymusic.msgprobe</string>
    <key>CFBundleName</key><string>Probe</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>15.0</string>
    <key>UIDeviceFamily</key><array><integer>1</integer></array>
    <key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
    <key>DTPlatformName</key><string>iphonesimulator</string>
    <key>UILaunchScreen</key><dict/>
    <!-- ATS: allow loopback so the connection itself isn't the blocker — isolating the mixed-content policy. -->
    <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict>
</plist>
PLIST
codesign --force --sign - --timestamp=none "$APP" >/dev/null 2>&1 || true

UDID="$(xcrun simctl list devices booted -j | /usr/bin/python3 -c 'import json,sys;d=json.load(sys.stdin)["devices"];print(next((x["udid"] for v in d.values() for x in v if x["state"]=="Booted"), ""))' 2>/dev/null || true)"
if [ -z "$UDID" ]; then
    UDID="$(xcrun simctl list devices available -j | /usr/bin/python3 -c 'import json,sys;d=json.load(sys.stdin)["devices"];print(next((x["udid"] for v in d.values() for x in v if "iPhone" in x["name"]), ""))')"
    echo "==> Booting simulator $UDID..."
    xcrun simctl boot "$UDID" 2>/dev/null || true
fi
xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1 || true

echo "==> Installing + launching $BUNDLE_ID on $UDID..."
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch --console-pty "$UDID" "$BUNDLE_ID" || true
