package com.Lia.assistant.action

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/**
 * V14 (P2) — which app is in front, and simple screen-time answers.
 *
 * Needs PACKAGE_USAGE_STATS ("Usage access"), a special permission the
 * user grants in Settings → Usage access. Play allows it for a genuine
 * use like this one ("konsi app khuli hai", "aaj YouTube kitni der
 * chalaya") — it only needs a privacy-policy mention (see the V14 plan).
 * Usage data is read on demand and never stored or uploaded.
 */
object UsageStatsHelper {

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Package name of the app most recently in front, or null. */
    fun currentAppPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            end - 60_000L,
            end
        ) ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    /** Top [limit] apps by foreground time since midnight: (package, minutes). */
    fun topAppsToday(context: Context, limit: Int = 3): List<Pair<String, Long>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            start,
            System.currentTimeMillis()
        ) ?: return emptyList()
        return stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .map { it.packageName to (it.totalTimeInForeground / 60_000L) }
    }

    /** Human app name for a package, or the package itself. */
    fun appLabel(context: Context, packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
