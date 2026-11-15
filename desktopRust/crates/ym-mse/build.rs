//! Builds YMusicV4's `:mse` C host (MSE over ffmpeg, the audio DAC, video decode + presenters)
//! against the shared libav in `$FFMPEG_BUILD_DIR/out/<slug>`. Exports the lib dir as
//! `DEP_MSEHOST_FFMPEG_LIB` so executables can add it as an rpath.

use std::path::PathBuf;

const COMMON_C: &[&str] = &[
    "mse_host.c",
    "mse_ffmpeg.c",
    "mse_caps.c",
    "mse_vt.c",
    "audio_fx.c",
    "vpresent.c",
    "vpresent_cpu.c",
    "vpipe.c",
    "file_av.c",
];
const APPLE_C: &[&str] = &["mse_vt_blit.c", "vpresent_vt.c", "vpipe_hw_vt.c"];
// libpulse-simple is dlopen'd by aq_pulse.c; only its headers are needed at build time.
// mse_hwdev_vk.c and vpresent_frames.c take the Vulkan headers (libvulkan-dev).
const LINUX_C: &[&str] = &["aq_pulse.c", "mse_hwdev.c", "mse_hwdev_vk.c", "vpipe_hw_linux.c", "vpresent_frames.c"];
const WINDOWS_C: &[&str] = &["mse_d3d11.c", "mse_d3d11_dec.c", "vpresent_d3d11.c", "vpipe_hw_d3d11.c", "aq_winmm.c"];
// d3dcompiler compiles the NV12 shader at runtime, dxguid has the IID_* symbols, winmm is waveOut*.
const WINDOWS_LIBS: &[&str] = &["d3d11", "dxgi", "d3dcompiler", "dxguid", "winmm"];
const APPLE_OBJC: &[&str] = &["mse_vt_layer.m", "mse_vt_dec.m"];
const APPLE_FRAMEWORKS: &[&str] = &[
    "AudioToolbox",
    "CoreAudio",
    "VideoToolbox",
    "CoreMedia",
    "CoreFoundation",
    "CoreVideo",
    "AVFoundation",
    "QuartzCore",
];
const LIBAV: &[&str] = &["avformat", "avcodec", "swscale", "swresample", "avutil"];

fn main() {
    println!("cargo:rerun-if-env-changed=YMUSIC_V4_DIR");
    println!("cargo:rerun-if-env-changed=FFMPEG_BUILD_DIR");
    let v4 = PathBuf::from(std::env::var("YMUSIC_V4_DIR").expect("YMUSIC_V4_DIR (set in .cargo/config.toml)"));
    let native = v4.join("mse/native");
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap();
    let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap();
    let arch = if target_arch == "aarch64" { "arm64" } else { "x86_64" };
    let slug = match target_os.as_str() {
        "macos" => format!("macos-{arch}"),
        "linux" => format!("linux-{arch}"),
        "windows" => format!("windows-{arch}"),
        other => panic!("ym-mse builds for macOS, Linux and Windows only, not {other}"),
    };
    let apple = target_os == "macos";
    let windows = target_os == "windows";
    let ffmpeg_build =
        PathBuf::from(std::env::var("FFMPEG_BUILD_DIR").expect("FFMPEG_BUILD_DIR (set in .cargo/config.toml)"));
    let ffmpeg = ffmpeg_build.join("out").join(&slug);
    assert!(
        ffmpeg.join("include").is_dir(),
        "no ffmpeg build at {} (run scripts/build-desktop.sh in ffmpeg-build, or set FFMPEG_BUILD_DIR)",
        ffmpeg.display()
    );

    let production = if std::env::var("PROFILE").as_deref() == Ok("release") { "1" } else { "0" };
    let mut c = cc::Build::new();
    c.include(&native)
        .include(v4.join("quickjs/native"))
        .include(ffmpeg.join("include"))
        .opt_level(2)
        .warnings(false)
        .flag_if_supported("-w")
        .define("PRODUCTION", production);
    let platform_c = match target_os.as_str() {
        "macos" => APPLE_C,
        "windows" => WINDOWS_C,
        _ => LINUX_C,
    };
    if windows {
        c.define("MSE_WIN_HW", "1");
    }
    for source in COMMON_C.iter().chain(platform_c) {
        c.file(native.join(source));
    }
    let glue: &[&str] = if apple { &["native/vt_overlay_stub.c", "native/ym_probe.c"] } else { &["native/ym_probe.c"] };
    for glue in glue {
        c.file(glue);
        println!("cargo:rerun-if-changed={glue}");
    }
    c.compile("msehost_c");
    for entry in std::fs::read_dir(&native).unwrap().flatten() {
        println!("cargo:rerun-if-changed={}", entry.path().display());
    }

    let lib = ffmpeg.join("lib");
    println!("cargo:rustc-link-search=native={}", lib.display());
    for name in LIBAV {
        println!("cargo:rustc-link-lib=dylib={name}");
    }
    println!("cargo:ffmpeg_lib={}", lib.display());
    // The packed ffmpeg executable (rpath to its own libav) the download mux runs.
    let packed = ffmpeg.join("pack").join(if windows { "ffmpeg.exe" } else { "ffmpeg" });
    if packed.is_file() {
        println!("cargo:ffmpeg_bin={}", packed.display());
    }
    // Windows has no rpath: the executable's build copies these DLLs beside itself.
    if windows {
        println!("cargo:ffmpeg_dlls={}", ffmpeg.join("pack").display());
    }
    if apple {
        link_apple(&native, production);
    } else if windows {
        for name in WINDOWS_LIBS {
            println!("cargo:rustc-link-lib=dylib={name}");
        }
        // The MinGW runtime the C threads on; ffmpeg's DLLs import the same libwinpthread-1.dll.
        println!("cargo:rustc-link-lib=dylib=winpthread");
    } else {
        for name in ["dl", "m", "pthread"] {
            println!("cargo:rustc-link-lib=dylib={name}");
        }
    }
}

fn link_apple(native: &std::path::Path, production: &str) {
    let mut objc = cc::Build::new();
    objc.include(native)
        .opt_level(2)
        .warnings(false)
        .flag("-fobjc-arc")
        .flag_if_supported("-w")
        .define("PRODUCTION", production);
    for source in APPLE_OBJC {
        objc.file(native.join(source));
    }
    objc.compile("msehost_objc");
    // rustc links with -nodefaultlibs, dropping clang's runtime, which `@available` checks call into.
    let probe = std::process::Command::new(objc.get_compiler().path())
        .arg("-print-file-name=libclang_rt.osx.a")
        .output()
        .expect("run the C compiler for its runtime library");
    let runtime = PathBuf::from(String::from_utf8_lossy(&probe.stdout).trim());
    assert!(runtime.is_file(), "no clang runtime at {} (libclang_rt.osx.a)", runtime.display());
    println!("cargo:rustc-link-search=native={}", runtime.parent().unwrap().display());
    println!("cargo:rustc-link-lib=static:-bundle=clang_rt.osx");
    for framework in APPLE_FRAMEWORKS {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
    for name in ["z", "bz2", "iconv"] {
        println!("cargo:rustc-link-lib=dylib={name}");
    }
}
