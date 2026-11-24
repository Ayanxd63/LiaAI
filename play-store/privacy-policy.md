# Privacy Policy — Gold AI

**Effective date:** 24 November 2026
**App:** Gold AI (package `com.goldai.assistant`)
**Developer:** Ayan XD
**Contact:** pathantechteam@gmail.com

Gold AI is a voice-first AI companion for Android. This policy explains what
the app does with your information. In short: **we (the developer) collect
nothing ourselves and run no server — your API key never leaves your phone.
Advertising (Google AdMob) is handled by Google, as described in
section 6 below. There are no in-app purchases and no subscription.**

## 1. Information we collect

**We (the developer) collect no personal information.** Gold AI has no
account system and no analytics. There is no server belonging to the
developer that receives your data. **Advertising is served by Google AdMob
(see section 6)** — that is Google's service, not ours, and it has its
own privacy policy.

## 2. Your Gemini API key

Gold AI talks to Google's Gemini API using **your own free Gemini API key**,
which you enter once in Settings.

- The key is stored **encrypted on your device only** (Android Keystore /
  EncryptedSharedPreferences). It is never sent to the developer, never
  uploaded anywhere, and never included in crash reports.
- You can delete the key at any time from Settings. Uninstalling the app
  removes it completely.

## 3. Voice, text and other data sent to Google

When you talk or type to Lia, your voice audio and text are sent to
**Google's Gemini API** (generativeai.googleapis.com) so the AI can reply.
This happens under **your own API key and your own Google account**, and is
governed by Google's privacy policy and the Google AI Studio terms — not by
us. We do not record, store or receive that audio or text on any developer
server.

- Microphone is used only during an active voice session, and only when you
  start one. Audio is streamed in real time and is not saved on the device.
- Conversations shown in the chat screen are stored **locally on your
  device only**. You can clear them from Settings.

## 4. On-device downloads

Some voice features download model files to your phone (for example the
Hindi voice model, about 139 MB, and the wake-word model, about 40 MB).
These files come from their providers' servers, stay on your device, and
contain no personal information.

## 5. Contacts, location, camera and other permissions

- **Contacts / phone calls:** used only on your device, only when you ask
  Lia to call or message someone by name. Contact names and numbers are
  never sent anywhere.
- **Approximate location:** used only on your device for the weather card
  (city level). No fine location is collected.
- **Camera:** used only for the flashlight. The app does not take photos.
- **Files you share with the app** (for example a document to summarise)
  are processed on your device or through the Gemini API under your key,
  as in section 3.

## 6. Advertising (Google AdMob)

Gold AI shows ads served by **Google AdMob** (Google Mobile Ads
SDK).

- **What AdMob collects:** your device's **Advertising ID** and
  **approximate location** (city level), used to serve and measure ads.
  AdMob may also collect device information and ad-interaction data, as
  described in Google's privacy policy.
- **What is shared:** the Advertising ID and approximate location are
  shared **with Google, for advertising only**. Nothing is shared with
  the developer or with any other advertiser directly by this app.
- **Your control:** you can reset your Advertising ID or opt out of
  personalised ads in **Android Settings > Privacy > Ads**.
- **Test ads during development:** builds made for testing use Google's
  official test ad IDs and show labelled test ads that earn nothing.

## 7. Data deletion

- Delete your API key and chat history from **Settings** inside the app.
- Uninstalling Gold AI deletes all app data from your device, including
  the key, history and downloaded voice models.

## 8. Children

Gold AI is not directed at children under 13. No personal information is
collected from anyone, including children. Ads served by AdMob follow
Google's families/ads policies for the app's content rating.

## 9. Changes to this policy

If this policy changes, the new version will be posted at the same URL and
the effective date above will be updated.

## 10. Contact

Questions about this policy: **pathantechteam@gmail.com**

---

*Note for submission: this file must be published at a public URL before
any store submission (Google Play requires it; the other stores accept a
link too). Suggested: GitHub Pages on the repo, e.g.
`https://Ayanxd63.github.io/GoldAI/privacy-policy.html`. Do not paste the
raw file as the policy URL — it must be a public web page.*
