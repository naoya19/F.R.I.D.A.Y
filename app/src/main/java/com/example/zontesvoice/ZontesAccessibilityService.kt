package com.example.zontesvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * เปิดแอพ Zontes Smart แล้วกดปุ่มตามชื่อบนหน้าจอ (LOCK / UNLOCK / FUEL / SEAT)
 */
class ZontesAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ZontesAccessibilityService? = null

        private const val TOTAL_TIMEOUT_MS = 25_000L
        private const val POLL_MS = 500L
        private const val SETTLE_MS = 2_500L     // รอให้บลูทูธต่อรถหลังเจอปุ่ม
        private const val AFTER_TAP_MS = 2_000L  // รอให้คำสั่งส่งออกก่อนกลับหน้าหลัก
        private const val VEHICLE_TAB_AFTER_MS = 5_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** เรียกจาก main thread เท่านั้น */
    fun execute(cmd: Command, onDone: (Boolean) -> Unit) {
        if (busy) {
            onDone(false)
            return
        }
        val pkg = Prefs.pkg(this)
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (pkg == null || launch == null) {
            onDone(false)
            return
        }
        busy = true
        wakeScreen()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            finish(false, onDone)
            return
        }
        val startedAt = SystemClock.uptimeMillis()
        poll(pkg, cmd, startedAt, 0L, onDone)
    }

    private fun poll(pkg: String, cmd: Command, startedAt: Long, firstSeen: Long, onDone: (Boolean) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > TOTAL_TIMEOUT_MS) {
            finish(false, onDone)
            return
        }

        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() == pkg) {
            val target = findByLabel(root, cmd.label)
            if (target != null) {
                val seen = if (firstSeen == 0L) now else firstSeen
                if (now - seen >= SETTLE_MS) {
                    val ok = tap(target)
                    main.postDelayed({
                        if (Prefs.returnHome(this)) performGlobalAction(GLOBAL_ACTION_HOME)
                        finish(ok, onDone)
                    }, AFTER_TAP_MS)
                    return
                }
                main.postDelayed({ poll(pkg, cmd, startedAt, seen, onDone) }, POLL_MS)
                return
            }
            // ไม่เจอปุ่ม อาจอยู่ผิดแท็บ ให้ลองกด VEHICLE
            if (now - startedAt > VEHICLE_TAB_AFTER_MS) {
                findByLabel(root, "VEHICLE")?.let { tap(it) }
            }
        }
        main.postDelayed({ poll(pkg, cmd, startedAt, 0L, onDone) }, POLL_MS)
    }

    private fun finish(ok: Boolean, onDone: (Boolean) -> Unit) {
        busy = false
        onDone(ok)
    }

    private fun findByLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (text.equals(label, ignoreCase = true) || desc.equals(label, ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        // 1) หา node ที่กดได้ (ตัวมันเองหรือแม่) แล้วสั่งคลิก
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        // 2) สำรอง: แตะที่พิกัดกึ่งกลางของข้อความ
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "zontesvoice:wake"
        )
        wl.acquire(20_000L)
    }
}
