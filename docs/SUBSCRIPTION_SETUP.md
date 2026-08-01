# Pro subscription — setup and rollout

Ships in **code 15**. The code is written and compiles, but it cannot work until the
products exist in Play Console: `queryProductDetails` returns nothing for a product
that is missing or inactive, and the paywall then shows *"Subscriptions aren't
available yet."*

Pricing, as specified: **₹99/month** and **₹399/year**.

> One commercial note, then it's your call. ₹399/year is a 66% discount on ₹99×12.
> That is a strong incentive to take the yearly plan, which is good for retention and
> cash up front, but it caps a subscriber at ₹399/year and yearly buyers are hard to
> re-price later. If you would rather protect revenue, ₹699–799/year is still a clear
> saving. The app reads prices from Play at runtime and computes the "save X%" line
> from them, so changing your mind costs nothing in code.

## 1. Create the subscription (Play Console)

**Monetise with Play → Products → Subscriptions → Create subscription**

- **Product ID**: `pro` — must match `BillingManager.PRODUCT_ID_PRO` exactly, and it
  can never be changed once created.
- **Name**: `Pro` (shown to users)

Then add **two base plans** to that one subscription. One product with two base plans
is Google's current model, and it keeps entitlement to a single product check while
letting Play handle switching between plans.

| Base plan ID | Type | Billing period | Price (India) |
|---|---|---|---|
| `monthly` | Auto-renewing | 1 month | ₹99 |
| `yearly` | Auto-renewing | 1 year | ₹399 |

The IDs must be exactly `monthly` and `yearly` — they match
`BillingManager.BASE_PLAN_MONTHLY` / `BASE_PLAN_YEARLY`.

**Activate both base plans.** A saved-but-inactive plan is invisible to the app, and
this is the single most common reason a paywall looks broken.

## 2. Update the content rating — required

You answered **"Does the app allow users to purchase digital goods?" → No** on
1 Aug 2026. That stops being true with this release.

**Policy → App content → Content rating** → redo the questionnaire → answer **Yes**.
An inaccurate answer here is a policy problem, not a cosmetic one.

Data safety needs no change: Google handles the payment, so the app collects no new
user data.

## 3. Test a real purchase before shipping

Do not ship billing that has never completed a purchase.

1. **Play Console → Setup → License testing** — add your Gmail. Testers buy at no
   charge and their subscriptions renew fast, so you can watch a renewal.
2. Upload code 15 to **Internal testing** — test purchases need a build installed
   from Play, not a sideloaded debug APK. The signing key must be the upload key.
3. Verify, in order:
   - Both plans appear with correct prices and the "save X%" line
   - A purchase completes and the app flips to Pro
   - The quota caption changes to **"Pro · unlimited"**
   - Solving repeatedly no longer decrements anything or hits a limit
   - The "Go Pro" button disappears from Home
   - Kill and reopen the app — Pro survives (that is `ProPrefs`)
   - Cancel in Play, wait for expiry, reopen — access is revoked

## 4. What the code does

- `billing/BillingManager.kt` — connection, product query, purchase flow, entitlement.
  **Acknowledges every purchase**, because an unacknowledged purchase is refunded
  automatically after three days. That is the classic first-launch billing bug.
- `billing/ProPrefs.kt` — local entitlement cache so a paying user is not metered
  while the billing handshake completes. Play overwrites it in both directions on
  every `queryPurchases`, so a lapsed subscription loses access.
- `usage/UsageRepository.kt` — Pro short-circuits `tryConsumeOne` before any
  Firestore call. One change covers Solve, Home Work, Planner and UPSC News.
- `screens/UpgradeScreen.kt` — the paywall. Prices and savings come from Play at
  runtime; nothing is hardcoded, so it stays correct in any currency.
- Entry points: a "Go Pro" button on Home (hidden once subscribed) and a CTA next to
  the rewarded-ad button at the moment the daily limit bites — which is where a
  paywall actually converts.

## 5. Known gap: entitlement is client-side

`BillingManager` trusts the Play client on-device. A determined user can defeat it.
This matches the existing posture of the daily quota, which is also client-enforced
against open Firestore rules, so it adds no new class of risk.

The real fix is server-side verification: have the backend call the Google Play
Developer API (`purchases.subscriptionsv2.get`) with a service account that has Play
Console access, then gate `/solve` and friends on a verified entitlement. That also
closes the existing quota-tampering gap. Worth doing when subscription revenue is
large enough to be worth stealing — not before.
