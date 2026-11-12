package com.personal.wascheduler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** status: PENDING, SENDING, SENT, FAILED, MISSED */
data class ScheduledMsg(
    val id: Long,
    val number: String,   // digits only, with country code
    val label: String,    // contact name or the number as typed
    val text: String,
    val timeMillis: Long,
    val status: String,
    val isGroup: Boolean = false,  // true: 'label' is a WhatsApp group name and 'number' is empty
    val repeatMs: Long = 0L        // > 0: this is a repeating series; each fire sends a one-off copy
)

object Store {
    private const val PREFS = "wa_scheduler"
    private const val KEY = "messages"

    @Synchronized
    fun load(ctx: Context): List<ScheduledMsg> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = ArrayList<ScheduledMsg>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                ScheduledMsg(
                    id = o.getLong("id"),
                    number = o.getString("number"),
                    label = o.getString("label"),
                    text = o.getString("text"),
                    timeMillis = o.getLong("time"),
                    status = o.getString("status"),
                    isGroup = o.optBoolean("group", false),
                    repeatMs = o.optLong("repeat", 0L)
                )
            )
        }
        return list
    }

    @Synchronized
    private fun save(ctx: Context, list: List<ScheduledMsg>) {
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            o.put("id", m.id)
            o.put("number", m.number)
            o.put("label", m.label)
            o.put("text", m.text)
            o.put("time", m.timeMillis)
            o.put("status", m.status)
            o.put("group", m.isGroup)
            o.put("repeat", m.repeatMs)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun get(ctx: Context, id: Long): ScheduledMsg? = load(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun add(ctx: Context, m: ScheduledMsg) {
        save(ctx, load(ctx) + m)
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        save(ctx, load(ctx).filter { it.id != id })
    }

    @Synchronized
    fun replace(ctx: Context, m: ScheduledMsg) {
        save(ctx, load(ctx).map { if (it.id == m.id) m else it })
    }

    /** Keep only the newest [keep] finished one-off messages so hourly repeats don't grow the history forever. */
    @Synchronized
    fun prune(ctx: Context, keep: Int = 60) {
        val all = load(ctx)
        val old = all.filter { it.repeatMs == 0L && it.status != "PENDING" && it.status != "SENDING" }
            .sortedByDescending { it.timeMillis }
            .drop(keep)
            .map { it.id }
            .toSet()
        if (old.isNotEmpty()) save(ctx, all.filter { it.id !in old })
    }

    @Synchronized
    fun setStatus(ctx: Context, id: Long, status: String) {
        save(ctx, load(ctx).map { if (it.id == id) it.copy(status = status) else it })
    }
}
