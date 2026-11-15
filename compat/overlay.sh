#!/bin/bash
# Stage the compat test into a shipped tag's checkout: BundleCompatTest + today's YouTube fixtures
# into its :dynamic-engine jvmTest, today's bundles and the tag's own versionCodes into build/compat.
# Usage: overlay.sh <tag-checkout> <dir with engine.js + player.js>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
checkout=$1
bundles=$2

test_dir="$checkout/dynamic-engine/src/jvmTest/kotlin/io/ymusic/dynamicengine/compat"
mkdir -p "$test_dir"
cp "$here/BundleCompatTest.kt" "$test_dir/"
sed 's/^package io\.ymusic\.dynamicengine\.yt$/package io.ymusic.dynamicengine.compat/' \
    "$root/dynamic-engine/src/commonTest/kotlin/io/ymusic/dynamicengine/yt/YtFixtures.kt" > "$test_dir/CompatFixtures.kt"

stage="$checkout/dynamic-engine/build/compat"
mkdir -p "$stage"
cp "$bundles/engine.js" "$bundles/player.js" "$stage/"
{
    sed -nE 's/^ymusic\.versionCode=([0-9]+).*/\1/p' "$checkout/gradle.properties"
    # Tags before ymusic.versionCode kept one per platform.
    sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$checkout/androidApp/build.gradle.kts"
    sed -nE 's/^val desktopVersionCode = ([0-9]+).*/\1/p' "$checkout/desktopApp/build.gradle.kts"
    sed -nE 's/.*<key>CFBundleVersion<\/key><string>([0-9]+)<\/string>.*/\1/p' "$checkout/iosApp/iosApp/Info.plist"
} | sort -un > "$stage/app-versions"
[ -s "$stage/app-versions" ] || { echo "no versionCode found in $checkout" >&2; exit 1; }
