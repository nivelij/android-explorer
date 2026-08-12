# TODO — Publishing Explorer to the Google Play Store

Running checklist of what's needed to get this app onto the Play Store. Ordered roughly by
priority / dependency. Check items off as they're done.

## ⚠️ Priority 0 — the real gate: `MANAGE_EXTERNAL_STORAGE` (All files access)

The whole app relies on all-files access, which Google Play treats as **high-risk / restricted**.
This is the single most likely thing to delay or block the listing.

- [ ] Be ready to submit a **Permissions Declaration** in Play Console justifying the permission.
- [ ] Confirm we present as a **file manager** (an eligible category for this permission) and that the
      core function genuinely requires browsing all files.
- [ ] Expect a **manual review**; frequent rejection cause — keep the justification tight and honest.

## Priority 1 — Google Play developer account

- [ ] Register a **Play Console account** (one-time **$25** fee).
- [ ] Complete **identity verification** (legal name/address + phone) — required for personal accounts.
- [ ] Start the **new-account testing gate early**: personal accounts created after Nov 2023 must run a
      **closed test with ≥12–20 testers for 14 days** before production publishing is unlocked. This runs
      in parallel with everything else, so kick it off ASAP.

## Priority 1 — Decide the package name (do this FIRST, it's permanent)

- [ ] Current package is `com.android_explorer`. **Once published it can never be changed.**
- [ ] It's not the reserved `com.android.*` prefix (so technically allowed), but the "android" branding
      could draw scrutiny. **Decide deliberately now** before anything ships.

## Priority 2 — Repo / build changes (currently NOT shippable)

See the "Gotchas" section in `CLAUDE.md` — these are known-broken for release today.

- [ ] **Real release signing.** Release build currently signs with the **debug key**, and CI regenerates
      a random debug keystore each run — Play will not accept this.
    - [ ] Create a stable **upload keystore** (store as a GitHub Actions secret, not in the repo).
    - [ ] Enroll in **Play App Signing** (Google holds the real app-signing key; we sign with the upload
          key — losing the upload key is recoverable).
- [ ] **Fix `versionCode`.** Hardcoded to `1`; Play rejects re-uploads that don't increment it. Make it a
      real, bumpable value, and set a sensible `versionName`.
- [ ] **Ship an App Bundle, not an APK.** New apps must upload a signed **`.aab`**
      (`./gradlew :app:bundleRelease`), not the `assembleRelease` APK.
- [ ] **`targetSdk`.** On 35 (Android 15) — meets the current requirement. Bar rises ~yearly, so plan to
      bump it to stay eligible for updates.

## Priority 3 — Store listing & compliance assets

- [ ] **Privacy policy** hosted at a public URL (effectively mandatory, doubly so with the storage
      permission).
- [ ] **Data safety form** (for a local-only file browser this should be "no data collected" — which
      also helps the permission review).
- [ ] **Content rating** questionnaire (IARC).
- [ ] Graphics:
    - [ ] App icon **512×512**
    - [ ] Feature graphic **1024×500**
    - [ ] **≥2 phone screenshots**
- [ ] Short description + full description.
- [ ] Category + contact email.

## Suggested order of execution

1. Decide the package name (permanent).
2. Fix signing + `versionCode` + AAB build in the repo.
3. Register the developer account and **start the 14-day closed-testing clock early** (runs in parallel).
4. Prepare privacy policy, data-safety form, and the **All files access declaration** (the real gate).

---

**Note:** the repo-side pieces (release-signing config + upload keystore + Play App Signing, CI building a
signed `.aab`, managed `versionCode`/`versionName`) can be implemented in this codebase. The
account/policy/listing steps must be done by the owner in the Play Console.
