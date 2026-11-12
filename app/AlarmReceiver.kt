package com.personal.wascheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        val m = Store.get(ctx, id) ?: return
        if (m.status != "PENDING") return

        val attempt: ScheduledMsg
        if (m.repeatMs > 0) {
            // Repeating series: arm the NEXT occurrence first (so the series survives any failure),
            // then send a one-off copy of the message right now.
            val now = System.currentTimeMillis()
            var next = m.timeMillis + m.repeatMs
            if (next <= now + 10_000L) next += ((now + 10_000L - next) / m.repeatMs + 1) * m.repeatMs
            val updated = m.copy(timeMillis = next)
            Store.replace(ctx, updated)
            Scheduler.schedule(ctx, updated)

            var childId = now
            if (childId == m.id) childId++
            attempt = m.copy(id = childId, timeMillis = now, status = "SENDING", repeatMs = 0L)
            Store.add(ctx, attempt)
            Store.prune(ctx)
        } else {
            Store.setStatus(ctx, id, "SENDING")
            attempt = m.copy(status = "SENDING")
        }

        DebugDump.clear(ctx)
        SendCoordinator.begin(attempt.id, if (attempt.isGroup) attempt.label else null)
        Notifier.wakeScreen(ctx)

        val open = Wa.chatIntent(ctx, attempt)
        // Fallback: if Android blocks the automatic launch, tapping this notification opens the chat.
        Notifier.post(ctx, attempt, open)
        try {
            ctx.startActivity(open)
        } catch (e: Exception) {
            // notification above is the fallback
        }
    }
}
