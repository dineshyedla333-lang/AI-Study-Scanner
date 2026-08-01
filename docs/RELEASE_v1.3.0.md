# Release v1.3.0 (versionCode 15) — Pro subscription

The first paid release. Pro removes **both** the daily limit and the ads, at
**₹99/month** or **₹399/year**.

## ⛔ Do not publish until these two are done

This release is **blocked**, not merely unfinished. Publishing it early ships a
paywall that cannot take money.

1. **Create and ACTIVATE the subscription** — `Monetise with Play → Products →
   Subscriptions`. Product `pro`, base plans `monthly` (₹99) and `yearly` (₹399), both
   auto-renewing. Full detail in `SUBSCRIPTION_SETUP.md`.
   Without this, tapping *Go Pro* shows "Subscriptions aren't available yet" — verified
   on device, it fails gracefully, but it sells nothing.
2. **Content rating: declare digital purchases.** You answered "purchase digital
   goods → No" on 1 Aug 2026. That is false as of this release.

Then: Internal testing → buy once as a **License tester** → promote. Never publish
billing that has not completed a real purchase; unacknowledged purchases are
auto-refunded after three days.

## About the content rating — "4+" does not exist on Google Play

Play uses IARC levels: **3+, 7+, 12+, 16+, 18+**. There is no 4+ (that is Apple's
scale), and the level is **not selectable** — it is computed from the questionnaire.

The 1 Aug questionnaire answered *Online content = Yes* (AI-generated content and news
articles) and *Controlled Substance = Yes, text-only, not the focus*. Those should
produce a level above 3+, yet the live listing still reads **"Rated for 3+"**. Verify
in `Policy → App content → Content rating` that the new questionnaire was accepted; if
it still shows 3+ with those answers, the submission did not save.

## What changed

| Area | Change |
|---|---|
| Revenue | Play Billing subscription: one product `pro`, base plans `monthly` / `yearly` |
| Revenue | Pro removes the daily limit across Solve, Home Work, Planner and UPSC News |
| Revenue | **Pro removes ads** — the ads SDK is never initialised on a Pro device, so no Advertising ID is collected for them either |
| UX | "Go Pro" on Home (hidden once subscribed) and a CTA at the moment the limit bites |
| Fix | Quota caption divided by `limitPerDay` instead of `effectiveLimit` on three screens, so anyone who watched a rewarded ad saw a nonsense fraction like "7/2" |
| Cleanup | That caption is now `UsageStatus.label`, one definition instead of four |
| Docs | Privacy policy covers subscriptions, and states Pro collects no Advertising ID |

Note the ad-free claim is enforced, not cosmetic: `MobileAds.initialize` is skipped
entirely for Pro, and `RewardedAdManager.preload` returns early. Hiding the button
while still requesting ads would have made the paywall's promise untrue.

## Release notes to paste

```
<en-US>
• Go Pro — remove ads and get unlimited solves
• ₹99 per month, or ₹399 for a year
• Pro removes the daily limit on scan and solve, Home Work, AI Study Planner and UPSC current affairs
• Fixed the daily-usage counter after watching a rewarded ad
</en-US>
```

## Build

```bat
cd android-app\AIStudyScanner
.\gradlew bundleRelease "-PRELEASE_STORE_PASSWORD=<pw>" "-PRELEASE_KEY_PASSWORD=<pw>"
```

Version code and name come from `gradle.properties` (15 / 1.3.0). Output:
`app/build/outputs/bundle/release/app-release.aab`

Verified before shipping: R8 passes with the billing library, the paywall renders on
an API 37 Pixel 7, and the "Go Pro" entry appears on Home.

Bump `RELEASE_VERSION_CODE` to 16 after uploading.

## After it is live

- **Test a renewal.** License testers renew on an accelerated clock, so you can watch
  a real renewal in minutes rather than a month.
- **Then cancel one** and confirm access is revoked on the next launch — that path is
  `queryPurchases` overwriting `ProPrefs`, and it is the one most likely to be wrong.
- **Watch the ratio.** Ads pay roughly ₹40–170 per 1,000 completed views; one ₹399
  subscriber is worth thousands of ad views. If subscriptions convert at all, they
  will dominate revenue long before ad volume does.
