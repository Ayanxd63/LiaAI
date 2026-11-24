# Gold AI — Master Store Listing

**App name:** Gold AI
**Package:** com.goldai.assistant
**Version:** 1.0.0 (versionCode 1)
**Developer:** Ayan XD
**Character:** the in-app AI companion is named "Lia" (the product's character, not a credit)
**Based on Lia AI by CodeNinjaVik** (one small line in About — keep it on every listing)
**Category:** Productivity
**Content rating:** Everyone
**Price:** Free (ads-supported). No in-app purchases, no
subscription — monetization is AdMob ads only (banner +
interstitial, test IDs until the user creates his own AdMob
account). Voice + text chat are free forever and image
generation is unlimited.

**Status (2026-11-24): READY, NOT SUBMITTED anywhere. Zero money spent.
Google Play is blocked on the $25 one-time developer fee (user has $0).
The four free stores still need the user to create the developer account
and pass identity verification himself — no account was created here.**

---

## Title (all stores)

**Gold AI — Voice AI Companion**

## Short description (80 chars max — fits Google Play + Huawei)

Your voice-first AI companion — talk naturally, get things done.

(64 characters.)

## Full description (simple English, honest — under 4,000 chars)

**Gold AI — Your Intelligent Voice Companion**

Meet Lia, a real-time AI companion that listens, understands and acts — in Hindi, Hinglish, Urdu or English. Just talk naturally and Lia responds instantly, opens apps, helps you draft messages and even builds websites on command.

**Real-time Voice Conversations**
Talk with Lia over a full-duplex live connection — barge in, interrupt or change topic any time. No waiting for replies.

**Speaks Your Language**
Switch freely between Hindi, Hinglish, Urdu and English. Lia detects your language automatically and answers in the same tongue. Hindi voice works on-device, so it sounds natural.

**10 Personalities, Your Way**
Choose from Normal, Friendly, Professional, Funny, Companion, Teacher, Developer, Hungry, Nautanki and more. Change Lia's name to whatever you like.

**Website Forge**
Say "Lia, ek cafe ki website banao" and watch a live 3D build scene construct an animated website in seconds.

**Phone Actions**
- Open any app by name
- Start a call to a contact
- Open the message composer for SMS or WhatsApp

**Image & Prompt Generator**
Create images and ready-made prompts from one simple screen.

**Premium 3D Design**
A GPU-shaded golden 3D orb reacts to your voice and tilts with your phone. Deep night sky, one warm golden accent. Looks stunning in dark and light themes.

**Typed Chat**
Prefer typing? The chat screen uses the same personality and even detects website build requests.

**Privacy First**
Your Gemini API key is stored only on your device — never sent to us. No account needed. No audio is logged or stored. Microphone is used only during active voice sessions.

**You need your own FREE Gemini API key** (from Google AI Studio) — the app asks for it once in Settings, and it never leaves your phone.

Developed by Ayan XD. Based on Lia AI by CodeNinjaVik.

---

## Data safety answers (all stores)

| Category | Collected | Shared | Required |
|----------|-----------|--------|----------|
| Microphone audio | No (processed by Google's Gemini API per your own key, never stored) | No | Yes |
| Contacts (name + phone) | No (looked up on-device only, when you ask Lia to call someone) | No | Optional |
| Approximate location | No (used on-device only for the weather card) | No | Optional |
| Device or other IDs | **Yes — Advertising ID, collected by AdMob (Google Mobile Ads SDK) to serve ads** | **Yes — with Google, for ads only** | **Yes (the app shows ads)** |
| Approximate location (for ads) | **Yes — approximate location may be used by AdMob to serve relevant ads** | **Yes — with Google, for ads only** | **Yes** |
| Photos / files | No (only files you choose to share with the app) | No | No |

**Encryption**: All API calls use HTTPS/WSS.
**Deletion**: Users can clear the API key and conversation history from Settings; uninstalling removes everything.

## Permission justifications

- **RECORD_AUDIO** — core voice conversation. Audio streams to the Gemini Live API in real time, never stored.
- **READ_CONTACTS (optional)** — only when you ask Lia to call or message a contact by name. Asked at first use.
- **CALL_PHONE (optional)** — places a call only when you explicitly ask ("Mummy ko call karo"), with confirmation.
- **CAMERA** — flashlight control only (no photos taken by the app).
- **ACCESS_COARSE_LOCATION (optional)** — city-level weather card only. No fine location, no tracking.
- **FOREGROUND_SERVICE (microphone)** — keeps the voice session alive while you switch apps. Persistent notification with a Stop button.
- **POST_NOTIFICATIONS** — Android 13+ ongoing voice-session notification.
- **SYSTEM_ALERT_WINDOW (optional)** — floating character bubble over other apps, only if you enable it.
- **RECEIVE_BOOT_COMPLETED** — restarts the always-listening service after a reboot, only if you enabled it.
- **BLUETOOTH_CONNECT** — Bluetooth enable dialog on Android 12+.

No permission was removed in the rebrand — every declared permission is used by a feature above. The `play` flavor contains no AccessibilityService (that stays direct-flavor-only) and no SEND_SMS permission.

---

## Screenshot plan (capture on a real phone after the V21 phone test)

Phone screenshots, 1080×1920 (portrait), 6 screens:

1. Home — golden 3D orb, idle
2. Voice chat — live conversation with waveform/orb reacting
3. Settings — voice picker (Hindi voice selected)
4. Chat — Roman Urdu conversation
5. Image generator — one generated image + prompt field
6. Prompt generator — one generated prompt

Reuse the same 6 shots on every store, resized per the per-store table below.
Feature graphic (Google Play + Amazon promo): 1024×500, golden orb on dark navy.

**Do NOT capture screenshots from the emulator/lab build — take them on the
user's real phone, after the V21 Hindi-voice phone test passes.**

---

## Per-store notes (verified from each store's docs, 2026-11-24)

### 1. Google Play — READY, NOT SUBMITTED (blocked on $25 fee)

- **Binary:** AAB only (`app-play-release.aab`). APK not accepted.
- **Icon:** 512×512 PNG, ≤1 MB, full square (Play masks it).
- **Feature graphic:** 1024×500 PNG/JPG (required).
- **Screenshots:** min 2, max 8, phone 1080×1920.
- **Text:** short description 80 chars max; full description 4,000 chars max.
- **Account:** Google Play developer account, **$25 one-time fee — NOT paid
  (user has $0). Do not submit until the $25 is arranged.**
- **Also needed:** privacy policy public URL, content rating questionnaire,
  data safety form, target API 36 (already set).
- **Revenue share:** Google keeps 15% on the first $1M/year (30% above).
  The app is free with ads only — no in-app purchases, so
  earnings are $0 until AdMob is switched to real IDs.

### 2. Huawei AppGallery — READY, account still needed (free)

- **Binary:** AAB or APK accepted.
- **Icon:** 216×216 or 512×512 PNG, ≤2 MB. (Use the 512×512 file.)
- **Screenshots:** 3–8, at 800×450 (landscape) or 450×800 (portrait).
  Sides must be 320–3,840 px. The 3+ shots must be different images.
- **Text:** brief introduction ≤80 chars; full introduction ≤8,000 chars.
- **No banner/feature graphic needed** for phone apps.
- **Account:** free developer account; individual registration is OK.
- **Revenue share:** developer keeps ~70%.
- **Watch out:** no other brands' names, icons or badges in screenshots
  (no Google Play badge in the Huawei shots).

### 3. Amazon Appstore — READY, account still needed (free)

- **Binary:** **APK ONLY — AAB is not supported.**
- **Icons (both required):** small 114×114 PNG + large 512×512 PNG
  (transparent background). Both files are in `play-store/`.
- **Screenshots:** 3–10, PNG/JPG, one of these sizes:
  800×480, 1024×600, 1280×720, 1280×800, 1920×1080, 1920×1200,
  2560×1600 (landscape or portrait). Keep text ≥50 px from edges.
- **Promo image:** 1024×500 PNG/JPG (landscape).
- **Text:** short description ≤1,200 chars; long description ≤4,000 chars;
  3+ feature bullet points.
- **Account:** free developer account.
- **Revenue share:** developer keeps 70% (80% for small business).

### 4. Samsung Galaxy Store — READY, account still needed (free, see caution)

- **Binary:** APK or AAB (Galaxy Store generates a universal APK from a bundle).
- **Icon:** 512×512 PNG, ≤1 MB.
- **Screenshots:** 4–8, JPG/PNG, 320–3,840 px on a side,
  aspect ratio no wider than 2:1.
- **Hero image:** 1200×675 — games only, NOT needed for this app.
- **Requirements:** target API 33+ (ours is 36) and at least one 64-bit
  binary (ours is arm64-v8a).
- **Account:** free seller account.
- **Revenue share:** developer keeps 80% (85% on subscriptions).
- **CAUTION:** Galaxy Store may require commercial-seller / D-U-N-S
  verification, which can block an individual seller. If it asks for a
  business registration, stop and tell the user — do not pay for D-U-N-S.

### 5. Aptoide (Aptoide Connect, free automated route) — READY, account still needed (free)

- **Binary:** **APK ONLY — AAB is not supported.** Signed APK required.
- **Route:** Aptoide Connect automated submission (free). **Do NOT use the
  manual / IAP review route ($69/year) — paid routes are out of scope.**
- **Signing:** same release keystore as every other store; every new
  version must use the same signature and a strictly higher versionCode.
- **Subscriptions:** none — ads-only monetization, no purchase SDK
  integrated and nothing to buy on Aptoide.
- **Not on Google Play yet:** Gold AI is not on Play, so use the manual
  submission form (enter metadata + upload the APK). Ownership proof is
  always required (email confirmation to the app's support address).
- **Icon / screenshots:** sizes are not published in the Connect docs I
  checked — reuse the 512×512 icon and 1080×1920 screenshots as the base
  set and confirm the exact fields in the Connect submission form at
  upload time.
- **Revenue share:** developer keeps **75%** of net sales on Aptoide and
  partner-store installs (up to 93% with Revenue Boost; 90% on
  self-referred players). App is free with no IAP, so $0 for now.
- **Note:** a package distributed through Aptoide may need to be
  registered under Google's Android Developer Verification.

---

## Per-store readiness (2026-11-24)

| Store | Binary ready? | Listing ready? | Still needs the USER |
|-------|---------------|----------------|----------------------|
| Google Play | AAB (built by CI after push) | Yes | $25 developer fee; Play Console account; privacy-policy URL; content rating; data safety form |
| Huawei AppGallery | AAB (same file as Play) | Yes | Free developer account + ID verification |
| Amazon Appstore | Signed APK (built by CI after push) | Yes | Free developer account + ID verification |
| Samsung Galaxy Store | APK or AAB | Yes | Free seller account + ID verification (D-U-N-S risk — see above) |
| Aptoide | Signed APK (same file as Amazon) | Yes | Free Aptoide Connect account + ownership-proof email |

**Nothing was submitted or uploaded to any store. No developer account
was created. No money was spent.**

## Submission checklist (for the parent / user, when the time comes)

- [ ] CI build green on GitHub Actions (push the `v21-fix-wakeword` branch)
- [ ] `gold-ai-play-release-aab` + `gold-ai-play-release-apk` artifacts downloaded
- [ ] Both artifacts signed with the release keystore (needs the 4 GitHub secrets — see SIGNING.md; unsigned otherwise)
- [ ] V21 Hindi-voice phone test passed (unproven — do the final release only after it)
- [ ] Screenshots captured on the real phone (6 shots, 1080×1920) + resized per store
- [ ] Feature graphic 1024×500 + Amazon promo image 1024×500 made from the golden orb art
- [ ] Privacy policy published at a public URL (GitHub Pages suggested) and linked in every listing
- [ ] Google Play: $25 fee arranged first — otherwise skip Play and ship the other four
- [ ] Same keystore for every store and every future update — never lose it
