# Gold AI — Release Signing Guide

## The keystore

- **File:** `play-store/gold-ai-release.keystore` (PKCS#12 format)
- **Key alias:** `goldai`
- **Certificate:** CN=Gold AI, O=Ayan XD, C=PK — RSA 2048, valid
  24 Nov 2026 → 11 Apr 2054 (10,000 days)
- **Passwords:** in `play-store/keystore-credentials.txt` (0600, git-ignored).
  Store password and key password are the same value (PKCS#12 uses one).
- **Backup key files:** `play-store/gold-ai-release-key.pem` (private key)
  and `play-store/gold-ai-release-cert.pem` (certificate) — same 0600,
  git-ignored. Keep a copy somewhere safe **outside this repo**.

## THE ONE RULE

**The SAME keystore must sign every future update of Gold AI on every
store.** If this keystore is lost, no store will accept an update — the app
would have to be re-published under a new package name and all users would
have to install it fresh. Never delete it, never share it, never commit it.

## How CI signing works

`app/build.gradle.kts` signs the release build only when a
`keystore.properties` file exists at the repo root. The GitHub Actions
workflow (`.github/workflows/android.yml`) creates it from secrets — the
build still succeeds **unsigned** if the secrets are not set, so a missing
secret never breaks CI.

### Add the keystore to GitHub (parent / user — one time)

```bash
# From ~/workspace/lia-ai/play-store (never paste the output anywhere public):
base64 -w0 gold-ai-release.keystore
# Copy the single line of output.
```

In the GitHub repo → **Settings → Secrets and variables → Actions**,
add these four secrets:

| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_BASE64` | the base64 line from above |
| `RELEASE_KEYSTORE_PASSWORD` | store password from `keystore-credentials.txt` |
| `RELEASE_KEY_ALIAS` | `goldai` |
| `RELEASE_KEY_PASSWORD` | key password from `keystore-credentials.txt` |

Then re-run the workflow (Actions → Build Gold AI APK → Run workflow).
The `gold-ai-play-release-aab` and `gold-ai-play-release-apk` artifacts
will be signed with this key.

## Verifying a built artifact (after CI)

```bash
# APK signature (needs Android build-tools; runs on any machine with them):
apksigner verify --verbose app-play-release.apk
# AAB: unzip and check META-INF, or use bundletool:
bundletool validate --bundle app-play-release.aab
```

## Google Play App Signing — later decision

When Gold AI is eventually enrolled in Google Play, Play Console will ask
whether to use **Play App Signing**. If enabled, this keystore becomes the
**upload key** and Google manages the final signing key. That is safe and
normal — but the upload key must still be this same keystore for every
future upload. Huawei, Amazon, Samsung and Aptoide do not use Play App
Signing; they verify the APK/AAB signature against this keystore directly,
so the same key works on all five stores.
