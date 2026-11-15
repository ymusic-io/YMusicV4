//! Where a download's bytes live: per-task scratch files in app data (ffmpeg needs real paths, and
//! a resume continues from a scratch's length), then one publish into the user's folder.

use std::fs::{self, File, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};

use crate::error::{DownloadError, is_disk_full};
use crate::names::free_name;

pub struct DownloadStorage {
    scratch_dir: PathBuf,
}

impl DownloadStorage {
    pub fn new(scratch_dir: PathBuf) -> Self {
        Self { scratch_dir }
    }

    pub fn task_dir(&self, task_id: &str) -> PathBuf {
        self.scratch_dir.join(task_id)
    }

    /// A scratch file of the task, its folder created; stable per (task, name) so a resume
    /// continues into the same bytes.
    pub fn scratch(&self, task_id: &str, name: &str) -> Result<PathBuf, DownloadError> {
        let dir = self.task_dir(task_id);
        fs::create_dir_all(&dir).map_err(|error| DownloadError::file("R70016", "create scratch folder", error))?;
        Ok(dir.join(name))
    }

    /// Drops every file of the task at once; absent is fine.
    pub fn delete_scratch(&self, task_id: &str) -> io::Result<()> {
        match fs::remove_dir_all(self.task_dir(task_id)) {
            Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
            _ => Ok(()),
        }
    }

    /// The task ids that own a scratch folder, to prune those whose task is gone.
    pub fn scratch_task_ids(&self) -> io::Result<Vec<String>> {
        match fs::read_dir(&self.scratch_dir) {
            Ok(entries) => {
                entries.map(|entry| entry.map(|entry| entry.file_name().to_string_lossy().into_owned())).collect()
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(Vec::new()),
            Err(error) => Err(error),
        }
    }

    /// Moves the finished scratch into `folder` under the next free name and returns the file's
    /// `file://` URI. Blocking, and not to be interrupted: the task completes right after it.
    pub fn publish(&self, scratch: &Path, file_name: &str, folder: &Path) -> Result<String, DownloadError> {
        fs::create_dir_all(folder).map_err(DownloadError::FolderUnavailable)?;
        let destination = folder.join(free_name(file_name, |candidate| folder.join(candidate).exists()));
        // The task is marked complete right after: its bytes must be on disk before the rename, or a
        // power loss can keep the rename and lose the data.
        sync(scratch).map_err(|error| DownloadError::file("R70017", "sync the finished file", error))?;
        if let Err(move_error) = fs::rename(scratch, &destination) {
            // A rename across volumes can't work; anything else will fail the copy too.
            if let Err(copy_error) = copy_synced(scratch, &destination) {
                let reached = destination.exists();
                // A partial copy left behind would stay: a retry publishes under the next free name.
                let _ = fs::remove_file(&destination);
                if reached && is_disk_full(&copy_error) {
                    return Err(DownloadError::DiskFull(copy_error));
                }
                if copy_error.kind() == io::ErrorKind::PermissionDenied || !folder.exists() {
                    return Err(DownloadError::FolderUnavailable(copy_error));
                }
                return Err(DownloadError::technical(
                    "R70018",
                    format!("publish {} into {}: {copy_error} (rename: {move_error})", file_name, folder.display()),
                ));
            }
            fs::remove_file(scratch)
                .map_err(|error| DownloadError::file("R70019", "drop the copied scratch", error))?;
        }
        Ok(ym_common::file_uri::to_file_uri(&destination.to_string_lossy()))
    }
}

fn sync(path: &Path) -> io::Result<()> {
    // Windows' FlushFileBuffers needs a handle with write access; fsync takes any descriptor.
    OpenOptions::new().write(true).open(path)?.sync_all()
}

fn copy_synced(source: &Path, destination: &Path) -> io::Result<()> {
    let mut input = File::open(source)?;
    let mut output = OpenOptions::new().write(true).create_new(true).open(destination)?;
    io::copy(&mut input, &mut output)?;
    output.sync_all()
}
