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
