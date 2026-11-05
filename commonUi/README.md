# `:commonUi`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

The shared Compose Multiplatform app UI (jvm + android; iOS later).

- **Screens** — drawer navigation (Discover/YT Music/Library/Downloads/Settings); Discover feed tabs + search
  (live suggestions, paged results) over `:dynamic-engine`'s `yt` task methods; LibraryScreen over `:media-store`
  flows; the persistent mini-player + full Now Playing (transport, sleep timer, capability selectors, queue).

- **Now Playing layouts** — a tall window stacks video, controls and queue; a wide one puts the queue in a side
  pane, but only from `SideBySideMinWidth`, below which the pane would out-size the video and the layout stacks
  (video height capped so the list stays usable).

- **SponsorBlock seam** — `ymusic.ui.sponsorblock`: `SponsorBlockApi` + `MockSponsorBlockApi` +
  SQLite cache/whitelist (`SponsorBlockStore`) + `SponsorBlockService` + `SponsorBlockCoordinator`
  (queue videoId → `Player.setSkipSegments`). The mock returns a 0–10s intro and a 60–75s mid-roll;
  swap `AppGraph`'s `sponsorBlockApi` for the real `/sponsorBlock/getSkipSegments` client when that
  lands. Enable is `AppSettings.sponsorBlockEnabled` (Settings row); per-video whitelist is the Now
  Playing toggle (hidden when there are no segments). Seek bars draw the ranges on `SlimSeekBar`.

- **AppGraph** — shells construct `AppGraph` (engines + `Player` facade + optional mediaStore/scan callback + the
  platform `playerSurface(modifier)` composable, which hosts the one shared video container the shell handed the
  engines) — nothing platform-specific in common. Desktop YouTube sign-in is a shell screen (`cookieSignIn`: QR /
  DevTools paste in `:desktopApp`). Mobile exposes Settings → Link devices (`exportSessionCookies` + `scanQrCode`
  + `confirmDeviceOwner`) so the phone can confirm a matching code, unlock, encrypt the jar, and POST it to that
  inbox. The desktop deletes the inbox as soon as it receives the payload.

- **Outside links** — shells hand every url they are opened with to `IncomingLinks.open` (Android VIEW intents for
  youtube.com / youtu.be / ymusic.io / ymusicapp.com / `ymusic://`; desktop macOS + iOS `ymusic://` only — no
  universal links, and Windows/Linux have no protocol registration or single-instance hand-off yet). `AppRoot`
  drains it and asks the engine (`resolveLink` → engine-js `yt/resolve-link`, which owns every YouTube url shape and
  the handle → channel lookup) what it opens: a video plays with its radio (or the link's playlist) in Now Playing,
  a playlist/channel/music page lands on Discover's stack, a search runs there.

- **PlaybackCoordinator** — play actions + display metadata for queue items. Play-now starts the video's
  radio and a playlist page's Play continues past the loaded page, both as a `NextQueueSource` (the engine's
  `yt/queue` = /next playlist panel; a video with no radio gets its related videos and stops).
  Watch-time ranking (Library Recent play / Top tracks, Discover Top tracks) lives in
  `PlayHistoryStore` (its own SQLite file), not `:media-store`.

- **Play again** (legacy "Rewind playlist") — every queue a `Player.setQueue` makes is a session keyed by
  `QueueState.queueId`. `PlayAgainRecorder` (one per `AppGraph`, started when `playHistory` is set)
  writes it to `PlayAgainStore` (the `play_again` table in the play-history DB, newest 50) when it starts,
  when its items or current item change, on pause, every 30s while playing, and when it is replaced.
  `PlayAgainScreen` shows each session as a card (current item, a strip of the queue around it, Resume). A tap calls `PlaybackCoordinator.playAgain`, which restores the queue
  under the same id. Like legacy, it resumes mid-item only when ≥5 min remain; otherwise the item restarts.
  A playlist queue's `QueueSource.resumeToken` (the `NextQueueSource` step: radio, playlist position or continuation) is saved with the
  session, so a resumed playlist keeps loading from where it had reached.

- **Playlist transfer** (`PlaylistTransfer.kt`) — the Playlists tab's ⋮ menu: Import from device (shell
  `DevicePlaylistSource`; Android reads MediaStore playlists and keeps only members matching a Library item, merged
  by name), Back up (`createDocument` → a `ymusic.playlists` v1 JSON of every user playlist's codes) and Restore
  (`openDocument` → Merge by name or Replace all, via `BetterMediaStore.restorePlaylists`). Smart playlists are
  never included. Each action hides when its `AppGraph` seam is null.

- **Thumbnails** — render through `:image-loader` (`LocalImageLoader` provided from `AppGraph.imageLoader`; null
  keeps placeholder boxes).

- **Localization** — **localized** via compose-resources: every user-facing string lives in
  `commonMain/composeResources/values*/strings.xml` (en base + ~50 locales — add every new key to ALL `values-*` files; plurals + `%1$s` args; brand marks
  untranslated by omission — missing keys fall back to base) — never hardcode UI text (dev-only CatalogueScreen
  exempt).
  - The `AppSettings.uiLanguage` setting drives `applyUiLocale` (expect/actual; JVM/Android set the default locale,
    iOS = next launch).
  - Plurals go through `pluralText` / `loadPluralText`, never compose-resources' own lookups: those throw while the
    platform locale's language has no CLDR plural rules, and Android can restore such a device locale over ours.
  - `App` rebuilds under `key(tag)` with `LocalLayoutDirection` derived from the effective locale (RTL mirrors the
    whole UI).
  - `SettingsScreen` hosts the picker.
  - Visual check: `:desktopApp:jvmTest` LocaleSnapshotTest renders en/vi/ar PNGs.
