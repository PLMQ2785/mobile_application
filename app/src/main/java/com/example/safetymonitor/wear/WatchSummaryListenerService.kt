package com.example.safetymonitor.wear

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.wear.WatchReceiptResult.INVALID_DATA
import com.example.safetymonitor.wear.WatchReceiptResult.SIGN_IN_REQUIRED
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val SUMMARY_PATH = "/workersafety/summary"
private const val RECEIPT_PATH = "/workersafety/summary/receipt"
private const val WATCH_CAPABILITY = "workersafety_watch"
private val mainHandler = Handler(Looper.getMainLooper())

internal enum class WatchReceiptResult {
    ACCEPTED, SIGN_IN_REQUIRED, NEW_WINDOW_REQUIRED, INVALID_DATA, WATCH_MISMATCH
}

class WatchSummaryListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val context = applicationContext
        handleWatchSummaryEvents(
            events.asSequence().map { event ->
                val item = event.dataItem
                WatchSummaryEvent(event.type, item.uri) { DataMapItem.fromDataItem(item).dataMap }
            },
            findReachableWatchNodes = {
                val capability = Wearable.getCapabilityClient(context)
                    .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                Tasks.await(capability, 2, TimeUnit.SECONDS).nodes.mapTo(HashSet()) { it.id }
            },
            sendReceipt = { nodeId, path, payload ->
                Wearable.getMessageClient(context).sendMessage(nodeId, path, payload)
            }
        )
    }
}

internal class WatchSummaryEvent(val type: Int, val uri: Uri, val readDataMap: () -> DataMap)

/** Run on the listener's callback thread. Admission and receipt enqueue run together on main. */
internal fun handleWatchSummaryEvents(
    events: Sequence<WatchSummaryEvent>,
    findReachableWatchNodes: () -> Set<String>,
    sendReceipt: (nodeId: String, path: String, payload: ByteArray) -> Unit
) {
    val capturedSession = SosStore.session.value?.takeIf { it.valid() }
    // One bounded lookup per callback batch, never a cache across callbacks or logins.
    val reachable by lazy(LazyThreadSafetyMode.NONE) {
        try {
            findReachableWatchNodes()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    for (event in events) {
        if (event.type != DataEvent.TYPE_CHANGED) continue
        val uri = event.uri
        val origin = uri.host?.takeIf { it.isNotBlank() } ?: continue
        if (uri.scheme != "wear" || uri.encodedAuthority != origin || uri.encodedPath != SUMMARY_PATH ||
            uri.encodedQuery != null || uri.encodedFragment != null) continue
        if (reachable.isEmpty()) return
        if (origin !in reachable) continue
        val data = try { event.readDataMap() } catch (_: RuntimeException) { continue }
        val id = (data.get<Any>("summary_id") as? String)?.takeIf(::isCanonicalId) ?: continue

        // Account transitions are on main. Never hand a prepared ACCEPTED to a later task.
        mainHandler.post {
            val session = capturedSession?.takeIf { it.isCurrent() }
            val result = if (session == null) SIGN_IN_REQUIRED else {
                val summary = parseWatchSummary(data, id)
                when {
                    !session.isCurrent() -> SIGN_IN_REQUIRED
                    summary == null -> INVALID_DATA
                    else -> session.watchInbox.admit(origin, summary)
                }
            }
            val receipt = if (session?.isCurrent() == true) result else SIGN_IN_REQUIRED
            try {
                sendReceipt(origin, RECEIPT_PATH, "$id|${receipt.name}".toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
                // Best effort only: a transport failure doesn't undo a valid admission.
            }
        }
    }
}

private fun SosSession.isCurrent(): Boolean = SosStore.session.value === this && valid()

private fun isCanonicalId(value: String): Boolean = value.length == 36 && try {
    UUID.fromString(value).toString() == value
} catch (_: IllegalArgumentException) {
    false
}
