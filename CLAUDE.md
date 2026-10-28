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
- `archive/` — archive engine (`ArchiveManager`, `ArchiveType`), pure JVM.
- `job/` — the shared long-job pipeline used by **both** archiving and transfers: `JobProgress`
  (`Kind {EXTRACT, COMPRESS, COPY, MOVE}`), `JobProgressBus`, `JobListener`, `TransferJobs`.
- `service/` — the foreground `FileJobService` that runs every long job. Dependency direction is
  one-way: `archive` → `job` and `data` → `job`; `job` depends on neither.
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
  Start a paste with `FileJobService.paste(...)`, never by calling `TransferManager` from a ViewModel:
  the service owns the clip (it clears the clipboard) and the screen refreshes off
  `JobProgressBus.completions`. The clip reaches the service via a `TransferJobs` token — the service
  must **not** read `TransferClipboard` itself, which would race the clear.
- **Add a setting** = a SharedPreferences singleton `object` mirroring `ThemeManager`/`PluginManager`
  (`MutableStateFlow` + prefs read/write), `init()`-ed in `App.onCreate`. (DataStore is a dependency but
  is unused — don't reach for it.)
- **Context-menu actions are data.** Build a `List<ContextAction>` and hand it to the shared `ContextMenu`
  (`ModalBottomSheet`) in `FileContextSheet.kt`; don't hand-roll a bespoke menu per screen.
- **Long-running work** runs in the foreground `FileJobService` with progress on `JobProgressBus` —
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
- **7z must be read STRICTLY sequentially.** In `extractSevenZ`, iterate `getNextEntry()` and read from
  the `SevenZFile` itself (`sz.read(buf, off, len)`). **Never call `getInputStream(entry)`** — it compiles
  to `buildDecodingStream(idx, isRandomAccess = true)`, and *interleaving* it with `getNextEntry()`
  defeats commons-compress's deferred-block optimisation, re-decoding the solid block from its start for
  every entry. 7z archives are solid by default, so that is **O(n²)** decompression. Measured on a 200 MB
  solid archive: 100 entries 56.6 s → 1.8 s, 200 entries 112.8 s → 1.7 s (the old cost grows with entry
  count at constant bytes; the new one is flat). This is what made multi-GB 7z extraction take hours.
  Note `getInputStream` *alone*, iterating `getEntries()`, is only ~1.4× slower — the interleaving is the
  trap. Totals come from `getEntries()` (parsed headers), so there is no second open and no pre-pass:
  a `nextEntry` pre-pass is not free, it decodes the whole stream just to skip it.
- **Buffer every archive file sink** (`sink()` in `ArchiveManager`). All app-visible Android storage is
  FUSE-backed, so throughput is dominated by syscall count, and junrar in particular writes in whatever
  granularity it likes — including one byte at a time via `ProgressOutputStream.write(b: Int)`.
- **Throttle progress at the source *and* in the bus.** `JobProgressBus.update()` coalesces running ticks
  to ~10/s (terminal states always pass) so no engine can flood recomposition. Screens must observe
  `JobProgressBus.completions`, not `progress`, or collecting in a top-level composable body invalidates
  the whole file list on every tick.
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
- **Dialogs with a text field must use `ImeSafeDialog`, never a bare `AlertDialog`.** Material 3's
  `AlertDialog` applies no IME insets and stacks its actions *below* the field, so on a short window —
  a 7" 16:9 handheld in landscape leaves ~150dp above the stock keyboard against ~230dp of dialog, and
  SwiftKey takes ~4/5 of the screen — the action row vanishes and the field is clipped.
  The fix has **two** halves and both are load-bearing.
  (1) **Make the dialog short.** Under 500dp of height `ImeSafeDialog` collapses to a single ~70dp bar:
  the title folds into the field's own *label* (a label costs no extra height, sitting inside the
  border) and the actions sit level with the field, so field and buttons stand or fall together.
  (2) **Anchor the window to the top** (`window.setGravity(TOP or CENTER_HORIZONTAL)` on the
  `DialogWindowProvider`). **Never assume the platform moves the dialog out of the keyboard's way** —
  that is device-dependent. On the emulator (API 33 *and* 34) the dialog window carries
  `INSET_PARENT_FRAME_BY_IME`, so its parent frame shrinks to the space above the IME and a centred
  dialog is always visible; on a real Odin 2 Portal (Android 13) it does not, the dialog stays centred
  on the **full** screen, and SwiftKey — which starts at ~1/3 of the screen height in landscape, far
  taller than the AOSP keyboard — covers it *entirely*: scrim visible, dialog nowhere. Being short made
  that worse, not better (a small centred dialog is swallowed whole where a tall one at least peeks
  out), which is why step 1 alone did not fix it. Pinned to the top, visibility no longer depends on
  keyboard height or on any window-manager behaviour, since keyboards come up from the bottom.
  (3) **Wire `ImeAction.Done` to confirm — it is load-bearing, not a convenience.** Top gravity buys
  *visibility*, not touchability. SwiftKey declares an IME window spanning the whole screen below the
  status bar **and claims touch over all of it** (`dumpsys input` → the `InputMethod` row's
  `touchableRegion` is the full display; `dumpsys window windows` shows that window *above* the
  dialog's). So while SwiftKey is up, **no tap reaches the dialog at any position** — OK/Cancel render
  but are dead, and even a tap outside it neither dismisses the dialog nor types anything. Moving the
  dialog cannot fix this; there is nowhere to move it to, and a window that sits above the IME cannot
  receive text. So `Done` is the *only* confirm path there (dismiss is back: once to drop the keyboard,
  again to close). Confirmed on an Odin 2 Portal: with the keyboard hidden the very same OK tap works,
  and on the emulator's AOSP keyboard button taps work throughout — so a dead button under SwiftKey is
  this, not a Compose hit-testing bug. Test buttons on the emulator and `Done` on the device.
  Also use `TextFieldValue` with the caret at
  `TextRange(initial.length)` (a plain `String` puts the caret at 0, so typing prepends).
  Beware when driving this over adb: SwiftKey autocorrects `input text`, so the field can end up with
  text you did not send — read it back from `uiautomator dump`, don't trust the string you passed.
  **Do not "fix" this by going full-screen** (`usePlatformDefaultWidth = false` +
  `decorFitsSystemWindows = false`) to consume IME insets by hand — tried and rejected. Such a window
  is neither panned nor resized, needs a hand-rolled scrim, and **breaks the IME**: `showSoftInput()`
  is called but the keyboard never appears. `SOFT_INPUT_ADJUST_RESIZE` on the dialog window does not
  help either. Relatedly, never swallow taps on a dialog card with
  `pointerInput { detectTapGestures {} }` — it eats the press before the field sees it, leaving the
  field unfocusable; use a no-op `clickable`.
  Verify any change here in **landscape**, not just portrait — portrait has room to hide the bug. Match
  the Odin's metrics (`wm size 1080x1920` + `wm density 360` → 480dp tall in landscape, the same layout
  scale as its screenshots) and rotate with `wm user-rotation lock 1` (`settings put system
  user_rotation` alone is ignored while the launcher is foreground). Rotation resets after
  `adb install`, so re-apply it and confirm `dumpsys display` reports `real 1920 x 1080` before driving
  taps. A useful extra stress case is `wm size 820x1920`, which leaves the keyboard covering ~2/3 of a
  landscape window. Because the emulator always keeps dialogs clear of the IME, **the emulator cannot
  reproduce this class of bug** — check the placement mechanically instead:
  `dumpsys window windows` should show the dialog's `frame=` starting at the top of its `parent=`.
  `CompressDialog` and the archive `PasswordDialog` still use bare `AlertDialog`s with a text field and
  therefore remain exposed to this on such devices.
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
  Horizontal is `translationX = offsetX` (clamped `[-(scale-1)·w, 0]`).
