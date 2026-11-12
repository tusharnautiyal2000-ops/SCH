package com.personal.wascheduler

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock

object Scheduler {

    fun schedule(ctx: Context, m: ScheduledMsg) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // setAlarmClock is exempt from Doze and needs no special "exact alarm" permission.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(m.timeMillis, showIntent), pendingFor(ctx, m.id))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingFor(ctx, id))
    }

    private fun pendingFor(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, requestCode(id), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun requestCode(id: Long): Int = (id % Int.MAX_VALUE).toInt()
}

object Wa {
    private val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun chatIntent(ctx: Context, m: ScheduledMsg): Intent {
        val i: Intent
        if (m.isGroup) {
            // Groups have no phone-number link, so open WhatsApp's "Send to..." screen with the text
            // and let the accessibility service pick the group by name.
            i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, m.text)
        } else {
            i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${m.number}?text=${Uri.encode(m.text)}"))
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pm = ctx.packageManager
        val pkg = PACKAGES.firstOrNull {
            try {
                pm.getPackageInfo(it, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
        if (pkg != null) i.setPackage(pkg)
        return i
    }
}

/** Shared between the alarm receiver and the accessibility service (same process). */
object SendCoordinator {
    @Volatile var pendingId: Long = -1L
    @Volatile var groupName: String? = null      // null = normal 1-to-1 message
    @Volatile var groupSelected: Boolean = false
    @Volatile var searchTyped: Boolean = false
    @Volatile var sendClicks: Int = 0
    @Volatile var lastStep: String = ""
    @Volatile var dumpsTaken: Int = 0
    @Volatile var tapAttempts: Int = 0
    @Volatile var lastTapAt: Long = 0L
    val history = StringBuilder()
    @Volatile private var startedAt: Long = 0L

    fun begin(id: Long, group: String?) {
        pendingId = id
        groupName = group
        groupSelected = false
        searchTyped = false
        sendClicks = 0
        lastStep = ""
        dumpsTaken = 0
        tapAttempts = 0
        lastTapAt = 0L
        history.setLength(0)
        startedAt = SystemClock.elapsedRealtime()
    }

    fun clear() {
        pendingId = -1L
        groupName = null
    }

    fun timedOut(): Boolean {
        val limit = if (groupName != null) 60_000L else 30_000L
        return pendingId != -1L && SystemClock.elapsedRealtime() - startedAt > limit
    }
}

object Notifier {
    private const val CHANNEL = "wa_sched"
    private const val DEBUG_ID = 999001

    /** Live progress notification so you can see exactly which step it reached if a send fails. */
    fun debug(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Scheduled WhatsApp messages", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("WhatsApp Scheduler - step")
            .setContentText(text)
            .setOngoing(true)
            .build()
        nm.notify(DEBUG_ID, n)
    }

    fun clearDebug(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(DEBUG_ID)
    }

    fun post(ctx: Context, m: ScheduledMsg, open: Intent) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Scheduled WhatsApp messages", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            ctx, Scheduler.requestCode(m.id), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Sending WhatsApp message")
            .setContentText("To ${m.label} - tap here if it doesn't send by itself")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(Scheduler.requestCode(m.id), n)
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "wascheduler:wake"
        )
        wl.acquire(30_000L)
    }
}


/** Keeps the latest dump of what WhatsApp's screen looked like when a step couldn't find what it needed. */
object DebugDump {
    private const val PREFS = "wa_scheduler_debug"

    fun save(ctx: Context, slot: String, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(slot, text.take(6000)).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Step history + the FIRST screen where it got stuck + the latest screen. */
    fun report(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hist = p.getString("history", "") ?: ""
        val first = p.getString("first", "") ?: ""
        val last = p.getString("last", "") ?: ""
        if (hist.isEmpty() && first.isEmpty() && last.isEmpty()) return ""
        return "=== STEPS ===\n" + hist + "\n=== FIRST STUCK SCREEN ===\n" + first +
            "\n=== LATEST SCREEN ===\n" + last.take(2500)
    }
}
