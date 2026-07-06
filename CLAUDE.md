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
  over remembered state (`editorFile`, `pdfFile`, `searching`, `categoryName`, `browsePath`), in
  precedence order top→bottom. Use `rememberSaveable` for state that must survive process death;
  `editorFile`/`pdfFile` use plain `remember` (the Activity sets `configChanges`, so it isn't
  recreated on rotation). To add a screen: add a state var + a `when` branch + pass callbacks down.
- **One open-file resolver.** `MainActivity`'s `openFile` lambda decides built-in editor vs built-in
  PDF reader (each gated by `PluginManager`) vs the system chooser (`FileOpener.open`). Every screen
  routes file taps through this — do **not** re-implement open logic per screen. Folders open in the
  browser; archives open the contents preview.
- **State.** `AndroidViewModel` + `StateFlow` + `collectAsStateWithLifecycle`. ViewModels are
  Activity-scoped via `viewModel()` and reused, so screens call `load()`/`navigateTo()` in a
  `LaunchedEffect` on entry.
- **Repositories** are plain classes: `FileRepository` (raw filesystem via all-files access, incl.
  `search()`), `MediaStoreRepository` (device-wide category aggregation), `StorageRepository`,
  `RecentFilesRepository`.
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
  MainActivity's `configChanges` includes `orientation`, so rotating re-lays-out without recreating the
  Activity.
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