- **PDF vertical position needs *two* carriers, and dropping either one is a real bug.** The list scroll
  alone is not enough: `LazyColumn`'s max scroll puts the last content pixel at the bottom of the
  **unscaled** viewport, so at scale `s` the final `(s-1)/s` of that screenful has no scroll position
  that can reach it. `translationY = offsetY` (clamped `[-(s-1)·h, 0]`) carries exactly that remainder —
  and the two ranges sum to precisely the right total, since `(D-h) + (s-1)h/s = D - h/s`. Without it,
  zooming near the end of a document silently clamps (you get the top of the page, not the point you
  pinched) and panning down does nothing — the symptom that made the reader feel broken on short PDFs.
  Everything vertical therefore goes through one `advanceVertical(contentDelta, scale)`, which scrolls
  the list first and spills the leftover into `offsetY` when advancing, and unwinds in the reverse order
  when going back, so the pair behaves as a single monotone coordinate. Scroll is driven **synchronously
  via `lazyState.dispatchRawDelta`** (whose return value is the spill); do *not* go back to launching a
  coroutine per event for `scrollBy` — overlapping launches fight the scroll lock and vertical panning
  barely moves. `dispatchRawDelta` works even though `userScrollEnabled=false` while zoomed (that flag
  only gates *user* gestures, not programmatic scroll) — which is also why a flick while zoomed gets no
  native fling and `PdfScreen` runs its own `splineBasedDecay` on release.
- **Keep the last page bitmap while re-rendering.** `PdfPage` holds the previous bitmap until the sharper
  one lands instead of resetting to `null` (what `produceState` did): falling back to the placeholder
  changes the item's height, which yanks the scroll position every time the zoom settles.
- **`items` name clash.** `LazyColumn` and `LazyVerticalGrid` both export `items`; import the grid one
  as `gridItems` (`androidx.compose.foundation.lazy.grid.items as gridItems`).
- **Multi-line commit messages** with embedded double quotes break shell parsing — use
  `git commit -F <file>`.

## Release

Manual GitHub Actions workflow: **Actions → "Release APK" → Run workflow**, set a version tag
(e.g. `v0.4`). It builds `:app:assembleRelease`, attaches `android-app-<version>.apk`, and publishes a
GitHub Release with `generate_release_notes: true`. Note: GitHub's auto "What's Changed" lists merged
PRs; since work lands directly on `main`, expect mainly the "Full Changelog" compare link.

**Auto-update depends on this workflow.** `AppUpdateRepository` reads `releases/latest` (which skips
drafts and pre-releases), takes the first `.apk` asset, and compares `versionCodeOf(tag)` with
`BuildConfig.VERSION_CODE` — so `versionCodeOf` must stay in lockstep with the workflow's
`MA*10000 + MI*100 + PA` formula, and every release must attach exactly one APK. Bump the
`versionCode`/`versionName` defaults in `app/build.gradle.kts` to the new tag with each release, or
local builds will be offered "updates" they already contain. To test the flow on the emulator, build
with `-PappVersionName=<older> -PappVersionCode=<older code>`. Android 14's installer labels its
"App installed" screen `com.android_explorer.App` with a generic icon after replacing a running app —
that is the system's doing (tapping an APK in the browser does the same), not the updater's.

## Committing

This repo pushes to **github.com/nivelij** (personal account). Commits **must** be authored as
`nivelij <12596856+nivelij@users.noreply.github.com>` (the repo-local git config is already set), and
pushed via the SSH alias `git@github.com-personal`. Only commit/push when the user asks.
