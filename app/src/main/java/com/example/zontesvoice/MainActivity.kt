package com.example.zontesvoice

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var nameEdit: EditText
    private lateinit var wakeEdit: EditText
    private lateinit var secretEdit: EditText
    private lateinit var homeSwitch: SwitchCompat
    private lateinit var beepSwitch: SwitchCompat
    private lateinit var sizeLabel: TextView
    private lateinit var sizeBar: SeekBar
    private val phraseEdits = LinkedHashMap<Command, EditText>()

    private companion object {
        const val SIZE_MIN = 64
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun title(label: String) = TextView(this).apply {
        text = label
        textSize = 16f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun hint(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        setPadding(0, dp(10), 0, 0)
    }

    private fun thaiName(c: Command) = when (c) {
        Command.LOCK -> "ล็อกรถ (LOCK)"
        Command.UNLOCK -> "ปลดล็อก / สตาร์ท (UNLOCK)"
        Command.FUEL -> "เปิดฝาถัง (FUEL)"
        Command.SEAT -> "เปิดเบาะ (SEAT)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        col.addView(title("ผลการฟังล่าสุด (ดูว่าแอพได้ยินอะไร)"))
        diag = TextView(this).apply { textSize = 13f }
        col.addView(diag)
        col.addView(hint("ถ้า \"เสียงสูงสุด\" ค้างราว -2 dB ทั้งที่พูดอยู่ แปลว่าไมค์ไม่ได้ยินเสียงเลย (เช่นมีแอพอัดหน้าจอ/แอพอื่นใช้ไมค์อยู่)"))

        // --- 1) สิทธิ์ ---
        col.addView(title("1) ตั้งค่าสิทธิ์"))
        col.addView(btn("อนุญาตไมค์ + การแจ้งเตือน") { requestRuntimePerms() })
        col.addView(btn("เปิด Accessibility ให้แอพนี้") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("อนุญาตแสดงทับแอพอื่น (จำเป็นสำหรับไอคอนลอย)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(btn("เลือกแอพ Zontes Smart") { pickZontesApp(forcePicker = true) })

        // --- 2) ชื่อและคำสั่ง ---
        col.addView(title("2) ชื่อผู้ช่วยและคำสั่งเสียง"))
        col.addView(hint("ชื่อที่แสดงกลางไอคอนลอย"))
        nameEdit = EditText(this).apply { setText(Prefs.name(this@MainActivity)) }
        col.addView(nameEdit)

        col.addView(hint("คำปลุก = ชื่อที่ใช้เรียก (คั่นด้วย , ใส่หลายแบบได้ เผื่อถอดเสียงไม่ตรง เว้นว่าง = ไม่ต้องเรียกชื่อ)"))
        wakeEdit = EditText(this).apply { setText(Prefs.wake(this@MainActivity)) }
        col.addView(wakeEdit)

        for (c in Command.values()) {
            col.addView(hint("วลีสั่ง: ${thaiName(c)} (คั่นด้วย ,)"))
            val e = EditText(this).apply { setText(Prefs.phrases(this@MainActivity, c)) }
            phraseEdits[c] = e
            col.addView(e)
        }

        col.addView(hint("รหัสลับ (ต้องพูดตอนปลดล็อก/เปิดเบาะ/เปิดฝาถัง เว้นว่าง = ไม่ใช้)"))
        secretEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(Prefs.secret(this@MainActivity))
        }
        col.addView(secretEdit)

        col.addView(btn("รีเซ็ตชื่อและวลีเป็นค่าเริ่มต้น") {
            nameEdit.setText(Prefs.DEFAULT_NAME)
            wakeEdit.setText(Prefs.DEFAULT_WAKE)
            for ((c, e) in phraseEdits) e.setText(c.defaultPhrases)
        })

        // --- 3) ตัวเลือก ---
        col.addView(title("3) ตัวเลือก"))
        homeSwitch = SwitchCompat(this).apply {
            text = "กลับหน้าหลักของมือถือหลังสั่งเสร็จ"
            isChecked = Prefs.returnHome(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(homeSwitch)

        beepSwitch = SwitchCompat(this).apply {
            text = "ปิดเสียงติ๊งระหว่างฟัง (เพลงในเครื่องจะเงียบขณะฟังอยู่ มีเสียงปิ๊ปครั้งเดียวตอนเริ่ม)"
            isChecked = Prefs.muteBeep(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(beepSwitch)

        sizeLabel = TextView(this)
        sizeBar = SeekBar(this).apply {
            max = 96
            progress = (Prefs.orbDp(this@MainActivity) - SIZE_MIN).coerceIn(0, 96)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    sizeLabel.text = "ขนาดไอคอนลอย: ${p + SIZE_MIN} dp (มีผลตอนกดเริ่มฟังใหม่)"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sizeLabel.text = "ขนาดไอคอนลอย: ${sizeBar.progress + SIZE_MIN} dp (มีผลตอนกดเริ่มฟังใหม่)"
        col.addView(sizeLabel)
        col.addView(sizeBar)

        // --- 4) ทดสอบ ---
        col.addView(title("4) ทดสอบสั่งด้วยปุ่ม (ยังไม่ใช้เสียง)"))
        for (c in Command.values()) {
            col.addView(btn("ทดสอบ ${c.label}") { testCommand(c) })
        }

        // --- 5) เริ่มฟัง ---
        col.addView(title("5) ฟังเสียงเบื้องหลัง"))
        col.addView(btn("▶ เริ่มฟังเสียง (แสดงไอคอนลอย)") { startVoice() })
        col.addView(btn("■ ปิดทั้งหมด") {
            stopService(Intent(this, VoiceListenerService::class.java))
            Toast.makeText(this, "ปิดแล้ว", Toast.LENGTH_SHORT).show()
        })

        col.addView(TextView(this).apply {
            text = "ตัวอย่าง: พูดว่า \"friday เปิดการทำงานเครื่องยนต์\" = UNLOCK\n" +
                "แตะไอคอนลอยเพื่อหยุดฟัง (ภาพหยุดนิ่ง) แตะอีกครั้งเพื่อเล่นต่อ ลากเพื่อย้ายตำแหน่ง"
            setPadding(0, dp(16), 0, 0)
        })

        pickZontesApp(forcePicker = false)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshDiag()
        Diag.listener = { runOnUiThread { refreshDiag() } }
    }

    override fun onPause() {
        Diag.listener = null
        savePrefs()
        super.onPause()
    }

    private fun refreshDiag() {
        val h = Diag.history().ifEmpty { "(ยังไม่มีผลถอดเสียง)" }
        diag.text = "สถานะ: ${Diag.event}\n\n$h"
    }

    private fun savePrefs() {
        Prefs.setName(this, nameEdit.text.toString().trim().ifEmpty { Prefs.DEFAULT_NAME })
        Prefs.setWake(this, wakeEdit.text.toString().trim())
        Prefs.setSecret(this, secretEdit.text.toString().trim())
        for ((c, e) in phraseEdits) {
            Prefs.setPhrases(this, c, e.text.toString().trim().ifEmpty { c.defaultPhrases })
        }
        Prefs.setReturnHome(this, homeSwitch.isChecked)
        Prefs.setMuteBeep(this, beepSwitch.isChecked)
        Prefs.setOrbDp(this, sizeBar.progress + SIZE_MIN)
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val pkg = Prefs.pkg(this)
        status.text = buildString {
            appendLine("${mark(hasMic())} สิทธิ์ไมค์")
            appendLine("${mark(ZontesAccessibilityService.instance != null)} Accessibility เปิดอยู่")
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} แสดงทับแอพอื่น (ไอคอนลอย)")
            append("${mark(pkg != null)} แอพ Zontes: ${pkg ?: "ยังไม่ได้เลือก"}")
        }
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun pickZontesApp(forcePicker: Boolean) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val guess = apps.filter {
            it.first.contains("zontes", ignoreCase = true) || it.second.contains("zontes", ignoreCase = true)
        }

        if (!forcePicker && Prefs.pkg(this) != null) return
        if (!forcePicker && guess.size == 1) {
            Prefs.setPkg(this, guess[0].second)
            refreshStatus()
            return
        }

        val list = if (guess.isNotEmpty()) guess else apps
        AlertDialog.Builder(this)
            .setTitle("เลือกแอพ Zontes Smart")
            .setItems(list.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i ->
                Prefs.setPkg(this, list[i].second)
                refreshStatus()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun testCommand(c: Command) {
        savePrefs()
        val svc = ZontesAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "ยังไม่ได้เปิด Accessibility", Toast.LENGTH_LONG).show()
            return
        }
        if (Prefs.pkg(this) == null) {
            Toast.makeText(this, "ยังไม่ได้เลือกแอพ Zontes", Toast.LENGTH_LONG).show()
            return
        }
        svc.execute(c) { ok ->
            Toast.makeText(this, if (ok) c.doneText else "สั่งไม่สำเร็จ", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVoice() {
        savePrefs()
        if (!hasMic()) {
            requestRuntimePerms()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this, "ยังไม่ได้อนุญาตแสดงทับแอพอื่น จะไม่มีไอคอนลอย", Toast.LENGTH_LONG
            ).show()
        }
        ContextCompat.startForegroundService(this, Intent(this, VoiceListenerService::class.java))
    }
}
