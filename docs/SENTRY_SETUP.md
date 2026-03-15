# Sentry setup (Backend + Android)

Sentry collects crashes/errors so you can see stack traces, devices, and trends.

You must create a Sentry project to get a **DSN** (a URL-like key).

---

## 1) Create Sentry projects and DSNs
1) Sign up / log in: https://sentry.io/
2) Create a project:
   - Platform: **Python / FastAPI** (for backend)
   - Platform: **Android** (for mobile)
3) Copy the DSN from each project (Settings → Client Keys (DSN))

You will end up with two DSNs:
- Backend DSN (Python)
- Android DSN

---

## 2) Backend (FastAPI) Sentry
Code already added:
- dependency: `sentry-sdk` in `ai-study-scanner/backend/requirements.txt`
- initialization: `ai-study-scanner/backend/main.py` (enabled only if `SENTRY_DSN` is set)

### Set env vars
Put this in `ai-study-scanner/backend/.env`:
```env
SENTRY_DSN=YOUR_BACKEND_DSN_HERE
# optional:
SENTRY_TRACES_SAMPLE_RATE=0.0
SENTRY_RELEASE=ai-study-scanner-backend@1.0.0
```

Notes:
- If `SENTRY_DSN` is empty/missing, Sentry is disabled.
- `SENTRY_TRACES_SAMPLE_RATE` controls performance tracing. Keep `0.0` initially.

---

## 3) Android app Sentry
Code already added:
- dependency: `io.sentry:sentry-android` via version catalog (`gradle/libs.versions.toml`)
- added in `android-app/AIStudyScanner/app/build.gradle.kts`
- app init in `android-app/AIStudyScanner/app/src/main/java/com/dineshyedla/aistudyscanner/AIStudyScannerApplication.kt`
- manifest wired: `android-app/AIStudyScanner/app/src/main/AndroidManifest.xml`

### Provide DSN to the build
Add this to `android-app/AIStudyScanner/gradle.properties` (create if it doesn't exist):
```properties
SENTRY_DSN=YOUR_ANDROID_DSN_HERE
```

This feeds the DSN into:
- `BuildConfig.SENTRY_DSN`

If DSN is empty, Sentry is disabled.

---

## 4) Verify it works
### Backend
1) Start backend
2) Hit an endpoint that triggers an error (or temporarily raise an exception)
3) Check Sentry Issues page

### Android
1) Run the app
2) Trigger a crash (temporarily throw RuntimeException somewhere)
3) Check Sentry Issues page

---

## Files changed/added
Backend:
- `ai-study-scanner/backend/requirements.txt`
- `ai-study-scanner/backend/main.py`

Android:
- `android-app/AIStudyScanner/gradle/libs.versions.toml`
- `android-app/AIStudyScanner/app/build.gradle.kts`
- `android-app/AIStudyScanner/app/src/main/java/com/dineshyedla/aistudyscanner/AIStudyScannerApplication.kt`
- `android-app/AIStudyScanner/app/src/main/AndroidManifest.xml`

Docs:
- `docs/SENTRY_SETUP.md`
