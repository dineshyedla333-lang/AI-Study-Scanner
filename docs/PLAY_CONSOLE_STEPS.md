# Play Console — step by step

Everything below is done at **app level**, not account level. The screenshot you
were on (Home / Policy status / Users and permissions) is the **account** menu —
none of these settings live there.

**Get to app level first:**

1. Open <https://play.google.com/console>
2. In the **1 app** list, click **AI Study Scanner Agent**
3. The left menu changes — it now shows Dashboard, Test and release, Monetise,
   Grow, **Policy**, etc. Everything below uses this menu.

All four tasks live on one page: **Policy → App content**. Google shuffles these
labels between console updates; if a name differs, look for the closest match on
that same page.

---

## Task A — Target audience (do this first)

This decides whether the ads in v1.2.5 are even allowed. Do it before anything else.

1. **Policy → App content**
2. Find **Target audience and content** → click **Manage** (or **Start**)
3. Step 1 shows **Target age group** with tick boxes:

   ```
   [ ] Ages 5 and under
   [ ] Ages 6-8
   [ ] Ages 9-12
   [ ] Ages 13-15
   [ ] Ages 16-17
   [ ] Ages 18 and over
   ```

4. **Look at which boxes are ticked. This is the answer I need.**

### What the answer means

| What you see | Meaning |
|---|---|
| Only **13-15 / 16-17 / 18+** ticked | Good. Ads work normally, full revenue. Nothing to change. |
| **Any** of *5 and under*, *6-8*, *9-12* ticked | Problem. Google's Families policy forbids the Advertising ID and personalised ads. |

### If a child age band is ticked

For a JEE / NEET / UPSC / CBSE study app, the real audience is teenagers and adults,
so the intended fix is to untick the child bands:

1. Untick everything below **Ages 13-15**
2. Tick **13-15**, **16-17**, **18 and over**
3. Continue through the remaining steps and **Save**

This matches `docs/privacy-policy.html`, which already states the app is for ages 13
and above. Setting it correctly is not a workaround — it is what your app actually is.

> Changing the target audience can reset your content rating and may remove the app
> from the Designed for Families programme. Expect to redo Task D.

---

## Task B — Declare that the app has ads

1. **Policy → App content**
2. Find **Ads** → **Manage**
3. **Does your app contain ads?** → **Yes**
4. **Save**

A "Contains ads" badge then appears on your store listing automatically.

---

## Task C — Fix Data safety (currently wrong)

Your live listing says *"No data shared with third parties"*. That stops being true
the moment the ads build ships, because AdMob receives the Advertising ID. An
inaccurate Data safety form is an enforcement action, not a warning.

1. **Policy → App content → Data safety** → **Manage**
2. Work through to the **Data types** step
3. Under **Device or other IDs**, tick **Device or other IDs**
4. On its detail screen set:
   - **Collected** → **Yes**
   - **Shared** → **Yes** ← this is the one that is currently wrong
   - **Purpose** → **Advertising or marketing**
   - **Is this required?** → **Optional** (users only see an ad if they choose to)
5. Confirm **encrypted in transit** → **Yes** (your app is HTTPS-only)
6. **Save**, then **Submit** at the end of the flow

While you are here, confirm the entries you already had are still accurate: email
address, phone number, user IDs, and the question content you send to the backend.

---

## Task D — Re-check the content rating

Your listing shows **Rated for 3+**. After changing target audience and adding ads,
redo the questionnaire so the rating matches reality.

1. **Policy → App content → Content rating** → **Start questionnaire**
2. Answer honestly. There is a question about whether the app **contains ads** —
   answer **Yes**.
3. Submit. The new rating applies to the next release.

---

## Then ship v1.2.5

Follow `docs/RELEASE_v1.2.5.md`. Order matters:

1. Device-test the debug build
2. Build the signed AAB
3. Upload to **Internal testing** and confirm **Target SDK 36** in App bundle explorer
4. Promote to **Production at 100%**
5. Republish the updated `docs/privacy-policy.html` so the live URL matches

---

## Then the growth work, in priority order

### 1. Replace the screenshots — highest impact by far

Your listing has two pale mockups that look like wireframes, not a working app.
Visitors judge in about two seconds and those images say "unfinished".

- Take **real** screenshots on a real phone, solving a **real** textbook question
- Show the step-by-step answer — that is your actual product
- Add 4-6 more (Play allows 8); captions are in `docs/STORE_LISTING.md`

**Grow → Store presence → Main store listing → Phone screenshots**

### 2. Update the listing text

Title, short description and full description are drafted in
`docs/STORE_LISTING.md`. Same page as the screenshots.

### 3. Get your first 50 real users

This is the part no code and no listing can do. You have fewer than 5 downloads, so
Play sends you essentially no organic traffic and will not until installs and
retention give it something to rank. The first users have to come from somewhere you
control:

- A college or coaching-centre WhatsApp group
- A student subreddit (r/JEENEETards, r/UPSC) — post honestly as the developer
- One teacher who will mention it to a class
- Friends preparing for these exams

### 4. Ask those users for ratings

Zero reviews is a hard conversion barrier. Ask directly — a personal message
converts far better than an in-app prompt at this size.

### 5. Only then, read the numbers

**Statistics** and **Grow → Store performance** show installs, store-listing
conversion rate, and retention. Change one thing at a time or you will not know what
moved.

---

## Honest expectation

Tasks A-D and v1.2.5 make the app **correct and shippable**. They will not, by
themselves, produce users — they clear the blockers that would stop you shipping at
all. Steps 1 and 3 in the growth list are what actually change your install count,
and step 3 is the one that has not started yet.
