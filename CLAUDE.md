# CLAUDE.md

Working notes for Claude Code / agents in this repo. The user-facing feature list lives in
`README.md` (which may lag the code — e.g. "Details" view is now "Grid", and the long-press context
menu is always a bottom sheet: single column in portrait, a 2-column grid in landscape).
This file covers how the code is put together and what's easy to get wrong.

## Stack

Kotlin + Jetpack Compose, Material 3 (Material You dynamic color). `minSdk 31`, `targetSdk`/`compileSdk 35`,
JDK 17, Gradle 8.11.1. Package `com.android_explorer`; launcher name **Explorer**. Single Activity.

## Commands

```bash
./gradlew :app:assembleDebug            # build debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest        # pure-JVM unit tests (archive engine only)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

There is no lint/format/CI-on-push config; the only CI is the manual release workflow (below).

## Development workflow (do this for every change)

A feature/fix is **done only after it builds, tests pass, and it's been verified running** — not when
the code looks right. Every change:

1. **Build.** `./gradlew :app:assembleDebug` must succeed. Only the pre-existing `Icons.*`
   deprecation warnings are acceptable; new warnings/errors are not.
2. **Unit tests.** Run `./gradlew :app:testDebugUnitTest` whenever you touch the archive engine or
   `data/` logic. Coverage is archive-only and pure-JVM; there are **no UI/instrumented tests**, so
   anything visual must be checked live (step 3). Add unit tests when you add testable non-UI logic.
3. **Verify on the emulator ("simulated test") — required for any UI or user-visible change.** Install
   and actually exercise the new behavior, confirming with a screenshot; do not infer it works from a
   green build.
   - AVD: `file2ex_avd`. SDK tools under `~/Library/Android/sdk/{platform-tools,emulator}`. Boot if
     needed: `emulator -avd file2ex_avd -no-snapshot-load -no-boot-anim &`, then wait for
     `adb shell getprop sys.boot_completed` = 1.
   - `adb install -r …/app-debug.apk`, then grant all-files access:
     `adb shell appops set com.android_explorer MANAGE_EXTERNAL_STORAGE allow`.
   - Drive the UI with `adb shell input tap|text|keyevent` and capture `adb exec-out screencap -p`.
   - Media categories need MediaStore populated — after `adb push`, scan with
     `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/<path>`.
   - For flows that leave the app (share / set-wallpaper / system open), confirm the launched
     component with `adb shell dumpsys activity activities | grep topResumedActivity` instead of
     guessing.
4. **Clean up** any temp/test files pushed to the device or created in the tree. Keep scratch files in
   the session scratchpad, never in the repo or `/tmp`.
5. **Docs.** If behavior or architecture changed, update `README.md` / this file so they don't drift.
6. **Commit only when the user asks** (see Committing).

## Architecture & conventions

- **No navigation library.** `AppRoot` in `MainActivity.kt` picks the current screen with a `when`
  over remembered state (`editorFile`, `pdfFile`, `searching`, `categoryName`, `browsePath`,
  `driveBrowsing`, `showRecents`), in precedence order top→bottom. Use `rememberSaveable` for state that must survive process death;
  `editorFile`/`pdfFile` use plain `remember` (the Activity sets `configChanges`, so it isn't
  recreated on rotation). To add a screen: add a state var + a `when` branch + pass callbacks down.
- **One open-file resolver.** `MainActivity`'s `openFile` lambda decides built-in editor vs built-in
  PDF reader (each gated by `PluginManager`) vs `.apk` → system installer (`FileOpener.installApk`,
  which forces the `application/vnd.android.package-archive` MIME and skips the chooser — needs the
  `REQUEST_INSTALL_PACKAGES` manifest perm; MimeTypeMap has no "apk" mapping so the generic path would
  resolve to a wildcard type and never offer the installer) vs the system chooser (`FileOpener.open`).
  Every screen routes file taps through this — do **not** re-implement open logic per screen. Folders
  open in the browser; archives open the contents preview.
- **State.** `AndroidViewModel` + `StateFlow` + `collectAsStateWithLifecycle`. ViewModels are
  Activity-scoped via `viewModel()` and reused, so screens call `load()`/`navigateTo()` in a
  `LaunchedEffect` on entry.
- **Repositories** are plain classes: `FileRepository` (raw filesystem via all-files access, incl.
  `search()`), `MediaStoreRepository` (device-wide category aggregation), `StorageRepository`,
  `RecentFilesRepository`.
- **`FileItem` is backend-agnostic.** It carries a `location: NodeRef` — `Local(File)` or
  `Drive(id, parentId, mimeType)` — so the same rows/UI render either source. `file` is a **nullable**
  `File?` (local only); local-only paths call `requireFile()`, and features that need a real `File`
  (editor / PDF / "open with" / Share / wallpaper / archive engine) get one via a **temp download** for
  Drive items. `path` is the stable id (`absolutePath` for local, `drive:<id>` for Drive); `extension`/
  `isImage`/`isPdf` are name-derived (work for both).
- **Google Drive** lives in `data/drive/`: `DriveAuth` (OAuth via Play Services **Authorization API** —
  on-device access token, no client id/secret in code; connected email persisted in prefs, `init()` in
  `App`), `DriveApi` (Drive REST v3 over OkHttp: `listFolder`/`download`/`accountEmail`/`storageQuota`,
  bearer-auth with a 401 refresh-retry), and `DriveRepository` (maps `DriveFile`→`FileItem`, caches
  downloads). The whole feature is gated behind `DriveAuth.isSupported(context)` (a `GoogleApiAvailability`
  certified-GMS check) — on uncertified devices `DriveSection` shows a non-interactive `UnsupportedCard`
  instead of the connect card (see gotcha below). UI: `ui/drive/DriveSection` (Home connect card / connected
  storage-meter card — **tap the card to browse**, no separate button). The connected card is **tri-state**:
  loading ("Checking storage…") → OK (`StorageMeter`) → **failed** (quota fetch errored = revoked/expired
  token) which swaps in a "Couldn't reach Google Drive" message + a **Reconnect** action (clears then
  re-launches sign-in, so the quota `LaunchedEffect(account)` re-fires even if the same account returns) so
  a dead session isn't a permanent "Checking storage…" dead end. `DriveBrowserScreen`+`DriveBrowserViewModel` (read-only folder-id nav stack reusing
  `FileListItem`; file tap downloads-to-cache then routes through the shared `openFile`). `AppRoot` gets
  a `driveBrowsing` branch.
- **Cross-backend transfers (Drive writes).** `FileItem`s carry their backend (`NodeRef`), so one app-wide
  `TransferClipboard` (object) is shared by both browsers; `TransferManager.paste(context, clip, destNodeRef)`
  dispatches by source→dest: local→local (FileRepository copy/move), **local→Drive = upload**, **Drive→local =
  download**, Drive→Drive = server move (cut) or copy (recursive for folders). Local & Drive browsers both read
  the shared clipboard (paste icon shows whenever it's non-empty) and paste into their current folder. Drive
  write ops live in `DriveApi`/`DriveRepository` (upload multipart, createFolder, rename, move via
  addParents/removeParents, **trash = the "Delete" action, recoverable**, copyFile). `DriveContextSheet` (in
  `FileContextSheet.kt`, reusing the private `ContextMenu`) gives Drive rows Open/Copy/Cut/Rename/Delete; the
  Drive top bar has New-folder + Paste. Long transfers show a busy overlay (no dedicated service yet).
- **Settings are SharedPreferences singletons** (`object`s), **not** DataStore (that dependency is
  present but unused): `ThemeManager`, `PluginManager`. Both are `init()`-ed in `App.onCreate`. Add a
  new setting by mirroring the pattern: `MutableStateFlow` + prefs read/write + `init`.
- **Icons.** `material-icons-extended`, `Icons.Rounded.*`. Central file-type icon + accent-color
  mapping is `ui/components/FileIcons.kt` (`FileKind` enum, `kindOf`/`iconFor`/`colorFor`). Per-folder
  drawable overrides go through `specialFolderIconRes` (e.g. Download → `ic_folder_download`).
- **Context menus** live in `ui/components/FileContextSheet.kt`: `FileContextSheet` (browser) and
  `RecentsContextSheet` (flat lists: home / category / search). Both keep the same public signature but
  now build a `List<ContextAction>` (data, not composables) and hand it to a private `ContextMenu`,
  which is **always a Material `ModalBottomSheet`** (drag handle, one consistent look). Only the action
  *layout* adapts by `LocalConfiguration.orientation`: **portrait → a single column**, **landscape → a
  2-column grid** (`normal.chunked(2)` + `Modifier.weight(1f)` cells, `Spacer(weight 1f)` for an odd last
  item) — because the landscape sheet is short. The sheet content is wrapped in `verticalScroll` so a long
  list (or the short landscape sheet) never clips. `dismissThen` plays the slide-out (`sheetState.hide()`)
  before running the action. Both orientations share `MenuHeader` (type icon + name + `size · EXT`) and a
  trailing destructive **Delete** (always full-width) split off by a divider; **no "Close" button** (tap
  scrim / swipe / back). Each action's own callback clears the caller's menu state, so it self-dismisses.
  Actions are still conditional — e.g. `onShare` only for non-folders, `onSetWallpaper` only for images.
  There is **no "Select" context action** — multi-select is entered from the browser top bar instead (see
  below). MainActivity's `configChanges` includes `orientation`, so rotating re-lays-out without recreating
  the Activity.
- **Browser top bar** (`BrowserBar` in `BrowserScreen.kt`) stays lean: `[Paste?] [Search] [Select] [⋮]`.
  The **Select** icon (`Icons.Rounded.Checklist`) calls `viewModel.enterSelectionMode()` — which sets a
  `selectionMode` flag on `BrowserUiState` so selection mode can start with **nothing selected**
  (`inSelectionMode = selectionMode || selected.isNotEmpty()`; `clearSelection()` resets both). The **⋮**
  overflow holds everything else: the Grid/List toggle, the four Sort options (active one shows an up/down
  arrow; tapping it again flips direction and **keeps the menu open**), New folder / New file / Toggle
  hidden, and the Theme items.
- **Home is a single scroll region** (`HomeScreen`, no bottom nav): Storage / Shortcuts / Google Drive,
  then a horizontally-scrollable **`RecentStrip`** (last 10 files as compact `RecentCard`s; tap a card to
  open the file). The Google Drive **card is clickable** (`onOpenDrive`); the **Storage card lists one
  meter per mounted volume** (`StorageRepository.volumes()` already enumerates internal + removable
  SD/USB), and **each meter row is individually clickable** → `onBrowse(volume)` opens *that* volume.
  The old standalone "Browse files" / "Browse Google Drive Files" buttons were removed to save space.
  Multi-volume browsing: `onBrowse` carries the `VolumeStat`, so `AppRoot` sets `browsePath`/`browseRoot`
  (= the volume mount, e.g. `/storage/<uuid>`) + `browseLabel`; `BrowserScreen(rootDir, rootLabel)` →
  `BrowserViewModel.openAt(rootDir, startDir)` binds `navigateUp` to that volume root (so SD browsing
  stops at the card, not `/storage`) and titles the root with the volume label. Internal shortcuts pass
  `browseRoot = internal root`, keeping their up-navigation unchanged. New volumes appear via the
  existing `ON_RESUME` `homeViewModel.refresh()` (re-reads `volumes()`); no live mount callback yet. Portrait stacks them in one `verticalScroll` `Column`; landscape keeps the 50:50
  split — Storage **over** the Recent strip on the left, Drive on the right. The strip's **"See all"** opens
  `RecentScreen` (`AppRoot`'s `showRecents` branch), a full list bucketed by modified date (Today / Yesterday
  / This week / This month / Older via `java.time`, week start = default locale) reusing `FileListItem` +
  `RecentsContextSheet`. The home long-press context sheet + dialogs live at the `HomeScreen`/`RecentScreen`
  level.
- **View modes.** `ViewMode { LIST, GRID }`; rows are `FileListItem` / `FileGridItem` in
  `FileEntry.kt`. The browser toggles; the Pictures/Video categories default to grid, others to list.
- **Archives.** Long jobs run through the foreground `ArchiveService` (not inline), with progress on a
  shared `ArchiveProgressBus`. Engine is `ArchiveManager` (Commons Compress + junrar + xz + zip4j).

## Gotchas

- **Signing / upgrades.** The release build signs with the **debug key**, now pinned to a keystore
  committed at `app/debug.keystore` (wired via the `signingConfigs { getByName("debug") }` block in
  `app/build.gradle.kts`, standard `android`/`androiddebugkey` creds). Local and CI builds therefore
  share one signature (SHA-256 `2048018f…`), so new releases **install over old ones** — no uninstall.
  Don't delete/replace `app/debug.keystore` or that breaks again (`.gitignore` keeps it tracked via the
  `!debug.keystore` exception). `versionCode` is still hardcoded to `1` — fine for `adb install -r`, but
  bump it for real Play/version upgrades. For an actual Play Store release you still want a *dedicated*
  release key (not the debug key) via GitHub secrets — see `TODO.md`.
- **MediaStore under all-files access** works fine; category queries map each row's `_data` → `File` →
  `FileItem`, filtered by `exists()`. Documents have no MediaStore collection — that category queries
  `MediaStore.Files` by `DISPLAY_NAME LIKE '%.<ext>'`.
- **Search** is a live recursive filesystem walk (no index) from the storage root; it runs off the
  main thread with a spinner and can take a moment on large trees. Matching is case-insensitive
  substring on the name (any extension); hidden entries are skipped.
- **Google Drive OAuth setup.** Access needs an **Android** OAuth client in the Cloud project (matched by
  package `com.android_explorer` + signing SHA-1 of the committed `app/debug.keystore`) — NOT a Desktop/Web
  client. The consent screen stays in **Testing** with the tester's email added and the full `…/auth/drive`
  scope, which avoids Google verification (a one-time "unverified app" click-through is expected). No client
  id/secret is embedded — the Authorization API identifies the app by package+SHA-1. Runtime OAuth can't be
  scripted via adb (needs a real Google account + interactive consent); verify sign-in by hand. The token is
  short-lived and re-fetched silently; a Drive call with no token surfaces "reconnect required".
- **Drive needs *certified* Play services.** The Authorization API (and the legacy `GoogleAuthUtil`
  path too) ships only in authentic, Play-certified GMS. On uncertified devices (e.g. AYN Odin2 handheld,
  Android 13) both fail with `SERVICE_INVALID` / status 17 / "GooglePlayServices not available due to error 9"
  — the connect tap did nothing because the failure listener swallowed it. There is **no in-app fix**: the
  legacy fallback hits the same `ensurePlayServicesAvailable` gate; the only real alternatives are a GMS-free
  browser OAuth (AppAuth + PKCE) or registering the device at google.com/android/uncertified. We chose to
  **gate the feature**: `DriveAuth.isSupported()` probes `GoogleApiAvailability.isGooglePlayServicesAvailable()
  == SUCCESS` and, when false, `DriveSection` renders the disabled `UnsupportedCard` (cloud-off + short
  reason) instead of Connect. Certification is device-wide, so probe once (not per-tap).
- **OLED + translucent surfaces.** The OLED theme forces `surface`/`background` to pure `#000000`, so a
  card painted with a *translucent* surface tint (e.g. `surfaceVariant.copy(alpha = …)`) collapses into
  the background and vanishes. For panels that must stay visible on every theme, use a **solid** container
  role (`surfaceContainerHigh`) plus a hairline `BorderStroke(1.dp, outlineVariant)` — see the Storage
  card in `HomeScreen.kt`. Avoid `alpha`-over-surface fills for anything that needs an edge on OLED.
