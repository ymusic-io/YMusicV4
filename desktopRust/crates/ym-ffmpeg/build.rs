//! Exports the ffmpeg-build checkout's packed ffmpeg as `YM_FFMPEG_BIN`, the binary a dev build
//! runs when none sits beside the executable.

use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=FFMPEG_BUILD_DIR");
    let Ok(ffmpeg_build) = std::env::var("FFMPEG_BUILD_DIR") else { return };
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap();
    let arch = if std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("aarch64") { "arm64" } else { "x86_64" };
    let platform = match target_os.as_str() {
        "macos" => "macos",
        "windows" => "windows",
        _ => "linux",
    };
    let pack = PathBuf::from(ffmpeg_build).join("out").join(format!("{platform}-{arch}")).join("pack");
    println!("cargo:rerun-if-changed={}", pack.display());
    let binary = pack.join(if target_os == "windows" { "ffmpeg.exe" } else { "ffmpeg" });
    if binary.is_file() {
        println!("cargo:rustc-env=YM_FFMPEG_BIN={}", binary.display());
    }
}
