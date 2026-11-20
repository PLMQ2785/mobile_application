package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.wear.WatchSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WatchVitalsContent(session: SosSession?) {
    if (session == null) {
        WatchVitalsRows(null)
        Text(
            "로그인한 뒤 워치에서 새 측정을 시작하면 요약이 표시됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val summary by session.watchInbox.latest.collectAsState()
    WatchVitalsRows(summary)
    if (summary == null) {
        Text(
            "로그인한 뒤 워치에서 새 측정을 시작하면 요약이 표시됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WatchVitalsRows(summary: WatchSummary?) {
    val measuredAt = if (summary == null) {
        "—"
    } else {
        remember(summary.windowEndMs) {
            SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(summary.windowEndMs))
        }
    }
    WatchVitalsRow("최근 측정 시각", measuredAt)
    WatchVitalsRow(
        "평균 심박수",
        summary?.averageHeartRate?.let { String.format(Locale.KOREA, "%.1f bpm", it) } ?: "—"
    )
    WatchVitalsRow(
        "손목 피부온도",
        summary?.wristSkinTemperatureC?.let { String.format(Locale.KOREA, "%.1f °C", it) } ?: "—"
    )
}

@Composable
private fun WatchVitalsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}
