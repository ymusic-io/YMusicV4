//! One full Library rescan once the probe moved to the ffmpeg binary, so items scanned by earlier
//! builds are probed again by it.

use std::error::Error;
use std::path::Path;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use ym_settings::SettingsStore;
use ym_store::MediaStore;

use crate::graph::AppGraph;

const RESCAN_DONE_KEY: &str = "rescan_rust_ffmpeg_sub_process";
const START_DELAY: Duration = Duration::from_millis(1000);

/// Fire and forget, once per install: the flag is set before the scan starts. The check reads the
/// in-memory settings, so a launch that is done spawns nothing.
pub fn schedule(graph: &AppGraph) {
    let log = graph.log.fork("library-rescan");
    match graph.settings.boolean(RESCAN_DONE_KEY, false) {
        Ok(true) => return,
        Ok(false) => {}
        Err(error) => return log.warn("R90043 the one-time Library rescan failed", Some(&*error)),
    }
    let (settings, media) = (graph.settings.clone(), graph.media.clone());
    graph.tokio.spawn(async move {
        tokio::time::sleep(START_DELAY).await;
        tokio::task::spawn_blocking(move || {
            if let Err(error) = rescan(settings.as_ref(), &media) {
                log.warn("R90043 the one-time Library rescan failed", Some(&*error));
            }
        });
    });
}

fn rescan(settings: &dyn SettingsStore, media: &MediaStore) -> Result<(), Box<dyn Error + Send + Sync>> {
    settings.put_raw(RESCAN_DONE_KEY, "true".to_owned())?;
    media.invalidate_probes()?;
    let cancel = AtomicBool::new(false);
    for root in media.root_buckets()? {
        ym_store::scan_folder(media, ym_ffmpeg::FfmpegMetadataGetter::shared(), Path::new(&root.uri), &cancel)?;
    }
    Ok(())
}
