use std::path::PathBuf;

const SOURCES: &[&str] = &[
    "quickjs/quickjs.c",
    "quickjs/cutils.c",
    "quickjs/libregexp.c",
    "quickjs/libunicode.c",
    "quickjs/dtoa.c",
    "quickjs_native.c",
    "quickjs_json.c",
    "quickjs_pipe.c",
    "quickjs_hostfn.c",
    "quickjs_web.c",
    "quickjs_mse.c",
];

fn main() {
    println!("cargo:rerun-if-env-changed=YMUSIC_V4_DIR");
    let v4 = PathBuf::from(std::env::var("YMUSIC_V4_DIR").expect("YMUSIC_V4_DIR (set in .cargo/config.toml)"));
    let native = v4.join("quickjs/native");
    assert!(native.is_dir(), "no quickjs sources at {}", native.display());

    let mut build = cc::Build::new();
    build
        .include(native.join("quickjs"))
        .include(&native)
        .opt_level(2)
        .warnings(false)
        .flag_if_supported("-w")
        .flag_if_supported("-funsigned-char")
        .define("CONFIG_VERSION", "\"2026-06-04\"")
        .define("_GNU_SOURCE", None);
    let mut sources: Vec<PathBuf> = SOURCES.iter().map(|s| native.join(s)).collect();
    let bg_trace = native.join("quickjs_bg_trace.c");
    if bg_trace.is_file() {
        sources.push(bg_trace);
    }
    for src in &sources {
        println!("cargo:rerun-if-changed={}", src.display());
        build.file(src);
    }
    for header in std::fs::read_dir(&native).unwrap().flatten() {
        if header.path().extension().is_some_and(|ext| ext == "h") {
            println!("cargo:rerun-if-changed={}", header.path().display());
        }
    }
    build.compile("qjscore");
}
