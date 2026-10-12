package com.example.zontesvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

enum class OrbState { LISTENING, BUSY, PAUSED }

/**
 * ไอคอน HUD วงแหวนหมุน แสดงชื่อผู้ช่วยตรงกลาง
 * LISTENING = หมุนปกติ สีฟ้า / BUSY = หมุนเร็ว สีส้ม / PAUSED = หยุดนิ่ง สีหม่น
 */
class OrbView(context: Context) : View(context) {

    var name: String = Prefs.DEFAULT_NAME
        set(v) {
            field = v
            invalidate()
        }

    var state: OrbState = OrbState.LISTENING
        set(v) {
            field = v
            lastT = SystemClock.uptimeMillis()
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private var angle = 0f
    private var pulseT = 0f
    private var lastT = SystemClock.uptimeMillis()
    private var peak = 0f

    /** ระดับเสียงจากไมค์ 0..1 ทำให้วงในเรืองขึ้นตามเสียงพูด */
    fun pushLevel(v: Float) {
        if (v > peak) peak = v
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()
    private val tri = Path()

    private val dotDash = DashPathEffect(floatArrayOf(1.2f * d, 3.4f * d), 0f)
    private val boxDash = DashPathEffect(floatArrayOf(3.2f * d, 2.4f * d), 0f)

    private fun setRect(cx: Float, cy: Float, r: Float) = rect.set(cx - r, cy - r, cx + r, cy + r)

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastT).coerceIn(0L, 100L) / 1000f
        lastT = now

        val speed = when (state) {
            OrbState.LISTENING -> 1f
            OrbState.BUSY -> 3.2f
            OrbState.PAUSED -> 0f
        }
        angle = (angle + 24f * speed * dt) % 360f
        pulseT += dt * 2.2f * speed.coerceAtMost(1.6f)
        val lv = peak
        peak = (peak - dt * 2f).coerceAtLeast(0f)

        val base: Int
        val deep: Int
        when (state) {
            OrbState.LISTENING -> { base = 0xFF00E5FF.toInt(); deep = 0xFF1E88E5.toInt() }
            OrbState.BUSY -> { base = 0xFFFFB74D.toInt(); deep = 0xFFFF8F00.toInt() }
            OrbState.PAUSED -> { base = 0xFF5E7C8B.toInt(); deep = 0xFF35505E.toInt() }
        }

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2f * d

        // พื้นหลังวงกลมโปร่งแสง
        fill.color = 0xD9031422.toInt()
        c.drawCircle(cx, cy, r, fill)

        // วงนอกบาง
        ring.pathEffect = null
        ring.color = deep
        ring.strokeWidth = 1.2f * d
        c.drawCircle(cx, cy, r * 0.97f, ring)

        // ส่วนโค้งหนา หมุนตามเข็ม
        c.save()
        c.rotate(angle, cx, cy)
        ring.color = base
        ring.strokeWidth = 3f * d
        setRect(cx, cy, r * 0.88f)
        c.drawArc(rect, 0f, 75f, false, ring)
        c.drawArc(rect, 105f, 28f, false, ring)
        c.drawArc(rect, 190f, 110f, false, ring)
        c.restore()

        // วงจุด หมุนทวนเข็ม
        c.save()
        c.rotate(-angle * 0.7f, cx, cy)
        ring.color = deep
        ring.strokeWidth = 1.8f * d
        ring.pathEffect = dotDash
        c.drawCircle(cx, cy, r * 0.76f, ring)
        c.restore()

        // วงกล่องเรียงเป็นช่อง หมุนเร็วกว่า
        c.save()
        c.rotate(angle * 1.6f, cx, cy)
        ring.color = base
        ring.strokeWidth = 4f * d
        ring.pathEffect = boxDash
        setRect(cx, cy, r * 0.63f)
        c.drawArc(rect, 20f, 220f, false, ring)
        c.restore()
        ring.pathEffect = null

        // วงในเรืองแสง เต้นเบา ๆ ตอนทำงาน
        val ri = r * 0.5f * (1f + 0.035f * sin(pulseT) + 0.1f * lv)
        ring.color = base
        ring.alpha = (40 + 140 * lv).toInt()
        ring.strokeWidth = (7f + 5f * lv) * d
        c.drawCircle(cx, cy, ri, ring)
        ring.alpha = 90
        ring.strokeWidth = 4.5f * d
        c.drawCircle(cx, cy, ri, ring)
        ring.color = base
        ring.alpha = 255
        ring.strokeWidth = 1.8f * d
        c.drawCircle(cx, cy, ri, ring)

        // ชื่อกลางวง ย่อขนาดให้พอดี
        textP.color = if (state == OrbState.PAUSED) 0xFFB0BEC5.toInt() else 0xFFE0F7FA.toInt()
        textP.textSize = ri * 0.5f
        val maxW = ri * 1.7f
        while (textP.measureText(name) > maxW && textP.textSize > 4f * d) {
            textP.textSize *= 0.93f
        }
        val ty = cy - (textP.ascent() + textP.descent()) / 2f
        c.drawText(name, cx, ty - (if (state == OrbState.PAUSED) ri * 0.15f else 0f), textP)

        // สามเหลี่ยม play ตอนหยุด บอกว่าแตะเพื่อเล่นต่อ
        if (state == OrbState.PAUSED) {
            val s = ri * 0.2f
            val py = cy + ri * 0.5f
            tri.reset()
            tri.moveTo(cx - s * 0.7f, py - s)
            tri.lineTo(cx - s * 0.7f, py + s)
            tri.lineTo(cx + s, py)
            tri.close()
            fill.color = 0xFFB0BEC5.toInt()
            c.drawPath(tri, fill)
        } else {
            postInvalidateDelayed(33)
        }
    }
}
