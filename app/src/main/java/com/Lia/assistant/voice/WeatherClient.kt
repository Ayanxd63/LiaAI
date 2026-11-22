package com.Lia.assistant.voice

import android.content.Context
import android.location.LocationManager
import com.Lia.assistant.data.NovaPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V16 (P1-6) — weather card, keyless.
 *
 * Source: Open-Meteo (https://open-meteo.com) — free, no API key, no
 * account, no login. One GET with latitude/longitude returns the
 * current temperature, weather code and humidity. Location comes only
 * from the device's last-known location and only when the user granted
 * location permission; without it the card says so plainly and offers
 * to ask for it. The last good result is cached on the phone so the
 * Home card is never blank after the first successful fetch.
 *
 * No new dependency: OkHttp + org.json, both already in the app.
 */
data class WeatherNow(
    val tempC: Double,
    val code: Int,
    val humidity: Int?,
    val fetchedAtMs: Long
) {
    val description: String get() = WeatherClient.weatherDescription(code)
}

object WeatherClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** WMO weather codes, in plain words. */
    fun weatherDescription(code: Int): String = when (code) {
        0 -> "Saaf aasmaan"
        1 -> "Halke badal"
        2 -> "Badal chhaye hue"
        3 -> "Poori tarah badal"
        45, 48 -> "Dhund"
        51, 53, 55 -> "Halki boondabandi"
        56, 57 -> "Thandi boondabandi"
        61 -> "Halki barish"
        63 -> "Barish"
        65 -> "Tez barish"
        66, 67 -> "Thandi barish"
        71 -> "Halki barf"
        73 -> "Barf"
        75 -> "Tez barf"
        77 -> "Barf ke daane"
        80 -> "Halki bochhar"
        81, 82 -> "Bochhar"
        85, 86 -> "Barf ki bochhar"
        95 -> "Toofan"
        96, 99 -> "Ole ke saath toofan"
        else -> "Mausam"
    }

    /**
     * Last-known device location (coarse is enough for weather).
     * Returns null when location permission is missing or no fix is
     * cached — the caller shows the graceful "no location" state then.
     */
    fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        return try {
            val manager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER
            )
            for (provider in providers) {
                val location = runCatching {
                    manager.getLastKnownLocation(provider)
                }.getOrNull() ?: continue
                return Pair(location.latitude, location.longitude)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Fetch current weather for [latitude]/[longitude]. Never throws. */
    suspend fun fetch(
        context: Context,
        latitude: Double,
        longitude: Double
    ): Result<WeatherNow> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    "https://api.open-meteo.com/v1/forecast?latitude=$latitude" +
                        "&longitude=$longitude&current=temperature_2m,weather_code," +
                        "relative_humidity_2m&timezone=auto"
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Weather service: HTTP ${response.code}")
                    }
                    val body = response.body?.string()
                        ?: throw IllegalStateException("Weather service: empty reply")
                    val current = JSONObject(body).getJSONObject("current")
                    val now = WeatherNow(
                        tempC = current.getDouble("temperature_2m"),
                        code = current.getInt("weather_code"),
                        humidity = if (current.has("relative_humidity_2m")) {
                            current.optInt("relative_humidity_2m")
                        } else {
                            null
                        },
                        fetchedAtMs = System.currentTimeMillis()
                    )
                    saveCached(context, now)
                    now
                }
            }
        }
    }

    /** Last good result, or null when weather was never fetched. */
    fun loadCached(context: Context): WeatherNow? {
        val raw = NovaPreferences.getString(
            context,
            NovaPreferences.Keys.WEATHER_CACHE_JSON,
            ""
        )
        if (raw.isBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            WeatherNow(
                tempC = obj.getDouble("t"),
                code = obj.getInt("c"),
                humidity = if (obj.has("h")) obj.optInt("h") else null,
                fetchedAtMs = obj.optLong(
                    "at",
                    NovaPreferences.getString(
                        context,
                        NovaPreferences.Keys.WEATHER_CACHE_TIME,
                        "0"
                    ).toLongOrNull() ?: 0L
                )
            )
        }.getOrNull()
    }

    private fun saveCached(context: Context, now: WeatherNow) {
        val obj = JSONObject().apply {
            put("t", now.tempC)
            put("c", now.code)
            if (now.humidity != null) put("h", now.humidity)
            put("at", now.fetchedAtMs)
        }
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.WEATHER_CACHE_JSON,
            obj.toString()
        )
        NovaPreferences.putString(
            context,
            NovaPreferences.Keys.WEATHER_CACHE_TIME,
            now.fetchedAtMs.toString()
        )
    }
}
