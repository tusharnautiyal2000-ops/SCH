package com.personal.wascheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms alarms after a reboot or app update. One-off messages whose time passed while the phone was off are marked MISSED. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val now = System.currentTimeMillis()
        for (m in Store.load(ctx)) {
            if (m.status != "PENDING") continue
            if (m.timeMillis > now) {
                Scheduler.schedule(ctx, m)
            } else if (m.repeatMs > 0) {
                // Skip the occurrences missed while the phone was off and continue the series.
                val next = m.timeMillis + ((now - m.timeMillis) / m.repeatMs + 1) * m.repeatMs
                val updated = m.copy(timeMillis = next)
                Store.replace(ctx, updated)
                Scheduler.schedule(ctx, updated)
            } else {
                Store.setStatus(ctx, m.id, "MISSED")
            }
        }
    }
}
