package com.example.safetymonitor

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.ui.WorkSafePage
import com.example.safetymonitor.ui.WorkSafeViewModel
import com.example.safetymonitor.wear.WatchSummaryEvent
import com.example.safetymonitor.wear.handleWatchSummaryEvents
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.util.UUID

/** Synthetic transport and loopback-only account checks; no watch or remote server is used. */
object WatchLinkChecks {
    private const val origin = "watch-node"
    private const val path = "/workersafety/summary/receipt"
    private fun session(expiry: Instant = Instant.parse("2099-01-01T00:00:00Z")) =
        SosSession("http://127.0.0.1:1", 77, "synthetic", expiry)

    private fun data(start: Long, end: Long, id: String = UUID.randomUUID().toString()): DataMap = DataMap().apply {
        putString("summary_id", id)
        putLong("window_start", start)
        putLong("timestamp", end)
        putInt("interval_minutes", 5)
        putBoolean("has_hr", true)
        putBoolean("has_skin", true)
        putBoolean("has_ambient", true)
        putInt("hr_count", 2)
        putInt("skin_count", 2)
        putDouble("hr_avg", 78.5)
        putDouble("skin_c", 33.2)
        putDouble("ambient_c", 22.0)
    }

    fun run(instrumentation: Instrumentation, results: StringBuilder) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        fun connect(value: SosSession?) = instrumentation.runOnMainSync { SosStore.connect(value) }
        data class Receipt(val node: String, val path: String, val bytes: ByteArray)
        fun deliver(map: DataMap, node: String = origin, reachable: () -> Set<String> = { setOf(origin) },
                    uri: Uri = Uri.parse("wear://$node/workersafety/summary"), type: Int = DataEvent.TYPE_CHANGED,
                    failSend: Boolean = false): List<Receipt> {
            val receipts = mutableListOf<Receipt>()
            handleWatchSummaryEvents(sequenceOf(WatchSummaryEvent(type, uri) { map }), reachable) { n, p, b ->
                receipts.add(Receipt(n, p, b))
                if (failSend) throw IllegalStateException("synthetic send failure")
            }
            instrumentation.waitForIdleSync()
            return receipts
        }
        fun expect(map: DataMap, result: String, node: String = origin, reachable: () -> Set<String> = { setOf(node) }) {
            val receipts = deliver(map, node, reachable)
            check(receipts.size == 1)
            val receipt = receipts.single()
            check(receipt.node == node && receipt.path == path)
            check(receipt.bytes.contentEquals("${map.getString("summary_id")}|$result".toByteArray(Charsets.UTF_8)))
        }
        try {
            connect(null)
            val start = System.currentTimeMillis() + 1_000
            val first = data(start, start + 1_000)
            expect(first, "SIGN_IN_REQUIRED")
            connect(session(Instant.EPOCH))
            expect(first, "SIGN_IN_REQUIRED")
            val active = session()
            connect(active)
            check(deliver(first, reachable = { emptySet() }).isEmpty())
            check(deliver(first, reachable = { throw IllegalStateException("lookup failed") }).isEmpty())
            check(deliver(first, node = "other", reachable = { setOf(origin) }).isEmpty())
            check(deliver(first, uri = Uri.parse("wear://$origin/workersafety/summary/other")).isEmpty())
            check(deliver(first, type = DataEvent.TYPE_DELETED).isEmpty())
            check(deliver(data(start, start + 1_000, "not-a-uuid")).isEmpty())
            check(active.watchInbox.latest.value == null)
            expect(data(1, 2), "NEW_WINDOW_REQUIRED")
            val invalids = listOf<(DataMap) -> Unit>(
                { it.putString("window_start", "wrong") }, { it.remove("timestamp") },
                { it.putLong("timestamp", start) }, { it.putInt("interval_minutes", 3) },
                { it.putString("has_hr", "true") }, { it.putInt("hr_count", 0) },
                { it.remove("skin_c") }, { it.putDouble("hr_avg", Double.NaN) },
                { it.putDouble("skin_c", Double.POSITIVE_INFINITY) }, { it.putDouble("ambient_c", 71.0) },
                { it.putDouble("hr_avg", 34.0) }, { it.putDouble("skin_c", 51.0) },
                { it.putLong("timestamp", System.currentTimeMillis() + 121_000) }
            )
            invalids.forEach { mutate -> data(start, start + 1_000).also { mutate(it); expect(it, "INVALID_DATA") } }
            expect(first, "ACCEPTED")
            val summary = checkNotNull(active.watchInbox.latest.value)
            check(summary.averageHeartRate == 78.5 && summary.wristSkinTemperatureC == 33.2 && summary.ambientTemperatureC == 22.0)
            expect(first, "ACCEPTED")
            check(active.watchInbox.latest.value === summary)
            val changed = data(start, start + 1_000, first.getString("summary_id")!!).apply { putDouble("hr_avg", 79.0) }
            expect(changed, "INVALID_DATA")
            expect(data(start + 1_000, start + 2_000), "WATCH_MISMATCH", "foreign")
            expect(data(start, start + 2_000), "NEW_WINDOW_REQUIRED")
            val next = data(start + 1_000, start + 2_000).apply {
                putBoolean("has_hr", false); putInt("hr_count", 0); remove("hr_avg")
            }
            expect(next, "ACCEPTED")
            check(active.watchInbox.latest.value?.averageHeartRate == null && active.watchInbox.latest.value?.wristSkinTemperatureC == 33.2)
            val empty = data(start + 2_000, start + 3_000).apply {
                putBoolean("has_hr", false); putBoolean("has_skin", false); putBoolean("has_ambient", false)
                putInt("hr_count", 0); putInt("skin_count", 0); remove("hr_avg"); remove("skin_c"); remove("ambient_c")
            }
            expect(empty, "ACCEPTED")
            check(active.watchInbox.latest.value?.let { it.averageHeartRate == null && it.wristSkinTemperatureC == null && it.ambientTemperatureC == null } == true)
            val failedReceipt = data(start + 3_000, start + 4_000)
            check(deliver(failedReceipt, failSend = true).size == 1)
            check(active.watchInbox.latest.value?.summaryId == failedReceipt.getString("summary_id"))
            val replacement = session()
            val raced = data(start + 4_000, start + 5_000)
            expect(raced, "SIGN_IN_REQUIRED", reachable = { connect(null); connect(replacement); setOf(origin) })
            check(replacement.watchInbox.latest.value == null && active.watchInbox.latest.value?.summaryId == failedReceipt.getString("summary_id"))
            expect(data(1, 2), "NEW_WINDOW_REQUIRED")
            results.appendLine("PASS: typed DataMap, ordered policy, replay, receipts, lookup/session races and send failure")
            connect(null)
            checkHome(instrumentation, results, ::deliver)
        } finally {
            connect(null)
        }
    }

    private fun checkHome(
        instrumentation: Instrumentation,
        results: StringBuilder,
        deliver: (DataMap, String, () -> Set<String>, Uri, Int, Boolean) -> List<*>
    ) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("worksafe_theme", 0)
        val originalServer = prefs.getString("sos_server", null)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var model: WorkSafeViewModel? = null
        val evidence = File(checkNotNull(context.getExternalFilesDir(null)), "watch-link").apply { mkdirs() }
        try {
            instrumentation.runOnMainSync { model = ViewModelProvider(activity as MainActivity)[WorkSafeViewModel::class.java] }
            val m = checkNotNull(model)
            runBlocking { m.logout() }
            AccountIntegrationChecks.loginFixture(m)
            instrumentation.waitForIdleSync()
            check(m.page == WorkSafePage.HOME)
            fun tree(node: AccessibilityNodeInfo?, lines: MutableList<String>) {
                if (node == null) return
                node.text?.toString()?.let { lines.add(it) }
                node.contentDescription?.toString()?.let { lines.add(it) }
                for (i in 0 until node.childCount) tree(node.getChild(i), lines)
            }
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                return false
            }
            fun capture(name: String) {
                var text = ""
                repeat(20) {
                    instrumentation.waitForIdleSync()
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val lines = mutableListOf<String>(); tree(root, lines); text = lines.joinToString("\n")
                    scroll(root)
                    Thread.sleep(150)
                }
                File(evidence, "$name.txt").writeText(text)
                instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                    checkNotNull(bitmap)
                    File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                check(text.contains("센서 정보")) { "$name did not reach the sensor card; $text" }
            }
            capture("before")
            val start = System.currentTimeMillis() + 1_000
            val map = data(start, start + 1_000)
            check(deliver(map, origin, { setOf(origin) }, Uri.parse("wear://$origin/workersafety/summary"), DataEvent.TYPE_CHANGED, false).size == 1)
            capture("after")
            check(m.sosSession?.watchInbox?.latest?.value?.let {
                it.averageHeartRate == 78.5 && it.wristSkinTemperatureC == 33.2
            } == true)
            runBlocking { m.logout() }
            AccountIntegrationChecks.loginFixture(m)
            instrumentation.waitForIdleSync()
            check(m.page == WorkSafePage.HOME && m.sosSession?.watchInbox?.latest?.value == null)
            capture("relogin")
            results.appendLine("PASS: real HOME screenshots captured; synthetic session values and relogin inbox checked; evidence ${evidence.absolutePath}")
        } finally {
            runBlocking { model?.logout() }
            instrumentation.runOnMainSync { activity.finish() }
            prefs.edit().apply { if (originalServer == null) remove("sos_server") else putString("sos_server", originalServer) }.commit()
        }
    }
}
