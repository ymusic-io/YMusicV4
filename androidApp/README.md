# `:androidApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **What it is** — the Android product shell: plain AGP 9 application module booting the same shared `App` over ExoPlayer + MSE YouTube. The WebView is the login browser and the cookie store (`WebViewCookieJar` wraps `CookieManager`). Settings → Link devices scans a desktop QR (Play services code scanner, no CAMERA permission), confirms a matching code, unlocks with PIN/biometric, and POSTs the encrypted jar.
  - **`com.android.application` cannot combine with the KMP plugin since AGP 9** — plain layout `src/main/`, built-in Kotlin, `org.jetbrains.compose` plugin applied for dependency substitution.

- **Home-screen widgets** — `PlayerWidgets` (Small / Compact / Large / Square, plain `RemoteViews`): live from `player.status × queue` once the graph is up; with an empty queue they show the newest "Play again" session and Play resumes it (`PlaybackService.playOrResume`); the play button sends the explicit Play/Pause its icon shows, never a toggle. Blur, palette and rounded crops are baked into bitmaps (RemoteViews can't), and the last track + its art are snapshotted to `filesDir/widget` so a refresh in a dead process still renders. Buttons are foreground-service starts of `PlaybackService` (a tap may revive the process).

- **Folder pick** — the system tree picker (SAF `content://`). Where there is none (Android TV resolves it to `frameworkpackagestubs`, which only toasts): up to Android 10, `MainActivity.pickFolderByPath` asks for READ+WRITE_EXTERNAL_STORAGE and browses volume paths in `PathFolderPickerDialog`, yielding a `file://` folder; from Android 11, the YMusic Files helper ([android-saf](https://github.com/ymusic-io/android-saf), holds All files access) picks and returns an ordinary SAF tree. YMusic never downloads or installs it: `features.safHelperPackage` / `safHelperUrl` / `safHelperMinVersion` name it (built-in defaults `io.ymusic.android.saf`, the android-saf releases page, 1; the remote config may override them, `""` switches it off), and the user installs it from that link. `StorageCompanion` only checks it is installed and signed with YMusic's key.

- **compileSdk** — 36 (okhttp 5.4 demands it for app modules).

- **Not wired yet** — media store/SAF scanner.
