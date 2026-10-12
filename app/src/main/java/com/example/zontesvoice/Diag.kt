package com.example.zontesvoice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** เก็บสถานะ/ผลการฟังล่าสุด ไว้แสดงในหน้าแอพ เพื่อดูว่าแอพได้ยินอะไรและทำไมไม่ทำงาน */
object Diag {
    @Volatile
    var listener: (() -> Unit)? = null

    @Volatile
    var event: String = "ยังไม่ได้เริ่มฟัง"
        private set

    private val history = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun event(msg: String) {
        event = msg
        listener?.invoke()
    }

    fun heard(msg: String) {
        synchronized(history) {
            history.add(0, "${fmt.format(Date())}  $msg")
            while (history.size > 5) history.removeAt(history.size - 1)
        }
        listener?.invoke()
    }

    fun history(): String = synchronized(history) { history.joinToString("\n") }
}
