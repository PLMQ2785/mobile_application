package com.example.safetymonitor.wear

import com.example.safetymonitor.wear.WatchReceiptResult.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchInbox internal constructor(private val signedInAtMs: Long) {
    private var watchNodeId: String? = null
    private val summaryState = MutableStateFlow<WatchSummary?>(null)
    val latest: StateFlow<WatchSummary?> = summaryState.asStateFlow()

    @Synchronized
    internal fun admit(origin: String, summary: WatchSummary): WatchReceiptResult {
        if (summary.windowEndMs > System.currentTimeMillis() + 120_000L) return INVALID_DATA
        if (summary.windowStartMs < signedInAtMs) return NEW_WINDOW_REQUIRED
        if (watchNodeId != null && watchNodeId != origin) return WATCH_MISMATCH

        val previous = summaryState.value
        if (previous != null) {
            if (summary.summaryId == previous.summaryId) {
                return if (summary == previous) ACCEPTED else INVALID_DATA
            }
            if (summary.windowStartMs < previous.windowEndMs || summary.windowEndMs <= previous.windowEndMs) {
                return NEW_WINDOW_REQUIRED
            }
        }
        watchNodeId = origin
        summaryState.value = summary
        return ACCEPTED
    }
}
