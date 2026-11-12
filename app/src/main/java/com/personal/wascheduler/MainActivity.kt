package com.personal.wascheduler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var numberInput: EditText
    private lateinit var contactsBtn: Button
    private lateinit var groupCheck: CheckBox
    private lateinit var repeatSpinner: Spinner

    private val repeatLabels = listOf(
        "Don't repeat", "Every 30 minutes", "Every hour", "Every 2 hours", "Every 6 hours", "Every day", "Every week"
    )
    private val repeatValues = longArrayOf(
        0L, 30 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L, 6 * 60 * 60_000L, 24 * 60 * 60_000L, 7 * 24 * 60 * 60_000L
    )
    private lateinit var messageInput: EditText
    private lateinit var dateBtn: Button
    private lateinit var timeBtn: Button
    private lateinit var statusText: TextView
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private var items: List<ScheduledMsg> = emptyList()
    private var pickedName: String? = null
    private var pickedRaw: String? = null

    private val cal: Calendar = Calendar.getInstance().apply {
        add(Calendar.MINUTE, 5)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val rowFormat = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WhatsApp Scheduler"

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(16), dp(12), dp(16), dp(8))

        // ---- Setup section ----
        val setupTitle = TextView(this)
        setupTitle.text = "One-time setup"
        setupTitle.textSize = 18f
        header.addView(setupTitle)

        statusText = TextView(this)
        statusText.setPadding(0, dp(4), 0, dp(4))
        header.addView(statusText)

        header.addView(makeButton("1. Turn on auto-send (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        header.addView(makeButton("2. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        header.addView(makeButton("3. Stop battery optimisation") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })

        header.addView(makeButton("Copy debug info (after a failed send)") {
            val dump = DebugDump.report(this)
            if (dump.isEmpty()) {
                Toast.makeText(this, "Nothing saved yet - run a send first", Toast.LENGTH_LONG).show()
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("wa scheduler debug", dump))
                Toast.makeText(this, "Copied - paste it into the Claude chat", Toast.LENGTH_LONG).show()
            }
        })

        // ---- New message section ----
        val newTitle = TextView(this)
        newTitle.text = "Schedule a message"
        newTitle.textSize = 18f
        newTitle.setPadding(0, dp(20), 0, dp(4))
        header.addView(newTitle)

        groupCheck = CheckBox(this)
        groupCheck.text = "Send to a WhatsApp group instead"
        header.addView(groupCheck)

        val numberRow = LinearLayout(this)
        numberRow.orientation = LinearLayout.HORIZONTAL
        numberInput = EditText(this)
        numberInput.hint = "Phone number (10 digits = India +91)"
        numberInput.inputType = InputType.TYPE_CLASS_PHONE
        numberRow.addView(numberInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        contactsBtn = makeButton("Contacts") {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 100
            )
        }
        numberRow.addView(contactsBtn)
        header.addView(numberRow)

        groupCheck.setOnCheckedChangeListener { _, checked ->
            numberInput.setText("")
            if (checked) {
                numberInput.hint = "Exact group name (must be unique)"
                numberInput.inputType = InputType.TYPE_CLASS_TEXT
                contactsBtn.visibility = View.GONE
            } else {
                numberInput.hint = "Phone number (10 digits = India +91)"
                numberInput.inputType = InputType.TYPE_CLASS_PHONE
                contactsBtn.visibility = View.VISIBLE
            }
        }

        messageInput = EditText(this)
        messageInput.hint = "Message"
        messageInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        messageInput.minLines = 3
        header.addView(messageInput)

        val whenRow = LinearLayout(this)
        whenRow.orientation = LinearLayout.HORIZONTAL
        dateBtn = makeButton("") { pickDate() }
        timeBtn = makeButton("") { pickTime() }
        whenRow.addView(dateBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        whenRow.addView(timeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(whenRow)

        repeatSpinner = Spinner(this)
        repeatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, repeatLabels)
        header.addView(repeatSpinner)

        header.addView(makeButton("Schedule message") { scheduleMessage() })

        val listTitle = TextView(this)
        listTitle.text = "Your messages (tap one to cancel or delete)"
        listTitle.textSize = 18f
        listTitle.setPadding(0, dp(20), 0, dp(4))
        header.addView(listTitle)

        // ---- List ----
        list = ListView(this)
        list.addHeaderView(header, null, false)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val idx = position - list.headerViewsCount
            if (idx >= 0 && idx < items.size) showItemActions(items[idx])
        }
        setContentView(list)

        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateStatus()
    }

    // ---- Contact picker result ----
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val num = c.getString(0) ?: ""
                pickedRaw = num
                pickedName = c.getString(1)
                numberInput.setText(num)
            }
        }
    }

    // ---- Date / time ----
    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, y, m, d ->
                cal.set(y, m, d)
                updateButtons()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime() {
        TimePickerDialog(
            this,
            { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                updateButtons()
            },
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun updateButtons() {
        dateBtn.text = dateFormat.format(cal.time)
        timeBtn.text = DateFormat.getTimeFormat(this).format(cal.time)
    }

    // ---- Scheduling ----
    private fun normalize(raw: String): String? {
        val hasPlus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (!hasPlus) {
            if (d.startsWith("00")) d = d.substring(2)
            else if (d.length == 11 && d.startsWith("0")) d = "91" + d.substring(1)
            else if (d.length == 10) d = "91$d"
        }
        return if (d.length in 8..15) d else null
    }

    private fun scheduleMessage() {
        val raw = numberInput.text.toString()
        val isGroup = groupCheck.isChecked
        val number: String?
        if (isGroup) {
            if (raw.trim().isEmpty()) {
                Toast.makeText(this, "Enter the exact group name", Toast.LENGTH_LONG).show()
                return
            }
            number = ""
        } else {
            number = normalize(raw)
            if (number == null) {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_LONG).show()
                return
            }
        }
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type a message first", Toast.LENGTH_LONG).show()
            return
        }
        var t = cal.timeInMillis
        if (t < System.currentTimeMillis() + 10_000L) {
            Toast.makeText(this, "Pick a time in the future", Toast.LENGTH_LONG).show()
            return
        }
        // Keep messages at least 45 s apart so two sends never overlap.
        val existing = Store.load(this).filter { it.status == "PENDING" }
        while (existing.any { Math.abs(it.timeMillis - t) < 45_000L }) t += 45_000L

        val label = if (pickedName != null && raw == pickedRaw) pickedName!! else raw.trim()
        val repeat = repeatValues[repeatSpinner.selectedItemPosition]
        val m = ScheduledMsg(System.currentTimeMillis(), number, label, text, t, "PENDING", isGroup, repeat)
        Store.add(this, m)
        Scheduler.schedule(this, m)
        messageInput.setText("")
        Toast.makeText(this, "Scheduled for ${rowFormat.format(Date(t))}", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun showItemActions(m: ScheduledMsg) {
        if (m.status != "PENDING") {
            confirmRemove(m)
            return
        }
        val options = arrayOf("Test now (send immediately)", if (m.repeatMs > 0) "Stop repeating" else "Cancel message")
        AlertDialog.Builder(this)
            .setTitle("To ${m.label}")
            .setItems(options) { _, which ->
                if (which == 0) testNow(m) else confirmRemove(m)
            }
            .show()
    }

    /** Fires the send flow right now, without touching the message's real scheduled time. */
    private fun testNow(m: ScheduledMsg) {
        val copyId = System.currentTimeMillis()
        val copy = m.copy(id = if (copyId == m.id) copyId + 1 else copyId, status = "SENDING", repeatMs = 0L)
        Store.add(this, copy)
        DebugDump.clear(this)
        SendCoordinator.begin(copy.id, if (copy.isGroup) copy.label else null)
        Notifier.wakeScreen(this)
        val open = Wa.chatIntent(this, copy)
        Notifier.post(this, copy, open)
        try {
            startActivity(open)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open WhatsApp", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun confirmRemove(m: ScheduledMsg) {
        val pending = m.status == "PENDING"
        val repeating = pending && m.repeatMs > 0
        AlertDialog.Builder(this)
            .setTitle(
                if (repeating) "Stop this repeating message?"
                else if (pending) "Cancel this message?"
                else "Delete from history?"
            )
            .setMessage("To ${m.label}\n\n${m.text}")
            .setPositiveButton(if (repeating) "Stop repeating" else if (pending) "Cancel message" else "Delete") { _, _ ->
                if (pending) Scheduler.cancel(this, m.id)
                Store.remove(this, m.id)
                refresh()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ---- List / status display ----
    private fun refresh() {
        val now = System.currentTimeMillis()
        for (m in Store.load(this)) {
            if (m.status == "SENDING" && now - m.timeMillis > 120_000L) Store.setStatus(this, m.id, "FAILED")
        }
        val all = Store.load(this)
        val upcoming = all.filter { it.status == "PENDING" }.sortedBy { it.timeMillis }
        val history = all.filter { it.status != "PENDING" }.sortedByDescending { it.timeMillis }
        items = upcoming + history

        adapter.clear()
        for (m in items) {
            val statusLabel = if (m.status.startsWith("FAILED -")) m.status.replaceFirst("FAILED -", "Failed -") else when (m.status) {
                "PENDING" -> if (m.repeatMs > 0) {
                    val idx = repeatValues.indexOf(m.repeatMs)
                    "Repeating: " + (if (idx >= 0) repeatLabels[idx].lowercase() else "custom interval") + " (next send shown)"
                } else "Scheduled"
                "SENDING" -> "Sending..."
                "SENT" -> "Sent (Send was tapped)"
                "FAILED" -> "Failed - open WhatsApp and check"
                "MISSED" -> "Missed (phone was off)"
                else -> m.status
            }
            val preview = if (m.text.length > 70) m.text.substring(0, 70) + "..." else m.text
            val who = if (m.isGroup) "${m.label} (group)" else m.label
            adapter.add("${rowFormat.format(Date(m.timeMillis))}  ->  $who\n$preview\n[$statusLabel]")
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateStatus() {
        val acc = accessibilityOn()
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        fun mark(ok: Boolean) = if (ok) "OK" else "NOT DONE"
        statusText.text = "Auto-send: ${mark(acc)}\nDisplay over apps: ${mark(overlay)}\nBattery: ${mark(battery)}"
        statusText.visibility = View.VISIBLE
    }

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains(packageName) && s.contains("SendAccessibilityService")
    }
}