- **PdfRenderer** allows only one open page at a time → `PdfScreen` serializes rendering behind a
  `Mutex`, renders off-main, and erases each page to white first (else transparency renders black).
- **PDF zoom** uses two scales: a live `gestureScale` applied via `graphicsLayer` (smooth pinch/
  double-tap, capped 5×) and a debounced `renderScale` (capped `MAX_RENDER_SCALE`=3×, width capped
  `MAX_RENDER_WIDTH_PX`) that re-renders visible pages at the settled zoom so text stays crisp. The
  pinch/pan gesture runs on `PointerEventPass.Initial` and only consumes moves when zoomed or on
  multi-touch, so at 1× the `LazyColumn` keeps native scroll+fling. The `graphicsLayer` uses a
  **top-left `transformOrigin(0,0)`** so the transform math is clean, and zoom **anchors to the pinch/
  double-tap focal point** (`event.calculateCentroid()` / the tap offset) — not a fixed center.
  Horizontal is `translationX = offsetX` (clamped `[-(scale-1)·w, 0]`); vertical is the `LazyColumn`'s
  own scroll (so paging still works while zoomed) driven **synchronously via `lazyState.dispatchRawDelta`**
  for both the focal-zoom correction and finger pan. Do *not* go back to launching a coroutine per event
  for `scrollBy` — overlapping launches fight the scroll lock and vertical panning barely moves.
  `dispatchRawDelta` works even though `userScrollEnabled=false` while zoomed (that flag only gates *user*
  gestures, not programmatic scroll).
- **`items` name clash.** `LazyColumn` and `LazyVerticalGrid` both export `items`; import the grid one
  as `gridItems` (`androidx.compose.foundation.lazy.grid.items as gridItems`).
- **Multi-line commit messages** with embedded double quotes break shell parsing — use
  `git commit -F <file>`.

## Release

Manual GitHub Actions workflow: **Actions → "Release APK" → Run workflow**, set a version tag
(e.g. `v0.4`). It builds `:app:assembleRelease`, attaches `android-app-<version>.apk`, and publishes a
GitHub Release with `generate_release_notes: true`. Note: GitHub's auto "What's Changed" lists merged
PRs; since work lands directly on `main`, expect mainly the "Full Changelog" compare link.

## Committing

This repo pushes to **github.com/nivelij** (personal account). Commits **must** be authored as
`nivelij <12596856+nivelij@users.noreply.github.com>` (the repo-local git config is already set), and
pushed via the SSH alias `git@github.com-personal`. Only commit/push when the user asks.
