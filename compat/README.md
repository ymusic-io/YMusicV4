# Bundle compatibility test

`engine.js` / `player.js` are pushed remotely to every shipped app, so today's bundle must still work on
yesterday's host (its Kotlin decoders, QuickJS/`:mse` natives, host protocols). This checks exactly that:
**the host built at each shipped tag + the bundle built from today's tree.**

- `floor.txt` — the oldest shipped version still checked. Raise it with the support floor.
- `tags.sh` — `release-v*` / `hotfix-v*` tags at or above the floor (`--json` = the CI matrix).
- `BundleCompatTest.kt` — lives only here. `overlay.sh` copies it, along with today's `YtFixtures.kt`
  (today's YouTube shapes), into a tag checkout's `:dynamic-engine` jvmTest. It stages today's prod bundles and
  the tag's own versionCodes (Android, desktop, iOS) into `dynamic-engine/build/compat/`. It drives the old
  host's public command API over canned HTTP, once per versionCode (JS gates on `versions.app`).
- `run.sh [tag…]` — local: builds `-Pproduction` bundles from the working tree (uncommitted edits included),
  then runs every tag (or the named ones) in a reused worktree under `$COMPAT_DIR`
  (default `$TMPDIR/ymusic-compat`).
- CI: the release build (`.github/workflows/release.yml`, on a `release-*` tag) builds the bundles once, then
  runs one concurrent job per tag; the draft release waits for all of them.

**The test must compile at every tag from the floor on.** It only uses API that every one of those tags
has. When a newer host changes that API, keep the test on the old shape until the floor passes it.

Assertions are deliberately loose (decodes, non-empty, typed errors). An old decoder usually *defaults* a
missing field rather than throwing — a renamed `sections` comes back as an empty page — so "non-empty"
is what catches most result breaks. Content correctness is HEAD's own tests' job.

The player check stops at boot + health check: every player command past that needs `player/init`, which
scrapes the live homepage and `base.js`.
