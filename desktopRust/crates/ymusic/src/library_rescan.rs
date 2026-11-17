//! One full Library rescan once the probe moved to the ffmpeg binary, so items scanned by earlier
//! builds are probed again by it.

use std::error::Error;
use std::path::Path;
use std::sync::atomic::Ordering;
use std::time::Duration;

use ym_common::log::Logger;
use ym_settings::SettingsStore;
use ym_store::MediaStore;

use crate::graph::AppGraph;

const RESCAN_DONE_KEY: &str = "rescan_rust_ffmpeg_sub_process";
/// Set once every item is marked for a new probe, so a resumed rescan probes only what is left.
const PROBES_INVALIDATED_KEY: &str = "rescan_rust_ffmpeg_sub_process_invalidated";
const START_DELAY: Duration = Duration::from_millis(1000);

/// Fire and forget, once per install: a launch that quits before every Library folder is scanned
/// resumes at the next one. The check reads the in-memory settings, so a launch that is done
/// spawns nothing.
pub fn schedule(graph: &AppGraph) {
    let log = graph.log.fork("library-rescan");
    match graph.settings.boolean(RESCAN_DONE_KEY, false) {
        Ok(true) => return,
        Ok(false) => {}
        Err(error) => {
            return log.warn("R90044 the one-time Library rescan did not start: its flag is unreadable", Some(&*error));
        }
    }
    let (settings, media) = (graph.settings.clone(), graph.media.clone());
    graph.tokio.spawn(async move {
        tokio::time::sleep(START_DELAY).await;
        tokio::task::spawn_blocking(move || {
            if let Err(error) = rescan(settings.as_ref(), &media, &log) {
                log.warn("R90043 the one-time Library rescan failed", Some(&*error));
            }
        });
    });
}

fn rescan(settings: &dyn SettingsStore, media: &MediaStore, log: &Logger) -> Result<(), Box<dyn Error + Send + Sync>> {
    if !settings.boolean(PROBES_INVALIDATED_KEY, false)? {
        media.invalidate_probes()?;
        settings.put_raw(PROBES_INVALIDATED_KEY, "true".to_owned())?;
    }
    for root in media.root_buckets()? {
        let path = Path::new(&root.uri);
        // scan_folder removes a gone root; an unmounted drive's items stay marked for a later scan.
        if !path.is_dir() {
            log.info(&format!("rescan skipped a Library folder that is not there: {}", root.uri));
            continue;
        }
        let report = ym_store::scan_folder(media, ym_ffmpeg::FfmpegMetadataGetter::shared(), path, &crate::QUITTING)?;
        if let Some(first) = report.errors.first() {
            log.warn(
                &format!(
                    "R90045 the one-time Library rescan could not index {} files under {}, first {}: {}",
                    report.errors.len(),
                    root.uri,
                    first.path.display(),
                    first.message
                ),
                None,
            );
        }
        if crate::QUITTING.load(Ordering::Relaxed) {
            return Ok(());
        }
    }
    settings.put_raw(RESCAN_DONE_KEY, "true".to_owned())?;
    Ok(())
}
