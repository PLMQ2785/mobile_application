package com.example.safetymonitor.wear

import com.google.android.gms.wearable.DataMap

data class WatchSummary(
    val summaryId: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val intervalMinutes: Int,
    val averageHeartRate: Double?,
    val wristSkinTemperatureC: Double?,
    val ambientTemperatureC: Double?,
    val heartRateCount: Int,
    val temperatureCount: Int
)

internal fun parseWatchSummary(data: DataMap, summaryId: String): WatchSummary? {
    // Read raw values: typed DataMap getters can silently substitute defaults.
    val start = data.get<Any>("window_start") as? Long ?: return null
    val end = data.get<Any>("timestamp") as? Long ?: return null
    val interval = data.get<Any>("interval_minutes") as? Int ?: return null
    if (start <= 0L || end <= start || (interval != 5 && interval != 10)) return null

    val hasHr = data.get<Any>("has_hr") as? Boolean ?: return null
    val hasSkin = data.get<Any>("has_skin") as? Boolean ?: return null
    val hasAmbient = data.get<Any>("has_ambient") as? Boolean ?: return null
    val hrCount = data.get<Any>("hr_count") as? Int ?: return null
    val skinCount = data.get<Any>("skin_count") as? Int ?: return null
    if (hrCount < 0 || skinCount < 0 || hasHr != (hrCount > 0) || hasSkin != (skinCount > 0)) return null
    if (hasAmbient && !hasSkin) return null
    if (hasHr != data.containsKey("hr_avg") || hasSkin != data.containsKey("skin_c") ||
        hasAmbient != data.containsKey("ambient_c")) return null

    val hr = if (hasHr) {
        (data.get<Any>("hr_avg") as? Double)?.takeIf { it.isFinite() && it in 35.0..230.0 } ?: return null
    } else null
    val skin = if (hasSkin) {
        (data.get<Any>("skin_c") as? Double)?.takeIf { it.isFinite() && it in 10.0..50.0 } ?: return null
    } else null
    val ambient = if (hasAmbient) {
        (data.get<Any>("ambient_c") as? Double)?.takeIf { it.isFinite() && it in -30.0..70.0 } ?: return null
    } else null

    return WatchSummary(summaryId, start, end, interval, hr, skin, ambient, hrCount, skinCount)
}
