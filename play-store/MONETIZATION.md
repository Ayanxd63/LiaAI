# Gold AI — Monetization (V22, 2026-11-24; subscription removed 2026-11-24)

**Status: ads-only, REAL AdMob IDs wired in (2026-11-24), $0 spent, nothing submitted.**
The app is ready to serve real ads once AdMob finishes its account
review (up to 24h) and the release is built. Voice + text chat are
FREE forever and image generation is unlimited — there is no paid
tier and no in-app purchase. No proprietary ad/tracking SDK other
than AdMob was added, so the F-Droid door stays open.

**Subscription REMOVED on the user's order (2026-11-24, "Subscription
abi ad na kro"):** Gold Premium (monthly/yearly via Google Play
Billing), the Premium screen, the 5-images/day free quota and the
rewarded ad were all removed the same day they were added. Files
deleted: `monetization/PremiumStore.kt`,
`monetization/BillingManager.kt` (play + direct),
`ui/screens/premium/PremiumScreen.kt`; the `billing-ktx`
dependency was removed from `build.gradle.kts`. The subscription
can be re-added later from this same design if the user asks —
the product IDs were `gold_premium_monthly` /
`gold_premium_yearly` (never created in Play Console) and the
free quota was 5 images/day with a rewarded ad granting +5.

---

## 1. What remains: AdMob (Google Mobile Ads SDK 23.0.0)
| Placement | Behaviour |
|-----------|-----------|
| **Banner** (adaptive) | Bottom of the Home screen, above the bottom nav. |
| **Interstitial** | After every **3rd** successful image generation, at most **once per 10 minutes**, and **never** while a voice conversation is active. |

- **REAL ad unit IDs** (account created 2026-11-24, publisher
  `pub-8725895970505052`, app "Gold AI"):
  banner `ca-app-pub-8725895970505052/3223978331`, interstitial
  `ca-app-pub-8725895970505052/1392021910`, app ID
  `ca-app-pub-8725895970505052~4537060005` (in `AdConfig.kt` and
  the `APPLICATION_ID` meta-data in AndroidManifest). These serve
  real ads and earn real money — never tap your own ads.
- **Ads are OFF in debug builds** (`BuildConfig.DEBUG` check in
  `AdManager`). An ad failure is always a silent no-op — ads can
  never crash the app or block a feature.
- `AD_ID` permission added (advertising ID, for ads only).

### Files changed / added
- Added: `app/src/main/java/com/goldai/assistant/monetization/`
  `AdConfig.kt`, `AdManager.kt`;
  `play-store/MONETIZATION.md` (this file).
- Edited: `app/build.gradle.kts` (play-services-ads 23.0.0,
  `buildConfig = true`),
  `AndroidManifest.xml` (AD_ID permission + **real** APPLICATION_ID),
  `LiaApplication.kt` (MobileAds init), `HomeScreen.kt` (banner),
  `CreateImageScreen.kt` (interstitial trigger),
  `proguard-rules.pro` (keep monetization package),
  `play-store/listing.md` (data safety),
  `play-store/privacy-policy.md` (AdMob disclosure).
- Voice chain, Hindi TTS and Gold AI branding/credits untouched.
- **Nothing committed, nothing pushed. AdMob account + app + ad
  units created 2026-11-24 (real IDs above).**

---

## 2. What is DONE, and what is still pending

### A. AdMob — DONE 2026-11-24 (free account, $0)
1. ✅ Free AdMob account created (Google account
   pathantechteam@gmail.com, individual, Pakistan).
2. ✅ App added: **Gold AI**, Android, `com.goldai.assistant`
   (unpublished — link it to the store listing later).
3. ✅ Two ad units created: Banner ("Gold AI Banner") and
   Interstitial ("Gold AI Interstitial").
4. ✅ Real IDs wired into `AdConfig.kt` and the
   `APPLICATION_ID` meta-data in `AndroidManifest.xml`.
5. ⏳ **app-ads.txt (still pending):** AdMob will ask for it. It
   is a one-line text file on the developer's website:
   `google.com, pub-8725895970505052, DIRECT, f08c47fec0942fa0`
   Host it at the root of the site listed as the developer
   website (e.g. the GitHub Pages site that will also host the
   privacy policy). Without it, ads still show, but some buyers
   will not bid — earnings drop.
6. ⏳ **AdMob account review (up to 24h):** the account was just
   created; ads may take up to 24 hours to start serving.
   Rebuild the release (CI) and check the ads show REAL ads (no
   "Test Ad" label). **Never tap your own live ads** — AdMob
   bans accounts for invalid clicks; use test IDs while
   developing.

### B. Payment profile — INCOMPLETE (user does this himself)
- AdMob will not pay until the payment profile is complete: the
  user adds his own bank account + payee details himself in
  **AdMob → Payments** (needed before the first payout, not
  before ads start serving).
- AdMob pays when the balance reaches the **$100 threshold**.
- **Never send bank details, card numbers or passwords to anyone
  — including to the assistant.** Add them only inside AdMob
  yourself, on its own secure page.
- Earnings reality check: a brand-new free app earns very little
  at first. Ads pay per thousand views / per click. Downloads
  alone earn nothing on any store.

### C. AdSense — NOT DONE (needs a website first)
- AdSense is for websites, not the app. It needs a live website
  URL to review. The hosting-panel website is not deployed yet,
  so the AdSense account step waits until the panel site is live.
  No AdSense account was created.

---

## 3. What could NOT be built / verified here

- **No AAB/APK build was possible on this VM** (no JDK, no Android
  SDK — same as the rebrand). The next step is the GitHub Actions
  run after push (`bundlePlayRelease` + `assemblePlayRelease`).
  The new Gradle dependency (play-services-ads 23.0.0) will
  resolve there; if a version fails to resolve, the workflow log
  will say which one — bump it to the current release on Google
  Maven and retry.
- **Kotlin was verified by review only** (no compiler on this VM):
  brace/import/API cross-checks were done by hand (see §4). The
  first CI build is the real compile check — fix whatever it
  reports, the code is structured so a failure will be localised
  to the monetization package.
- **Ads were NOT run on a phone.** Real ads are the expected
  behaviour now that real IDs are wired in — but only after the
  CI build and AdMob's up-to-24h account review.
- **Hindi voice is still unproven on the user's real phone**
  (V21 phone test pending) — same caveat as the rebrand: do the
  final release only after that test passes.

## 4. Verification done here (no compiler)

- All monetization Kotlin files: balanced braces/parens
  (script-checked), imports cross-checked against the files they
  reference (`VoiceSessionManager.isActive`, `AdManager`,
  `AdConfig`).
- AdMob API calls match play-services-ads 23.0.0
  (`MobileAds.initialize`, `AdView` + adaptive `AdSize`,
  `InterstitialAd.load` / `show`).
- Manifest: `AD_ID` permission + `APPLICATION_ID` meta-data are
  inside `<application>`; the test app ID is Google's sample ID.
- No remaining live references to the removed subscription:
  grep for `BillingManager|PremiumScreen|PremiumStore|
  gold_premium_monthly|gold_premium_yearly|RewardedAd|REWARDED`
  returns zero hits in `app/` (code + Gradle + manifest). The
  only mentions of the old names anywhere are the removal note at
  the top of this file (history, not live code).
- The real ad unit IDs now appear ONLY in `AdConfig.kt` and the
  manifest APPLICATION_ID (intended — the app needs them to serve
  ads). No keystore password, no bank detail and no password
  appears anywhere in this work.
