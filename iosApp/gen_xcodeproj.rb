#!/usr/bin/env ruby
# Generates iosApp.xcodeproj — the iOS product shell that runs the shared Compose UI (:commonUi's
# YMusicApp framework) on a device. Gradle builds + embeds + signs the framework
# (:commonUi:embedAndSignAppleFrameworkForXcode, -PkmpTargets=all so iosArm64 is configured on this
# Intel host); Xcode compiles iosApp/iOSApp.swift, which hosts MainViewControllerKt.MainViewController.
#
#   ruby gen_xcodeproj.rb
require "xcodeproj"

HERE = File.dirname(File.expand_path(__FILE__))
proj_path = File.join(HERE, "iosApp.xcodeproj")
FileUtils.rm_rf(proj_path)
project = Xcodeproj::Project.new(proj_path)

target = project.new_target(:application, "iosApp", :ios, "15.0")

src_group = project.new_group("iosApp", "iosApp")
target.add_file_references([
  src_group.new_file("iOSApp.swift"),
  src_group.new_file("IosPip.swift"),
])
src_group.new_file("Info.plist")

target.add_resources([
  project.new_file("Assets.xcassets"),
])

gradle_phase = target.new_shell_script_build_phase("Build & embed YMusicApp.framework (Compose/Kotlin)")
gradle_phase.shell_script = <<~SH
  set -e
  export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
  if [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
    if [ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
      export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    elif [ -x "$HOME/.sdkman/candidates/java/current/bin/java" ]; then
      export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
    else
      export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
    fi
  fi
  cd "$SRCROOT/.."
  # Gradle daemon started from SSH cannot use the login keychain (errSecInternalComponent).
  ./gradlew --stop >/dev/null 2>&1 || true
  # Let this shell (Xcode's child) codesign; Gradle's codesign hits errSecInternalComponent.
  SIGN_ID="${EXPANDED_CODE_SIGN_IDENTITY:-}"
  unset EXPANDED_CODE_SIGN_IDENTITY
  # -PkmpTargets=all so iosArm64 / iosSimulatorArm64 are configured even on this Intel host.
  ./gradlew --no-daemon :iosAppKotlin:embedAndSignAppleFrameworkForXcode -PkmpTargets=all
  FW="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/YMusicApp.framework"
  if [ -n "$SIGN_ID" ] && [ "$SIGN_ID" != "-" ] && [ -d "$FW" ]; then
    /usr/bin/codesign --force --sign "$SIGN_ID" --timestamp=none "$FW"
  fi
SH
target.build_phases.unshift(target.build_phases.delete(gradle_phase))

# Last, after Info.plist is processed: the version name is gradle.properties' ymusic.versionName,
# shared with Android and desktop.
version_phase = target.new_shell_script_build_phase("Stamp version name from gradle.properties")
version_phase.shell_script = <<~'SH'
set -e
v=$(sed -n 's/^ymusic\.versionName=//p' "$SRCROOT/../gradle.properties")
[ -n "$v" ] || { echo "error: ymusic.versionName missing from gradle.properties"; exit 1; }
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $v" "$TARGET_BUILD_DIR/$INFOPLIST_PATH"
SH

target.build_configurations.each do |config|
  s = config.build_settings
  s["SWIFT_VERSION"] = "5.0"
  s["IPHONEOS_DEPLOYMENT_TARGET"] = "15.0"
  s["PRODUCT_BUNDLE_IDENTIFIER"] = "com.ymusic.iosapp"
  s["PRODUCT_NAME"] = "$(TARGET_NAME)"
  s["INFOPLIST_FILE"] = "iosApp/Info.plist"
  s["TARGETED_DEVICE_FAMILY"] = "1,2"
  s["CODE_SIGN_STYLE"] = "Automatic"
  # DEVELOPMENT_TEAM intentionally unset — pick your Team in Xcode's Signing tab.
  s["ENABLE_USER_SCRIPT_SANDBOXING"] = "NO"
  s["ONLY_ACTIVE_ARCH"] = "YES"
  s["ASSETCATALOG_COMPILER_APPICON_NAME"] = "AppIcon"
  s["ASSETCATALOG_COMPILER_GENERATE_ASSET_SYMBOLS"] = "NO"
  # Compose MP has no iosX64, so the simulator path is arm64-only (Apple-Silicon hosts).
  s["FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]"] = "$(SRCROOT)/../commonUi/build/bin/iosArm64/debugFramework"
  s["FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]"] = "$(SRCROOT)/../commonUi/build/bin/iosSimulatorArm64/debugFramework"
  s["OTHER_LDFLAGS"] = "-framework YMusicApp"
end

project.save
puts "wrote #{proj_path}"
