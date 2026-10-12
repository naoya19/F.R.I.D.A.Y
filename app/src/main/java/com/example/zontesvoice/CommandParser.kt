package com.example.zontesvoice

enum class Command(val label: String, val doneText: String, val defaultPhrases: String) {
    LOCK(
        "LOCK", "ล็อกรถแล้ว",
        "ล็อก,lock,ปิดรถ,ปิดการทำงานเครื่องยนต์,ปิดเครื่องยนต์"
    ),
    UNLOCK(
        "UNLOCK", "ปลดล็อกรถแล้ว",
        "ปลดล็อก,อันล็อก,unlock,เปิดรถ,เปิดการทำงานเครื่องยนต์,เปิดเครื่องยนต์,สตาร์ท"
    ),
    FUEL("FUEL", "เปิดฝาถังแล้ว", "ฝาถัง,น้ำมัน"),
    SEAT("SEAT", "เปิดเบาะแล้ว", "เบาะ")
}

sealed interface Parsed {
    data class Run(val cmd: Command) : Parsed
    object NeedSecret : Parsed
    object NoPhrase : Parsed // ได้ยินชื่อผู้ช่วย แต่ไม่เจอวลีคำสั่ง
    object NoWake : Parsed   // ไม่ได้ยินชื่อผู้ช่วย
}

object CommandParser {

    /**
     * ตัดวรรณยุกต์ ไม้ไต่คู้ การันต์ จุด ช่องว่าง และรวมสระอำที่ถอดมาเป็น 2 ตัวอักษร
     * เพื่อให้เทียบคำได้ทนต่อการถอดเสียงที่ต่างกัน
     */
    private fun n(s: String): String {
        val removed = "็่้๊๋์ \t\n.,-"
        return s.lowercase()
            .replace("ํา", "ำ")
            .filterNot { it in removed }
            .replace("ลอค", "ลอก")
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** หาช่วงใน t ที่เหมือน/ใกล้เคียง k (ผิดได้ไม่เกิน maxD ตัวอักษร) */
    private fun findNear(t: String, k: String, maxD: Int): IntRange? {
        val idx = t.indexOf(k)
        if (idx >= 0) return idx until idx + k.length
        if (maxD <= 0) return null
        var best: IntRange? = null
        var bestD = maxD + 1
        for (len in (k.length - maxD)..(k.length + maxD)) {
            if (len <= 0 || len > t.length) continue
            for (s in 0..(t.length - len)) {
                val d = lev(t.substring(s, s + len), k)
                if (d < bestD) {
                    bestD = d
                    best = s until s + len
                }
            }
        }
        return best
    }

    /**
     * คำปลุกเทียบแบบยืดหยุ่น (ผิดเพี้ยนได้เล็กน้อย) ส่วนวลีคำสั่งเทียบแบบตรงตัวเท่านั้น
     * เพราะ "เปิดรถ" กับ "ปิดรถ" ต่างกันตัวเดียว ถ้าเทียบหลวมอาจสั่งผิดทิศทาง
     * ถ้าวลีซ้อนกัน เช่น "ล็อก" อยู่ใน "ปลดล็อก" จะเลือกวลีที่ยาวที่สุด
     */
    fun parse(raw: String, wakeCsv: String, secret: String, phrases: Map<Command, String>): Parsed {
        var t = n(raw)

        val wakes = wakeCsv.split(',').map { n(it) }.filter { it.isNotEmpty() }
        if (wakes.isNotEmpty()) {
            var hit: IntRange? = null
            for (w in wakes) {
                hit = findNear(t, w, if (w.length >= 5) w.length / 4 else 0)
                if (hit != null) break
            }
            if (hit == null) return Parsed.NoWake
            t = t.removeRange(hit) // ตัดชื่อผู้ช่วยออก กันไปซ้อนกับวลีคำสั่ง
        }

        var best: Command? = null
        var bestLen = 0
        for ((cmd, csv) in phrases) {
            for (p in csv.split(',')) {
                val k = n(p)
                if (k.isNotEmpty() && t.contains(k) && k.length > bestLen) {
                    best = cmd
                    bestLen = k.length
                }
            }
        }
        val cmd = best ?: return Parsed.NoPhrase

        if (cmd != Command.LOCK && secret.isNotBlank() && !t.contains(n(secret))) {
            return Parsed.NeedSecret
        }
        return Parsed.Run(cmd)
    }
}
