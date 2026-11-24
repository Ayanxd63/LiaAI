package com.goldai.assistant.monetization

/**
 * V22 monetization — AdMob ad unit IDs.
 *
 * These are the REAL IDs for the user's own AdMob account
 * (created 2026-11-24; app "Gold AI", package com.goldai.assistant,
 * publisher pub-8725895970505052). Real IDs serve real ads and earn
 * real money — never click your own ads, never ask others to click
 * them, and never run a build with these IDs on an emulator during
 * development: AdManager disables all ads in debug builds
 * (BuildConfig.DEBUG) so a test build can never generate invalid
 * live-ad traffic.
 *
 * STILL PENDING (see play-store/MONETIZATION.md):
 *   - AdMob account review (up to 24h after the account was made).
 *   - Payment profile incomplete — the user adds his own bank/payee
 *     details himself in AdMob (never in chat).
 *   - Add app-ads.txt to the developer website.
 *
 * The rewarded ad and the Gold Premium subscription were removed
 * on the user's order (2026-11-24) — ads-only monetization now.
 * Image generation is unlimited, so no rewarded bonus exists.
 *
 * NEVER invent an ID, never paste anyone else's ID.
 */
object AdConfig {

    /** Real banner ad unit ID ("Gold AI Banner", created 2026-11-24). */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-8725895970505052/3223978331"

    /** Real interstitial ad unit ID ("Gold AI Interstitial", created 2026-11-24). */
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8725895970505052/1392021910"

    /** Show an interstitial after every N successful image generations. */
    const val INTERSTITIAL_EVERY_N_IMAGES = 3

    /** Minimum gap between two interstitials (10 minutes). */
    const val INTERSTITIAL_MIN_GAP_MILLIS = 10L * 60L * 1000L
}
