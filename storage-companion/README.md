# YMusic Files (`storage-companion/`)

A tiny helper app (`io.ymusic.files`) that gives YMusic a folder picker on Android 11+ devices that have none — Android TV resolves `ACTION_OPEN_DOCUMENT_TREE` to `frameworkpackagestubs`, which only toasts "not supported". YMusic itself holds no broad storage permission; this app holds `MANAGE_EXTERNAL_STORAGE` instead, so only the few users who need it ever grant it.

- **Separate Gradle project**, not a module of the main build: Java, framework views, no libraries (no Kotlin stdlib either — `android.builtInKotlin=false`). Release APK ≈ 44 KB.
- **Shipped inside YMusic**: `:androidApp`'s `storageCompanion<Variant>` task builds it (`assembleDebug` / `assembleRelease`, signed with the same key as the YMusic variant) and stages the APK as the asset `storage-companion.apk`. YMusic installs it through `PackageInstaller` when needed (`androidApp/.../StorageCompanion.kt`).
- **`PickFolderActivity`** (action `io.ymusic.files.action.PICK_FOLDER`, launched explicitly by YMusic): asks for All files access, browses the mounted volumes, and returns a tree uri of `FilesProvider` with a persistable read/write prefix grant. It answers only callers signed with its own key — the grant is all-files access to that folder.
- **`FilesProvider`** (`io.ymusic.files.documents`): a `DocumentsProvider` over real paths; a document id is the absolute path, canonicalized on every use so `..` and symlinks can't leave a granted tree. It has no `DOCUMENTS_PROVIDER` intent filter on purpose: listed in the system picker it would hand any app all-files access.
- YMusic sees an ordinary SAF `content://` tree, so scanning, downloads, the write probe, rename and Remove from disk all run through its existing SAF code.

Build on its own: `./gradlew -p storage-companion assembleRelease` (from the repo root). Keep the AGP version in `build.gradle.kts` in step with `Versions.agp`. Bump `versionCode` with every change: YMusic reinstalls the bundled copy when the installed one is older.

Strings: `pick_title`, `use_folder`, `empty` and `open_settings` are copied from `:commonUi`'s translations; `access_message` is the companion's own. A new string goes into every `values-*` folder.
