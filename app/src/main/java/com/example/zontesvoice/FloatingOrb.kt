package com.example.zontesvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** วงกลมปุ่ม X ที่โผล่ตอนลากไอคอน ลากไอคอนมาทับแล้วปล่อย = ปิดแอพ */
class CloseTargetView(context: Context) : View(context) {
    var active: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * (if (active) 1f else 0.8f)
        bg.color = if (active) 0xDDE53935.toInt() else 0x99000000.toInt()
        c.drawCircle(cx, cy, r, bg)
        line.strokeWidth = 1.5f * d
        line.alpha = 140
        c.drawCircle(cx, cy, r, line)
        line.alpha = 255
        line.strokeWidth = 3f * d
        val a = r * 0.33f
        c.drawLine(cx - a, cy - a, cx + a, cy + a, line)
        c.drawLine(cx - a, cy + a, cx + a, cy - a, line)
    }
}

/**
 * ไอคอนลอยทับทุกแอพ ลากย้ายได้ แตะเพื่อสั่ง onTap (หยุด/เล่นต่อ)
 * ลากลงไปที่ปุ่ม X ด้านล่างจอแล้วปล่อยเพื่อปิด (onClose)
 */
class FloatingOrb(
    private val ctx: Context,
    private val onTap: () -> Unit,
    private val onClose: () -> Unit
) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = ctx.resources.displayMetrics
    private val lp = WindowManager.LayoutParams()
    private val tlp = WindowManager.LayoutParams()
    private var view: OrbView? = null
    private var target: CloseTargetView? = null
    private var sizePx = 0
    private val targetPx = (72 * dm.density).toInt()
    private val targetMarginPx = (56 * dm.density).toInt()

    private fun screenW(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.width() else dm.widthPixels

    private fun screenH(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height() else dm.heightPixels

    fun show(name: String, sizeDp: Int): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(ctx)) return false

        sizePx = (sizeDp * dm.density).toInt()
        lp.width = sizePx
        lp.height = sizePx
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        lp.format = PixelFormat.TRANSLUCENT
        lp.gravity = Gravity.TOP or Gravity.START
        val sx = Prefs.orbX(ctx)
        val sy = Prefs.orbY(ctx)
        lp.x = if (sx < 0) (8 * dm.density).toInt() else sx
        lp.y = if (sy < 0) (160 * dm.density).toInt() else sy
        clamp()

        val v = OrbView(ctx)
        v.name = name
        v.setOnTouchListener(touch)
        return try {
            wm.addView(v, lp)
            view = v
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setState(s: OrbState) {
        val v = view ?: return
        v.state = s
        // ระหว่างกำลังสั่งรถ ให้ทะลุการสัมผัส กันบังปุ่มที่ต้องกดในแอพ Zontes
        setTouchable(s != OrbState.BUSY)
    }

    fun setName(n: String) {
        view?.name = n
    }

    fun setLevel(v: Float) {
        view?.pushLevel(v)
    }

    fun hide() {
        hideTarget()
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        view = null
    }

    // ---------- close target ----------

    private fun targetCenterX() = screenW() / 2f
    private fun targetCenterY() = screenH() - targetMarginPx - targetPx / 2f

    private fun showTarget() {
        if (target != null) return
        tlp.width = targetPx
        tlp.height = targetPx
        tlp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        tlp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        tlp.format = PixelFormat.TRANSLUCENT
        tlp.gravity = Gravity.TOP or Gravity.START
        tlp.x = (targetCenterX() - targetPx / 2f).toInt()
        tlp.y = (targetCenterY() - targetPx / 2f).toInt()
        val t = CloseTargetView(ctx)
        try {
            wm.addView(t, tlp)
            target = t
        } catch (e: Exception) {
        }
    }

    private fun hideTarget() {
        val t = target ?: return
        try {
            wm.removeView(t)
        } catch (e: Exception) {
        }
        target = null
    }

    private fun isNearTarget(): Boolean {
        val ox = lp.x + sizePx / 2f
        val oy = lp.y + sizePx / 2f
        return hypot(ox - targetCenterX(), oy - targetCenterY()) < targetPx * 0.9f + sizePx * 0.3f
    }

    // ---------- window helpers ----------

    private fun setTouchable(touchable: Boolean) {
        val v = view ?: return
        val flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags != lp.flags) {
            lp.flags = flags
            try {
                wm.updateViewLayout(v, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun clamp() {
        lp.x = lp.x.coerceIn(0, (screenW() - sizePx).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH() - sizePx).coerceAtLeast(0))
    }

    private val touch = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var near = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    near = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        showTarget()
                    }
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        clamp()
                        near = isNearTarget()
                        if (near) {
                            // ดูดไอคอนเข้ากลางปุ่ม X
                            lp.x = (targetCenterX() - sizePx / 2f).toInt()
                            lp.y = (targetCenterY() - sizePx / 2f).toInt()
                        }
                        target?.active = near
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (ex: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    hideTarget()
                    if (!moved) {
                        v.performClick()
                        onTap()
                    } else if (near) {
                        onClose()
                    } else {
                        Prefs.setOrbPos(ctx, lp.x, lp.y)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideTarget()
                }
            }
            return true
        }
    }
}
