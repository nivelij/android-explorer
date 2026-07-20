# CLAUDE.md

How to work in this repo: workflow, conventions, code style, and gotchas. **This is not a feature list
or changelog** — user-facing features live in `README.md` and *what changed* lives in git history. When
you add or change a feature, do **not** describe it here; only add something if it's a durable rule,
convention, or pitfall worth enforcing next time.

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

## Codebase map

- `MainActivity.kt` / `App.kt` — single Activity; `AppRoot` **is** the navigation (see below). `App`
  wires the SharedPreferences singletons.
- `data/` — repositories (plain classes) + models: `FileRepository` (filesystem incl. `search()`),
  `MediaStoreRepository`, `StorageRepository`, `RecentFilesRepository`; `FileItem`/`NodeRef`;
  `TransferClipboard`/`TransferManager` (copy/cut/paste).
- `data/drive/` — Google Drive backend: `DriveAuth`, `DriveApi`, `DriveRepository`.
- `ui/<feature>/` — one package per screen (`home`, `browser`, `analyzer`, `category`, `search`,
  `editor`, `pdf`, `drive`), each a `…Screen` composable + a `…ViewModel`.
- `ui/components/` — shared composables: rows (`FileEntry.kt`), menus (`FileContextSheet.kt`), icon +
  accent mapping (`FileIcons.kt`), `StorageMeter`, dialogs.
- `archive/` + `service/` — archive engine (`ArchiveManager`) run via the foreground `ArchiveService`.
- `util/` — `FileOpener`, `Permissions`, `ThemeManager`, `PluginManager`, `Formatting`, `Wallpaper`.

## Conventions (follow these)

- **Navigation: no library.** `AppRoot` picks the screen with a `when` over remembered state, in
  precedence order. **Add a screen** = add a state var + a `when` branch + thread callbacks down. Use
  `rememberSaveable` for state that must survive process death.
- **Open files through the one resolver.** Route every file tap through `MainActivity`'s `openFile`
  lambda (built-in editor / PDF / `.apk` installer / system chooser). Never re-implement open logic per
  screen.
- **State:** `AndroidViewModel` + `StateFlow` + `collectAsStateWithLifecycle`. ViewModels are
  Activity-scoped via `viewModel()` and **reused**, so screens (re)load in a `LaunchedEffect(Unit)` on
  entry rather than in `init`.
- **`FileItem` is backend-agnostic** — it carries `location: NodeRef` (`Local(File)` / `Drive(...)`).
  `file` is **nullable** (local only): call `requireFile()` only on local-only paths, and for anything
  needing a real `File` (editor/PDF/share/wallpaper/archive) obtain one via temp download for Drive
  items. `path` is the stable list/selection id.
- **Cross-backend copy/move** goes through the app-wide `TransferClipboard` + `TransferManager.paste(...)`,
  which dispatches on source→dest (incl. upload/download). Don't branch on local-vs-Drive at call sites.
- **Add a setting** = a SharedPreferences singleton `object` mirroring `ThemeManager`/`PluginManager`
  (`MutableStateFlow` + prefs read/write), `init()`-ed in `App.onCreate`. (DataStore is a dependency but
  is unused — don't reach for it.)
- **Context-menu actions are data.** Build a `List<ContextAction>` and hand it to the shared `ContextMenu`
  (`ModalBottomSheet`) in `FileContextSheet.kt`; don't hand-roll a bespoke menu per screen.
- **Long-running work** runs in the foreground `ArchiveService` with progress on `ArchiveProgressBus` —
  never block a screen inline.
- **Icons** come from `FileIcons.kt` (`iconFor`/`colorFor`/`kindOf`); per-folder overrides via
  `specialFolderIconRes`. Use `material-icons-extended` `Icons.Rounded.*`.

## Code style

- **Match the surrounding file.** Kotlin, 4-space indent, explicit imports (no wildcards), trailing
  commas on multi-line argument lists.
- **Compose:** screen-internal pieces are `private` composables; hoist state and pass callbacks down as
  `onX: () -> Unit`; give reusable composables an optional `modifier: Modifier = Modifier`.
- **Colour comes from `MaterialTheme.colorScheme`**, never hardcoded hex — the one sanctioned exception is
  the file-type accent palette in `FileIcons.kt`. Everything must read on all four themes incl. OLED black
  (see the translucent-surface gotcha).
- **Comment the *why*,** not the what: short notes on non-obvious tradeoffs, matching the existing files.
- **No new build warnings** — only the pre-existing `Icons.*` deprecations are tolerated.

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
