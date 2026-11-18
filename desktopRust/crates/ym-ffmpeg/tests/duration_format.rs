//! The packed ffmpeg's `Duration:` line, which the Library's durations and playlist codes
//! (`@size:seconds`) are read from: it prints centiseconds, rounding half up (dump.c adds 5 ms before
//! truncating). Every case sits on a rounding edge, so a newer ffmpeg that formats or rounds otherwise
//! fails here before it reaches a build.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

use ym_ffmpeg::{FfmpegMetadataGetter, find_binary};

struct Case {
    sample_rate: u32,
    samples: u32,
    printed: &'static str,
    parsed_ms: i64,
}

const CASES: &[Case] = &[
    Case { sample_rate: 48_000, samples: 143_712, printed: "00:00:02.99", parsed_ms: 2_990 }, // 2.994 s
    Case { sample_rate: 48_000, samples: 143_760, printed: "00:00:03.00", parsed_ms: 3_000 }, // 2.995 s
    Case { sample_rate: 48_000, samples: 143_808, printed: "00:00:03.00", parsed_ms: 3_000 }, // 2.996 s
    Case { sample_rate: 48_000, samples: 143_952, printed: "00:00:03.00", parsed_ms: 3_000 }, // 2.999 s
    Case { sample_rate: 48_000, samples: 144_000, printed: "00:00:03.00", parsed_ms: 3_000 }, // 3.000 s
    Case { sample_rate: 48_000, samples: 144_192, printed: "00:00:03.00", parsed_ms: 3_000 }, // 3.004 s
    Case { sample_rate: 48_000, samples: 144_240, printed: "00:00:03.01", parsed_ms: 3_010 }, // 3.005 s
    Case { sample_rate: 8_000, samples: 479_960, printed: "00:01:00.00", parsed_ms: 60_000 }, // 59.995 s
    Case { sample_rate: 8_000, samples: 488_032, printed: "00:01:01.00", parsed_ms: 61_000 }, // 61.004 s
    Case { sample_rate: 8_000, samples: 28_799_960, printed: "01:00:00.00", parsed_ms: 3_600_000 }, // 3599.995 s
];

/// Mono 8-bit PCM: the WAV demuxer's duration is exactly `samples / sample_rate`.
fn write_wav(path: &Path, sample_rate: u32, samples: u32) {
    let mut out = BufWriter::new(File::create(path).unwrap());
    out.write_all(b"RIFF").unwrap();
    out.write_all(&(36 + samples).to_le_bytes()).unwrap();
    out.write_all(b"WAVEfmt ").unwrap();
    out.write_all(&16u32.to_le_bytes()).unwrap();
    out.write_all(&1u16.to_le_bytes()).unwrap(); // PCM
    out.write_all(&1u16.to_le_bytes()).unwrap(); // mono
    out.write_all(&sample_rate.to_le_bytes()).unwrap();
    out.write_all(&sample_rate.to_le_bytes()).unwrap(); // byte rate
    out.write_all(&1u16.to_le_bytes()).unwrap(); // block align
    out.write_all(&8u16.to_le_bytes()).unwrap(); // bits per sample
    out.write_all(b"data").unwrap();
    out.write_all(&samples.to_le_bytes()).unwrap();
    let silence = vec![0x80u8; 64 * 1024];
    let mut left = samples as usize;
    while left > 0 {
        let chunk = left.min(silence.len());
        out.write_all(&silence[..chunk]).unwrap();
        left -= chunk;
    }
    out.flush().unwrap();
}

fn printed_duration(binary: &Path, file: &Path) -> String {
    let mut spec = std::ffi::OsString::from("file:");
    spec.push(file.as_os_str());
    let output = Command::new(binary).arg("-hide_banner").arg("-i").arg(spec).output().unwrap();
    let stderr = String::from_utf8_lossy(&output.stderr);
    let line = stderr.lines().find(|line| line.trim_start().starts_with("Duration:"));
    let line = line.unwrap_or_else(|| panic!("no Duration line for {}:\n{stderr}", file.display()));
    line.trim_start()["Duration:".len()..].trim_start().split(',').next().unwrap().to_owned()
}

#[test]
fn ffmpeg_prints_centiseconds_rounded_half_up() {
    let binary = find_binary()
        .expect("no ffmpeg binary: set YMUSIC_FFMPEG, or build with FFMPEG_BUILD_DIR pointing at a built ffmpeg-build");
    let getter = FfmpegMetadataGetter::new(Some(binary.clone()));
    let dir = std::env::temp_dir().join(format!("ym-ffmpeg-duration-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut failures = Vec::new();
    for case in CASES {
        let file: PathBuf = dir.join(format!("{}-{}.wav", case.sample_rate, case.samples));
        write_wav(&file, case.sample_rate, case.samples);
        let printed = printed_duration(&binary, &file);
        let parsed = getter.get_metadata(&file).map(|metadata| metadata.container.duration_ms);
        if printed != case.printed || !matches!(parsed, Ok(Some(ms)) if ms == case.parsed_ms) {
            failures.push(format!(
                "{} samples at {} Hz: printed {printed} (want {}), parsed {parsed:?} (want {} ms)",
                case.samples, case.sample_rate, case.printed, case.parsed_ms
            ));
        }
        std::fs::remove_file(&file).unwrap();
    }
    std::fs::remove_dir_all(&dir).unwrap();
    assert!(failures.is_empty(), "ffmpeg's Duration line changed:\n{}", failures.join("\n"));
}
