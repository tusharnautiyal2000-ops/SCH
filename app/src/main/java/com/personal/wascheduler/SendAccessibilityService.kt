package com.personal.wascheduler

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SendAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastDumpAt = 0L
    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val id = SendCoordinator.pendingId
        if (id == -1L || busy) return

        if (SendCoordinator.timedOut()) {
            Notifier.debug(this, "Timed out - last seen: " + SendCoordinator.lastStep)
            Store.setStatus(this, id, "FAILED - Timed out - last seen: " + SendCoordinator.lastStep)
            SendCoordinator.clear()
            return
        }

        if (waRoots().isEmpty()) return

        busy = true
        // Short delay so the screen has finished loading before we act on it.
        handler.postDelayed({
            try {
                if (SendCoordinator.pendingId == id) {
                    if (SendCoordinator.groupName != null) groupStep(id) else directStep(id)
                }
            } finally {
                busy = false
            }
        }, 800)
    }

    private fun step(text: String) {
        SendCoordinator.lastStep = text
        Notifier.debug(this, text)
    }

    /** Every WhatsApp window currently on screen (main screen plus dialogs / bottom sheets). */
    private fun waRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: continue
                if (p in waPackages) out.add(r)
            }
        } catch (e: Exception) {
            // fall through to the active window below
        }
        if (out.isEmpty()) {
            val r = rootInActiveWindow
            val p = r?.packageName?.toString()
            if (r != null && p != null && p in waPackages) out.add(r)
        }
        return out
    }

    // ---------- 1-to-1 chat: text is pre-filled, just tap Send ----------
    private fun directStep(id: Long) {
        step("Chat open - looking for Send button")
        val btn = findSendAnywhere() ?: run { dumpScreen("direct: no Send found"); return }
        step("Found Send button - tapping")
        if (clickNode(btn)) finish(id)
    }

    // ---------- Group: pick the group in WhatsApp's "Send to..." list, then tap Send ----------
    private fun groupStep(id: Long) {
        val roots = waRoots()
        if (roots.isEmpty()) return
        val name = SendCoordinator.groupName ?: return

        if (SendCoordinator.groupSelected) {
            if (SendCoordinator.sendClicks >= 4) {
                step("Gave up after several Send taps")
                Store.setStatus(this, id, "FAILED")
                SendCoordinator.clear()
                return
            }
            step("Group opened - looking for Send button")
            val send = findSendAnywhere()
            if (send == null) {
                dumpScreen("group: group opened but no Send found")
                return
            }
            step("Found Send button - tapping (attempt ${SendCoordinator.sendClicks + 1})")
            if (clickNode(send)) {
                SendCoordinator.sendClicks = SendCoordinator.sendClicks + 1
                handler.postDelayed({ checkDone(id) }, 2500)
            }
            return
        }

        // Phase 1a: the group is visible in the list already -> tap it.
        for (root in roots) {
            val target = findMatchingChat(root, name)
            if (target != null) {
                step("Found '$name' on screen - tapping it")
                if (clickNode(target)) SendCoordinator.groupSelected = true
                return
            }
        }

        // Phase 1b: not visible -> use the search box.
        val root = roots[0]
        val edit = findEditText(root)
        if (edit != null) {
            if (!SendCoordinator.searchTyped) {
                step("Typing '$name' into search")
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    SendCoordinator.searchTyped = true
                }
            } else {
                step("Searched for '$name' - waiting for it to appear")
                dumpScreen("group: searched but group row not found")
            }
        } else {
            val search = findSearchButton(root)
            if (search != null) {
                step("Opening search")
                clickNode(search)
            } else {
                step("Looking for the group '$name' or a search box")
                dumpScreen("group: no list match and no search box")
            }
        }
    }

    /** After a Send tap: if no Send control is left on screen, the message went out. */
    private fun checkDone(id: Long) {
        if (SendCoordinator.pendingId != id) return
        if (findSendAnywhere() == null) finish(id)
    }

    private fun finish(id: Long) {
        step("Sent")
        Notifier.clearDebug(this)
        Store.setStatus(this, id, "SENT")
        SendCoordinator.clear()
        // Go back to the home screen so you're not left sitting in WhatsApp.
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 1500)
    }

    // ---------- finding the Send control ----------
    private fun findSendAnywhere(): AccessibilityNodeInfo? {
        for (root in waRoots()) {
            val pkg = root.packageName?.toString() ?: continue
            val n = findSend(root, pkg)
            if (n != null) return n
        }
        return null
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        // 1) WhatsApp's own send button id (the mic button reuses it when the box is empty).
        for (n in root.findAccessibilityNodeInfosByViewId("$pkg:id/send")) {
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            if (n.isEnabled && !d.contains("voice") && !d.contains("record")) return n
        }
        // 2) Any node whose text or description is exactly "Send" (button, text label, icon - any class).
        val hits = ArrayList<AccessibilityNodeInfo>()
        collect(root, hits) { n ->
            val t = n.text?.toString()?.trim()?.lowercase()
            val d = n.contentDescription?.toString()?.trim()?.lowercase()
            n.isEnabled && (t == "send" || d == "send")
        }
        if (hits.isNotEmpty()) return hits[0]
        // 3) Any node whose view id mentions "send" (but not voice / mic / record).
        collect(root, hits) { n ->
            val vid = n.viewIdResourceName?.lowercase() ?: ""
            n.isEnabled && vid.contains("send") && !vid.contains("voice") && !vid.contains("mic") &&
                !vid.contains("record") && !vid.contains("status")
        }
        return hits.firstOrNull()
    }

    // ---------- finding the group row ----------
    /** Case-insensitive, whitespace-tolerant match on either the row's own text or its description. */
    private fun findMatchingChat(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        val wanted = name.trim().lowercase()
        val hits = ArrayList<AccessibilityNodeInfo>()
        collect(root, hits) { n ->
            val cls = n.className?.toString() ?: ""
            if (cls.contains("EditText")) return@collect false
            val t = n.text?.toString()?.trim()?.lowercase()
            val d = n.contentDescription?.toString()?.trim()?.lowercase()
            (t != null && t == wanted) || (d != null && d.contains(wanted))
        }
        return hits.firstOrNull()
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        pred: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (node == null) return
        if (pred(node)) out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), out, pred)
    }

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((node.className?.toString() ?: "").contains("EditText")) return node
        for (i in 0 until node.childCount) {
            val r = findEditText(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun findSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText("Search")) {
            val cls = n.className?.toString() ?: ""
            if (!cls.contains("EditText")) return n
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }

    // ---------- screen dump for debugging ----------
    private fun dumpScreen(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 3000) return
        lastDumpAt = now
        val sb = StringBuilder()
        sb.append("REASON: ").append(reason).append('\n')
        sb.append("GROUP: ").append(SendCoordinator.groupName).append('\n')
        val roots = waRoots()
        sb.append("WINDOWS: ").append(roots.size).append('\n')
        for ((idx, r) in roots.withIndex()) {
            sb.append("--- window ").append(idx).append(" ---\n")
            dumpNode(r, 0, sb)
        }
        DebugDump.save(this, sb.toString())
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (n == null || sb.length > 8500 || depth > 14) return
        val cls = (n.className?.toString() ?: "").substringAfterLast('.')
        val vid = (n.viewIdResourceName ?: "").substringAfterLast('/')
        val t = n.text?.toString()?.replace('\n', ' ')?.take(30)
        val d = n.contentDescription?.toString()?.replace('\n', ' ')?.take(30)
        val flags = (if (n.isClickable) "C" else "") + (if (n.isEnabled) "" else "x")
        // skip pure layout containers with nothing useful to report
        val useful = vid.isNotEmpty() || !t.isNullOrEmpty() || !d.isNullOrEmpty() || n.isClickable
        if (useful) {
            sb.append("  ".repeat(depth)).append(cls)
            if (vid.isNotEmpty()) sb.append(" #").append(vid)
            if (!t.isNullOrEmpty()) sb.append(" t=\"").append(t).append('"')
            if (!d.isNullOrEmpty()) sb.append(" d=\"").append(d).append('"')
            if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
            sb.append('\n')
        }
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), depth + 1, sb)
    }

    override fun onInterrupt() {}
}
