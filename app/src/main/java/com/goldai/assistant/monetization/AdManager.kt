package com.goldai.assistant.monetization

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.goldai.assistant.BuildConfig
import com.goldai.assistant.voice.VoiceSessionManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * V22 monetization — AdMob wrapper (banner + interstitial).
 * ADS ONLY: the Gold Premium subscription was removed on the
 * user's order (2026-11-24, "Subscription abi ad na kro"), along
 * with the rewarded ad (its only reward was +5 images against a
 * quota that no longer exists) and the 5-images/day limit.
 * Image generation is unlimited again.
 *
 * SAFETY RULES, enforced here so no screen can get them wrong:
 * - Ads are OFF in debug builds (BuildConfig.DEBUG) — a developer
 *   build can never generate live-ad traffic or invalid clicks.
 * - Test ad unit IDs only (AdConfig) until the user replaces them
 *   with his own real IDs — see play-store/MONETIZATION.md.
 * - Every ad call is wrapped in try/catch: an ad failure is a
 *   silent no-op, never a crash, never a blocked feature.
 * - Interstitials NEVER interrupt an active voice conversation:
 *   [onImageGenerated] returns at once while
 *   VoiceSessionManager.isActive is true.
 */
object AdManager {

    private const val TAG = "GoldAds"

    // Local interstitial pacing, stored on the phone only.
    // (The counters moved here when the subscription was removed.)
    private const val PREFS_NAME = "gold_ads_prefs"
    private const val KEY_IMAGES_SINCE_INTERSTITIAL = "images_since_interstitial"
    private const val KEY_LAST_INTERSTITIAL_AT = "last_interstitial_shown_at"

    private var initialized = false
    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True only when ads may be shown at all right now. */
    fun adsEnabled(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        return true
    }

    /** Call once from LiaApplication.onCreate. No-op in debug builds. */
    fun initialize(context: Context) {
        if (BuildConfig.DEBUG) return
        if (initialized) return
        try {
            MobileAds.initialize(context) {
                initialized = true
                loadInterstitial(context.applicationContext)
                Log.d(TAG, "Mobile Ads initialised")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mobile Ads init failed (ads stay off): ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Banner (adaptive, shown at the bottom of Home)
    // ------------------------------------------------------------------

    /**
     * Adaptive banner. Renders nothing when ads are disabled
     * (debug build) — the caller needs no special case.
     */
    @Composable
    fun AdBanner(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        if (!adsEnabled(context)) return
        val adWidthDp = LocalConfiguration.current.screenWidthDp
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(60.dp),
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(
                        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                            ctx, adWidthDp
                        )
                    )
                    adUnitId = AdConfig.BANNER_AD_UNIT_ID
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { adView -> adView.destroy() }
        )
    }

    // ------------------------------------------------------------------
    // Interstitial (after every 3rd image, max once per 10 minutes)
    // ------------------------------------------------------------------

    private fun loadInterstitial(context: Context) {
        if (interstitialAd != null || interstitialLoading) return
        interstitialLoading = true
        try {
            InterstitialAd.load(
                context,
                AdConfig.INTERSTITIAL_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitialAd = ad
                        interstitialLoading = false
                        ad.fullScreenContentCallback =
                            object : com.google.android.gms.ads.FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() {
                                    interstitialAd = null
                                    loadInterstitial(context)
                                }
                                override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) {
                                    interstitialAd = null
                                    loadInterstitial(context)
                                }
                            }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitialAd = null
                        interstitialLoading = false
                        Log.d(TAG, "Interstitial failed to load: ${error.message}")
                    }
                }
            )
        } catch (e: Exception) {
            interstitialLoading = false
            Log.w(TAG, "Interstitial load threw: ${e.message}")
        }
    }

    /** Images generated since the last interstitial was shown. */
    private fun imagesSinceInterstitial(context: Context): Int =
        prefs(context).getInt(KEY_IMAGES_SINCE_INTERSTITIAL, 0)

    /** Epoch millis when the last interstitial was shown (0 = never). */
    private fun lastInterstitialShownAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_INTERSTITIAL_AT, 0L)

    /** Call after a successful image generation: counts it and, right
     * after an interstitial is shown, resets the counters. */
    private fun recordImageGenerated(context: Context) {
        val p = prefs(context)
        p.edit()
            .putInt(
                KEY_IMAGES_SINCE_INTERSTITIAL,
                p.getInt(KEY_IMAGES_SINCE_INTERSTITIAL, 0) + 1
            )
            .apply()
    }

    private fun recordInterstitialShown(context: Context) {
        prefs(context).edit()
            .putInt(KEY_IMAGES_SINCE_INTERSTITIAL, 0)
            .putLong(KEY_LAST_INTERSTITIAL_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Call after a successful image generation. Shows an interstitial
     * only when ALL of these hold: ads enabled, an ad is loaded, at
     * least 3 images since the last one, at least 10 minutes since
     * the last one, and NO voice conversation is active.
     */
    fun onImageGenerated(activity: Activity?) {
        val context = activity ?: return
        try {
            if (!adsEnabled(context)) return
            // Never interrupt a live voice conversation.
            if (VoiceSessionManager.isActive.value) return
            recordImageGenerated(context)
            if (imagesSinceInterstitial(context) < AdConfig.INTERSTITIAL_EVERY_N_IMAGES) return
            val gap = System.currentTimeMillis() - lastInterstitialShownAt(context)
            if (gap < AdConfig.INTERSTITIAL_MIN_GAP_MILLIS) return
            val ad = interstitialAd ?: run {
                loadInterstitial(context.applicationContext)
                return
            }
            recordInterstitialShown(context)
            ad.show(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Interstitial show threw: ${e.message}")
        }
    }
}
