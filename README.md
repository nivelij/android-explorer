# android_explorer

A minimalistic Android file explorer with a built-in archive engine. Kotlin + Jetpack Compose (Material 3, Material You dynamic color). The launcher name is **Explorer**; `android_explorer` is the project/package id.

## Requirements met
- **Android 12+** — `minSdk 31`, `compileSdk 35`, `targetSdk 35`.
- **Extract & create archives** — ZIP, 7z, TAR(.gz/.bz2/.xz) both ways; RAR extract (incl. common RAR5); standalone GZ/BZ2/XZ extract. Powered by Apache Commons Compress + junrar.
- **Password-protected extraction** — encrypted ZIP (ZipCrypto/AES via Zip4j), 7z, and RAR. When an archive needs a password the progress dialog prompts for one and retries, flagging a wrong password.
- **Extract here or to a chosen folder** — an in-app folder picker lets you extract into any directory (a folder named after the archive is created there).
- **Progress dialog + notification** — a foreground `ArchiveService` runs each job and mirrors progress to an in-app dialog and a notification with a progress bar (and a Cancel action), both driven by one shared `ArchiveProgressBus`.
- **List & Details views** — default **List** is Windows-Explorer-style (larger file name on the left; size + last-modified right-aligned; folder sizes computed asynchronously). **Details** adds real **image thumbnails** (Coil) and type labels. Toolbar toggles between them.
- **Google Drive** — connect a Google account from the Home screen (OAuth via Play Services). Once connected, the Drive section shows your Drive storage usage (same meter as local storage); **tapping the card** opens your Drive in the same file browser UI. Full read/write: browse, open (download-to-view), new folder, rename, delete (to Drive trash), and **copy/cut + paste that transfers across backends** — paste a local file into Drive to **upload**, paste a Drive file into a local folder to **download**, and cut/paste within Drive to move.
- **Built-in viewers (plugins)** — text/code files, PDFs, and **images** open in the app's own screens instead of the system "open with" chooser, so a tap shows the file immediately. The **image viewer** is a full-screen gallery: it opens the tapped picture and lets you swipe through the other images in the same folder, with pinch / double-tap to zoom. Each viewer can be turned off (falling back to the system chooser) from the **Plugins** dialog (puzzle icon on Home).
- **Theme control** — System default / Light / Dark / **Black (OLED, true #000000)**, chosen from the ⋮ overflow menu (also on the Home screen) and persisted. Defaults to following the device. Managed by `ThemeManager`.
- **Create folders and files** — both available from the overflow menu.
- **Long-press context menu** — a bottom sheet (single column in portrait, two columns in landscape) with Open, Copy, Cut, Rename, Compress (Zip), Share, Extract (archives), **Details**, and Delete.
- **Multi-select** — enter selection mode either from the Select toggle in the browser toolbar or by **long-pressing a row's icon** (Gmail-style; long-pressing the rest of the row still opens the context menu); then row taps check items. The icon **3D-flips** from its file/folder glyph to a checkmark as it's selected. The toolbar's overflow (⋮) menu holds the Grid/List toggle and Sort options to keep the header lean.
- **Storage analyzer** — an **"Analyze storage"** row in the Home Storage card opens a disk-usage view: a segmented **breakdown bar** shows the folder's split at a glance, then a ranked list of cards details each child (folders **and** files together) by on-disk size, with a proportion bar and % of the folder, so the top consumers surface first. Colour does one job — a single accent on the bars — while ranking reads from length + order and identity from the icon + name. Tap a folder to drill in; toggle largest/smallest-first. Sizes are computed off-thread and cached per folder for the session.
- **Clipboard** — Copy/Cut then **Paste** (paste icon appears in the toolbar and clears after a paste); recursive copy for folders, move-on-cut, auto-deduped names.
- **Details popup** — type, size (+ item count for folders), full path, and created / modified / accessed timestamps + permissions (via NIO `BasicFileAttributes`).
- **Adaptive orientation** — supports both portrait and landscape (follows the device's auto-rotate). The home screen switches between a two-pane layout (landscape) and a stacked layout (portrait); `configChanges` preserves state across rotation.
- **Home dashboard** — a storage meter card (total/used/free via `StatFs`; **tap it to browse**), shortcuts, the Google Drive section, and a horizontally-scrollable **Recent** strip (last 10 files from MediaStore). "See all" opens the full recent-files list grouped by modified date (Today / Yesterday / This week / This month / Older).
- **All-files access** — requests `MANAGE_EXTERNAL_STORAGE` so it can browse and manage the whole device.

## Tests
Pure-JVM unit tests for the archive engine live in `app/src/test/java/com/android_explorer/archive/`:
```bash
./gradlew :app:testDebugUnitTest
```
They cover ZIP/7z/TAR round-trips, encrypted-ZIP extraction (correct / wrong / missing password), zip-slip protection, and format detection.

## Project layout
```
app/src/main/java/com/android_explorer/
  archive/     ArchiveManager (extract/create), ArchiveType, ArchiveProgress(+Bus)
  service/     ArchiveService (foreground service + notifications)
  data/        FileRepository, StorageRepository, RecentFilesRepository, FileItem
  ui/home/     HomeScreen + HomeViewModel
  ui/browser/  BrowserScreen + BrowserViewModel
  ui/analyzer/ StorageAnalyzerScreen + StorageAnalyzerViewModel (disk-usage by size)
  ui/components/ StorageMeter, FileEntry, dialogs, ArchiveProgressDialog
  ui/theme/    Material 3 theme (dynamic color)
  MainActivity.kt, App.kt
```

## Build
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Run on the emulator
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/emulator/emulator" -avd android_explorer_avd &      # Android 14, arm64
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Grant all-files access without hunting through Settings:
adb shell appops set com.android_explorer MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.android_explorer android.permission.POST_NOTIFICATIONS
adb shell am start -n com.android_explorer/.MainActivity
```
On a real device, grant "All files access" for android_explorer in Settings → Apps → Special access.

## Notes / limitations
- RAR is extract-only (no open-source RAR writer exists); RAR5 support is best-effort via junrar.
- Creating archives is not password-protected (extraction of protected archives is fully supported).
- `local.properties` pins the SDK path for this machine; adjust if you move the SDK.
