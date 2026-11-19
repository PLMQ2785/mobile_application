package com.example.safetymonitor.watch

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/** Google Play services verifies the peer package and signing certificate. */
class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            val item = event.dataItem
            if (event.type != DataEvent.TYPE_CHANGED || item.uri.path != WatchSummary.PATH) continue
            val node = item.uri.host ?: continue
            // Copy values while the event buffer is still valid.
            val map = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull() ?: continue
            WatchDataRepository.receiveBlocking(applicationContext, node,
                map.keySet().associateWith { map.get<Any>(it) })
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name == WatchSummary.CAPABILITY) WatchDataRepository.refreshBlocking(applicationContext)
    }
}
