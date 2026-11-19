package com.example.safetymonitor.watch

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.safetymonitor.sos.SosSession
import com.example.workersafety.link.WatchLinkProtocol
import com.example.workersafety.link.WatchLinkProtocol.Result
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class WatchSnapshot(
    val connection: String = "로그인 후 연결 확인",
    val summary: WatchSummary? = null,
    val receivedAt: Long? = null,
    val notice: String = "워치에서 측정을 시작해주세요."
)

/** Latest reading only, in memory, with exactly the same lifetime as the server session. */
object WatchDataRepository {
    private val mutable = MutableStateFlow(WatchSnapshot())
    val state = mutable.asStateFlow()
    private var session: SosSession? = null
    private var readings = WatchReadingWindow(Long.MAX_VALUE)
    // Serialize callbacks and refreshes; never drop a data event during an in-flight refresh.
    private val ioLock = ReentrantLock()

    @Synchronized fun bind(value: SosSession?) {
        if (session === value) return
        session = value
        readings = WatchReadingWindow(System.currentTimeMillis())
        mutable.value = WatchSnapshot(connection = if (value == null) "로그인 후 연결 확인" else "워치 연결 확인 중")
    }

    @Synchronized private fun current(): SosSession? = session?.takeIf { it.valid() }

    @Synchronized private fun update(target: SosSession?, change: (WatchSnapshot) -> WatchSnapshot) {
        if (target != null && session === target && target.valid()) mutable.value = change(mutable.value)
    }

    @Synchronized private fun accept(target: SosSession?, node: String, values: Map<String, Any?>): Result {
        if (target == null || session !== target || !target.valid()) return Result.SIGN_IN_REQUIRED
        if (readings.source != null && readings.source != node) return Result.WATCH_MISMATCH
        val now = System.currentTimeMillis()
        val sample = WatchSummary.parse(values, now)
        if (sample == null) {
            mutable.value = mutable.value.copy(notice = "워치 데이터 형식 또는 기기 시각을 확인해주세요.")
            return Result.INVALID_DATA
        }
        if (sample.windowStart < readings.signedInAt) {
            mutable.value = mutable.value.copy(notice = "로그인 이후의 새 측정 구간을 기다리고 있습니다.")
            return Result.NEW_WINDOW_REQUIRED
        }
        if (readings.accept(node, sample)) {
            mutable.value = mutable.value.copy(summary = sample, receivedAt = now,
                notice = "${sample.intervalMinutes}분 단위 측정 요약 · 실시간 값이 아닙니다.")
        }
        // Re-acknowledge identical cached data after a lost reply, but not older/replaced data.
        return if (readings.source == node && readings.latest == sample) Result.ACCEPTED else Result.INVALID_DATA
    }

    private fun reachableWatch(context: Context, target: SosSession?): Node? {
        val capability = Tasks.await(Wearable.getCapabilityClient(context).getCapability(
            WatchSummary.CAPABILITY, CapabilityClient.FILTER_REACHABLE), 5, TimeUnit.SECONDS)
        val node = capability.nodes.singleOrNull()
        if (node == null) {
            update(target) { it.copy(connection = if (capability.nodes.isEmpty())
                "미연결 · 워치 앱·페어링 확인" else "여러 워치 연결됨 · 한 대만 연결해주세요") }
            return null
        }
        val differentSource = synchronized(this) { session === target &&
            readings.source != null && readings.source != node.id }
        update(target) { it.copy(connection = when {
            differentSource -> "워치 변경됨 · 다시 로그인해주세요"
            node.isNearby -> "연결됨 · 가까운 기기"
            else -> "연결됨 · 원격 경로"
        }) }
        return node
    }

    private fun consume(context: Context, target: SosSession?, node: String, values: Map<String, Any?>) {
        val result = accept(target, node, values)
        val id = values[WatchLinkProtocol.SUMMARY_ID] as? String
        if (!WatchLinkProtocol.validId(id)) return // Older watches remain compatible.
        try {
            Tasks.await(Wearable.getMessageClient(context).sendMessage(node,
                WatchLinkProtocol.RECEIPT_PATH,
                WatchLinkProtocol.encode(WatchLinkProtocol.Receipt(id!!, result))), 5, TimeUnit.SECONDS)
        } catch (ex: Exception) {
            // The reading remains accepted; the next refresh retries the receipt.
            Log.w("WatchData", "Receipt could not be delivered", ex)
        }
    }

    /** Called only on the WearableListenerService worker thread, including while logged out. */
    fun receiveBlocking(context: Context, node: String, values: Map<String, Any?>) = ioLock.withLock {
        val target = current()
        try {
            if (reachableWatch(context, target)?.id == node) consume(context, target, node, values)
        } catch (ex: Exception) {
            Log.w("WatchData", "Unable to verify watch source", ex)
        }
    }

    /** Call on IO or a WearableListenerService callback thread, never on the main thread. */
    fun refreshBlocking(context: Context) = ioLock.withLock {
        val target = current() ?: return@withLock
        try {
            val node = reachableWatch(context, target) ?: return@withLock
            val uri = Uri.Builder().scheme("wear").authority(node.id).path(WatchSummary.PATH).build()
            val items = Tasks.await(Wearable.getDataClient(context).getDataItems(uri), 5, TimeUnit.SECONDS)
            try {
                for (item in items) {
                    if (item.uri.path != WatchSummary.PATH || item.uri.host != node.id) continue
                    val map = DataMapItem.fromDataItem(item).dataMap
                    consume(context, target, node.id, map.keySet().associateWith { map.get<Any>(it) })
                }
                if (items.count == 0) update(target) { it.copy(
                    notice = "워치에서 측정을 시작한 후 ‘지금 전송’을 눌러주세요.") }
            } finally { items.release() }
        } catch (ex: Exception) {
            Log.w("WatchData", "Refresh failed", ex)
            update(target) { it.copy(connection = "연결 확인 실패 · Google Play 서비스와 워치 설정 확인") }
        }
    }
}
