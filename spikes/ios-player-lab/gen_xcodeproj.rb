#!/usr/bin/env ruby
# Generates PlayerLab.xcodeproj — a device-installable (arm64) iOS app over :player's Player.framework.
# Gradle builds + embeds + signs the framework (embedAndSignAppleFrameworkForXcode, -PkmpTargets=all so
# iosArm64 is configured on this Intel host); Xcode compiles Sources/PlayerLab.swift over it. Open in
# Xcode, pick your Team under Signing & Capabilities, connect+trust your device, and Run.
#
#   gem install xcodeproj   # (already present here: 1.22.0)
#   ruby gen_xcodeproj.rb
require "xcodeproj"

HERE = File.dirname(File.expand_path(__FILE__))
proj_path = File.join(HERE, "PlayerLab.xcodeproj")
FileUtils.rm_rf(proj_path)
project = Xcodeproj::Project.new(proj_path)

target = project.new_target(:application, "PlayerLab", :ios, "15.0")

# Source + resources — files are named relative to their group's own path.
src_group = project.new_group("Sources", "Sources")
target.add_file_references([src_group.new_file("PlayerLab.swift")])

res_group = project.new_group("Resources", "Resources")
res_refs = ["sample.mp4", "webview_ymusic.js"].map { |p| res_group.new_file(p) }
target.add_resources(res_refs)

# Info.plist (audio background mode so playback survives lock/background — a music app wants this).
project.new_group("Supporting", "Supporting").new_file("Info.plist")

# Build the Kotlin framework for the SDK/arch Xcode is targeting, then embed+sign it — BEFORE compile.
gradle_phase = target.new_shell_script_build_phase("Build & embed Player.framework (Kotlin)")
gradle_phase.shell_script = <<~SH
  set -e
  export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
  cd "$SRCROOT/../.."
  # -PkmpTargets=all so iosArm64 (device) is configured even on this Intel host.
  ./gradlew :player:embedAndSignAppleFrameworkForXcode -PkmpTargets=all
SH
# Move the Kotlin phase to run first (framework must exist before Swift links against it).
target.build_phases.unshift(target.build_phases.delete(gradle_phase))

target.build_configurations.each do |config|
  s = config.build_settings
  s["SWIFT_VERSION"] = "5.0"
  s["IPHONEOS_DEPLOYMENT_TARGET"] = "15.0"
  s["PRODUCT_BUNDLE_IDENTIFIER"] = "com.ymusic.iosplayerlab"
  s["PRODUCT_NAME"] = "$(TARGET_NAME)"
  s["INFOPLIST_FILE"] = "Supporting/Info.plist"
  s["TARGETED_DEVICE_FAMILY"] = "1,2" # iPhone + iPad
  s["CODE_SIGN_STYLE"] = "Automatic"
  # DEVELOPMENT_TEAM intentionally unset — you pick your Team in Xcode's Signing tab.
  s["ENABLE_USER_SCRIPT_SANDBOXING"] = "NO" # let the gradle build phase touch the repo
  s["ONLY_ACTIVE_ARCH"] = "YES"
  s["ASSETCATALOG_COMPILER_GENERATE_ASSET_SYMBOLS"] = "NO"
  # The Kotlin framework lives where Gradle links it, per SDK (arm64 device / arm64 simulator).
  s["FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]"] = "$(SRCROOT)/../../player/build/bin/iosArm64/debugFramework"
  s["FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]"] = "$(SRCROOT)/../../player/build/bin/iosSimulatorArm64/debugFramework"
  s["OTHER_LDFLAGS"] = "-framework Player"
end

project.save
puts "wrote #{proj_path}"
