# Release v1.2.5 (versionCode 14)

Branch: `release/v1.2.5-targetsdk36`

Two things ship together: the Play **target API 36** requirement (hard deadline
**31 Aug 2026** — updates are blocked after it) and the first **rewarded-ads**
release.

## What changed

| Area | Change |
|---|---|
| Toolchain | AGP 8.6.1 → 8.13.2 (8.6.1 cannot build compileSdk 36) |
| Compliance | `compileSdk`/`targetSdk` 35 → 36 |
| Android 16 | `enableEdgeToEdge()` in `MainActivity`; `safeDrawingPadding()` on `LoginScreen` |
| Android 16 | Portrait lock held on large screens via `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` |
| Revenue | Restored the `AD_ID` permission that `tools:node="remove"` was stripping |
| Revenue | Debug builds now use Google's test ad unit, not the live one |
| Retention | Failed solves refund the daily quota instead of silently eating it |
| Retention | Rewarded ad auto-runs the pending solve once the reward lands |
| Docs | Privacy policy now covers advertising; CLAUDE.md corrected to Production |

## 0. Known issue — 16 KB page size (NOT fixed in this release)

Running the debug build on an API 37 emulator raises:

> This app isn't 16 KB compatible. ELF alignment check failed. This app will be
> run using page size compatible mode.

Misaligned native libraries:

| Library | Comes from | Current | Fixed in |
|---|---|---|---|
| `libsentry.so`, `libsentry-android.so` | `io.sentry:sentry-android` | 7.14.0 | 8.x (latest 8.51.0) |
| `libimage_processing_util_jni.so` | `androidx.camera:*` | 1.4.0 | 1.4.2+ (latest 1.6.1) |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path` | 1.0.x via Compose BOM | 1.1.0 |
| `libmlkit_google_ocr_pipeline.so` | `com.google.mlkit:text-recognition` | 16.0.1 | **16.0.1 is already latest** |

Google Play requires 16 KB support for apps targeting Android 15+. The ML Kit row
is the awkward one: no newer bundled version exists, so fixing it likely means
switching to the unbundled `com.google.android.gms:play-services-mlkit-text-recognition`,
which is a behaviour change (model served via Play services) and needs its own testing.

**Decision for v1.2.5: ship without this fix.** Rationale:

- The targetSdk 36 deadline (31 Aug) is hard and this work is done and verified.
- v1.2.4 uploaded successfully on 28 Jul with the same misalignment, so Play is
  currently warning rather than blocking.
- The fix is a large dependency jump (Sentry 7→8, CameraX 1.4→1.6, Compose BOM
  2024.10→2026.06) with real breakage risk — the wrong thing to attempt in the same
  release as a compliance deadline.

**Verify empirically:** upload to Internal testing first. Play states plainly whether
16 KB is a warning or a hard block. If it blocks, the dependency upgrade becomes
urgent and v1.2.5 cannot ship as-is.

Track the fix as **v1.2.6**.

## 1. Test on a device before uploading

None of this has run on hardware. Install the debug build and confirm:

```bat
cd android-app\AIStudyScanner
.\gradlew installDebug
```

- [ ] **Rewarded ad** — burn the 2 debug-limit solves, tap *Watch ad for +3 more*,
      confirm a **test** ad plays and the answer then appears **without** pressing
      Solve again.
- [ ] **Quota refund** — turn off wifi/data mid-solve. The error must say the solve
      wasn't used, and the remaining count must not drop.
- [ ] **Edge-to-edge** on Android 15 or 16 — no content under the status or
      navigation bar. Check the Login screen and one `Scaffold` screen.
- [ ] **Camera/scan** still works (CameraX under the new AGP).
- [ ] **Large screen** — on a ≥600dp emulator, confirm the app stays portrait. If it
      rotates, the compat property is not taking effect; decide whether to ship
      adaptive layouts or accept rotation.

## 2. Play Console declarations — required, or the release is rejected

### Two blockers confirmed from the live listing (1 Aug 2026)

- [ ] **Data safety currently says "No data shared with third parties".** That was
      true before ads. AdMob receives the Advertising ID, which *is* sharing with a
      third party. An inaccurate Data safety form is an enforcement action, not a
      warning — fix this **before** the ads build goes live.
- [ ] **The listing shows "Rated for 3+".** Check **App content → Target audience
      and content**. If any under-13 age band is selected, the Families policy
      forbids collecting the Advertising ID and forbids personalised ads, which
      directly contradicts the `AD_ID` permission restored in this release. Either
      set the target audience to 13+ (matching the privacy policy, which already
      says 13+), or drop personalised ads and call
      `setTagForChildDirectedTreatment` before serving. Decide before uploading.

### Standard declarations

Restoring `AD_ID` and shipping ads changes what must be declared:

- [ ] **App content → Ads** — declare the app **contains ads**.
- [ ] **App content → Data safety** — declare **Advertising ID** as collected, used
      for *Advertising or marketing*. This is the step people miss; the permission is
      in the manifest now and Play cross-checks it.
- [ ] **Store listing** — the "Contains ads" badge appears automatically.
- [ ] **Privacy policy** — republish `docs/privacy-policy.html` (updated here) so the
      live URL matches. Play does fetch it.
- [ ] **AdMob** — confirm the app is linked and `app-ads.txt` is set up if you have a
      site listed.

## 3. Build and upload

Only the two passwords are needed; version code, name, API URL, keystore path and
alias now come from `gradle.properties`.

```bat
cd android-app\AIStudyScanner
.\gradlew bundleRelease "-PRELEASE_STORE_PASSWORD=<pw>" "-PRELEASE_KEY_PASSWORD=<pw>"
```

Output: `app/build/outputs/bundle/release/app-release.aab`

- [ ] Upload to **Internal testing** first. Verify a **real** ad serves on the signed
      build (test ads only appear in debug).
- [ ] Confirm **App bundle explorer** reports **Target SDK 36**.
- [ ] Promote to Production as a **staged rollout** (20%), then widen.

Bump `RELEASE_VERSION_CODE` in `gradle.properties` after every upload. Play
rejected code 12 once already because it had been used.

## 4. Not done here

- **Adaptive layouts.** The compat property stops working in API 37, and tablet /
  Chromebook support is real install headroom. This is the next Android task.
- **Store listing / ASO.** Title, short description, screenshots and keywords drive
  installs far more than anything in this release, and none of it was touched.
- **Rewarded-ad economics.** One placement (out of quota) at +3 solves for 10/day
  free is a guess, not a tuned funnel. Watch retention against ad revenue before
  changing the free limit.
