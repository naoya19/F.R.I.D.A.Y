package com.example.zontesvoice

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("zv", Context.MODE_PRIVATE)

    const val DEFAULT_NAME = "F.R.I.D.A.Y"
    const val DEFAULT_WAKE = "friday,ฟรายเดย์,ไฟรเดย์,ฟลายเดย์"

    fun pkg(c: Context): String? = sp(c).getString("pkg", null)
    fun setPkg(c: Context, v: String) = sp(c).edit().putString("pkg", v).apply()

    /** ชื่อที่แสดงกลางไอคอนลอย */
    fun name(c: Context): String = sp(c).getString("name", DEFAULT_NAME) ?: DEFAULT_NAME
    fun setName(c: Context, v: String) = sp(c).edit().putString("name", v).apply()

    /** คำปลุก (ชื่อผู้ช่วย) คั่นด้วยจุลภาค เว้นว่าง = ไม่ต้องมีคำปลุก */
    fun wake(c: Context): String = sp(c).getString("wake3", DEFAULT_WAKE) ?: ""
    fun setWake(c: Context, v: String) = sp(c).edit().putString("wake3", v).apply()

    /** วลีที่ใช้สั่งแต่ละคำสั่ง คั่นด้วยจุลภาค */
    fun phrases(c: Context, cmd: Command): String =
        sp(c).getString("ph3_${cmd.name}", cmd.defaultPhrases) ?: cmd.defaultPhrases

    fun setPhrases(c: Context, cmd: Command, v: String) =
        sp(c).edit().putString("ph3_${cmd.name}", v).apply()

    /** รหัสลับที่ต้องพูดด้วยตอนปลดล็อก/เปิดเบาะ/เปิดฝาถัง (เว้นว่าง = ไม่ใช้) */
    fun secret(c: Context): String = sp(c).getString("secret", "") ?: ""
    fun setSecret(c: Context, v: String) = sp(c).edit().putString("secret", v).apply()

    fun returnHome(c: Context): Boolean = sp(c).getBoolean("home", true)
    fun setReturnHome(c: Context, v: Boolean) = sp(c).edit().putBoolean("home", v).apply()

    /** ปิดเสียงติ๊งของตัวรู้จำเสียงตลอดช่วงฟัง (เปิดคืนตอนพูดตอบ/หยุด/ปิดแอพ) */
    fun muteBeep(c: Context): Boolean = sp(c).getBoolean("mute_beep_v2", true)
    fun setMuteBeep(c: Context, v: Boolean) = sp(c).edit().putBoolean("mute_beep_v2", v).apply()

    fun orbDp(c: Context): Int = sp(c).getInt("orb_dp", 100)
    fun setOrbDp(c: Context, v: Int) = sp(c).edit().putInt("orb_dp", v).apply()

    fun orbX(c: Context): Int = sp(c).getInt("orb_x", -1)
    fun orbY(c: Context): Int = sp(c).getInt("orb_y", -1)
    fun setOrbPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("orb_x", x).putInt("orb_y", y).apply()
}
