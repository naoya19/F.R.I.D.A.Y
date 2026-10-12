package com.example.zontesvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * ฟังเสียงต่อเนื่องเบื้องหลัง (Foreground Service) แล้วส่งคำสั่งให้ ZontesAccessibilityService
 * พร้อมไอคอนลอย (FloatingOrb) แตะเพื่อหยุด/เล่นต่อ ลากลงปุ่ม X เพื่อปิด
 */
class VoiceListenerService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.zontesvoice.STOP"
        const val ACTION_TOGGLE = "com.example.zontesvoice.TOGGLE"
        private const val CHANNEL_ID = "zv_listen"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var running = false

    /** กำลังสั่งรถ/พูดตอบอยู่ (ภายใน) */
    private var busy = false

    /** ผู้ใช้แตะไอคอนเพื่อหยุดฟัง */
    private var userPaused = false

    private var pendingDone: (() -> Unit)? = null
    private var orb: FloatingOrb? = null
    private var notifBuilder: Notification.Builder? = null
    private var sessionMaxDb = -99f

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var beepMuted = false
    private val unmuteRunnable = Runnable { setBeepMuted(false) }

    private val recognizerIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
    }

    private val restartRunnable = Runnable { startListening() }
    private val speakFallback = Runnable { finishSpeak() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (running) toggleUserPause()
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY

        try {
            startAsForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        orb = FloatingOrb(this, onTap = { toggleUserPause() }, onClose = { stopSelf() }).also {
            it.show(Prefs.name(this), Prefs.orbDp(this))
        }
        refreshOrb()

        initTts()
        readyBeep()
        startListening()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        setBeepMuted(false)
        orb?.hide()
        orb = null
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        Diag.event("หยุดฟังแล้ว")
        super.onDestroy()
    }

    // ---------- Orb / pause ----------

    private fun refreshOrb() {
        val o = orb ?: return
        o.setName(Prefs.name(this))
        o.setState(
            when {
                userPaused -> OrbState.PAUSED
                busy -> OrbState.BUSY
                else -> OrbState.LISTENING
            }
        )
    }

    private fun toggleUserPause() {
        userPaused = !userPaused
        if (userPaused) {
            main.removeCallbacks(restartRunnable)
            recognizer?.cancel()
            setBeepMuted(false)
            Diag.event("หยุดฟังชั่วคราว (แตะไอคอนเพื่อเล่นต่อ)")
        } else if (!busy) {
            readyBeep()
            scheduleRestart(400)
        }
        refreshOrb()
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "กำลังฟังคำสั่งเสียง", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, VoiceListenerService::class.java).setAction(ACTION_STOP), flags
        )
        val toggleIntent = PendingIntent.getService(
            this, 2, Intent(this, VoiceListenerService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), flags
        )
        val wake = Prefs.wake(this).split(',').firstOrNull { it.isNotBlank() }?.trim() ?: Prefs.name(this)
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${Prefs.name(this)} กำลังฟังอยู่")
            .setContentText("พูดว่า \"$wake ปลดล็อก\" หรือแตะไอคอนลอยเพื่อหยุด/เล่นต่อ")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "หยุด/เล่นต่อ", toggleIntent).build())
            .addAction(Notification.Action.Builder(null, "ปิดแอพ", stopIntent).build())
            .setOngoing(true)
        notifBuilder = b
        val notif = b.build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotif(text: String) {
        val b = notifBuilder ?: return
        b.setContentText(text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, b.build())
        } catch (e: Exception) {
        }
    }

    // ---------- Beep muting ----------

    private fun setBeepMuted(on: Boolean) {
        if (on == beepMuted) return
        beepMuted = on
        val streams = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM
        )
        for (s in streams) {
            try {
                audio.adjustStreamVolume(
                    s, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
                )
            } catch (e: Exception) {
                // บางเครื่องไม่อนุญาตบาง stream (เช่นติด Do Not Disturb) ข้ามไป
            }
        }
    }

    /** ตัดเสียงติ๊งของตัวรู้จำเสียงเฉพาะช่วงสั้น ๆ รอบการเริ่ม/จบการฟัง */
    @Suppress("UNUSED_PARAMETER")
    private fun muteBeepWindow(ms: Long = 0) {
        // V.2: ปิดเสียงค้างไว้ตลอดช่วงฟัง ไม่สลับปิด/เปิดทุกรอบ (กันเสียงติ๊งวนและจอแสดงระดับเสียงกระพริบ)
        // จะเปิดคืนตอนพูดตอบ ตอนหยุดชั่วคราว และตอนปิดแอพ
        if (!Prefs.muteBeep(this)) return
        main.removeCallbacks(unmuteRunnable)
        setBeepMuted(true)
    }

    /** เสียงปิ๊ปของเราเอง 1 ครั้ง บอกว่าพร้อมรับคำสั่ง (ใช้ช่อง ALARM จึงไม่โดนปิดเสียง) */
    private fun readyBeep() {
        try {
            val tg = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 70)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180)
            main.postDelayed({ tg.release() }, 400)
        } catch (e: Exception) {
        }
    }

    // ---------- Speech recognition ----------

    private fun errName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT (เน็ตช้า)"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK (ไม่มีเน็ต)"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO (ไมค์มีปัญหา)"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ไม่ได้ยินเสียงพูด"
        SpeechRecognizer.ERROR_NO_MATCH -> "ฟังไม่ออกเป็นคำ"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ไม่มีสิทธิ์ไมค์"
        10 -> "TOO_MANY_REQUESTS"
        11 -> "SERVER_DISCONNECTED"
        12 -> "ไม่รองรับภาษาไทย"
        13 -> "ภาษาไทยยังไม่พร้อมใช้งาน"
        else -> "error $code"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Diag.event("กำลังฟัง…")
        }

        override fun onBeginningOfSpeech() {
            Diag.event("ได้ยินเสียงพูด กำลังถอดความ…")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > sessionMaxDb) sessionMaxDb = rmsdB
            orb?.setLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onEndOfSpeech() {
            muteBeepWindow()
        }

        override fun onResults(results: Bundle?) {
            muteBeepWindow()
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            handle(texts)
        }

        override fun onError(error: Int) {
            if (!running || busy || userPaused) return
            muteBeepWindow()
            Diag.event("${errName(error)} (เสียงสูงสุด ${"%.1f".format(sessionMaxDb)} dB)" + clientDiag(error))
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(300)
                else -> {
                    // สร้างตัวรู้จำเสียงใหม่ ป้องกันค้างหลัง error
                    recognizer?.destroy()
                    recognizer = null
                    scheduleRestart(1_500)
                }
            }
        }
    }

    // ถ้าเจอ CLIENT ติด ๆ กัน ให้สลับไปผูกกับบริการของ Google โดยตรง (ข้ามค่าเริ่มต้นของเครื่อง)
    private var forceGoogle = false
    private val googleRecognizer = android.content.ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    )

    private fun createRecognizer(): SpeechRecognizer {
        if (forceGoogle) {
            try {
                packageManager.getServiceInfo(googleRecognizer, 0)
                return SpeechRecognizer.createSpeechRecognizer(this, googleRecognizer)
            } catch (e: Exception) {
                // ไม่มีบริการนี้ ใช้ค่าเริ่มต้น
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    /** ข้อมูลช่วยหาสาเหตุ error CLIENT: ใครบันทึกเสียงอยู่ และใช้บริการรู้จำเสียงตัวไหน */
    private fun clientDiag(error: Int): String {
        if (error != SpeechRecognizer.ERROR_CLIENT) return ""
        forceGoogle = !forceGoogle
        val recs = try {
            audio.activeRecordingConfigurations.size
        } catch (e: Exception) {
            -1
        }
        val svc = try {
            android.provider.Settings.Secure.getString(contentResolver, "voice_recognition_service")
        } catch (e: Exception) {
            null
        }
        val avail = SpeechRecognizer.isRecognitionAvailable(this)
        return "\nบริการ: ${svc ?: "ค่าเริ่มต้น"} | พร้อมใช้: $avail | มีแอพบันทึกเสียงอยู่: $recs" +
            " | รอบหน้า: ${if (forceGoogle) "ผูก Google ตรง" else "ค่าเริ่มต้น"}"
    }

    private fun startListening() {
        if (!running || busy || userPaused) return
        main.removeCallbacks(restartRunnable)
        muteBeepWindow(1_000)
        sessionMaxDb = -99f
        try {
            if (recognizer == null) {
                recognizer = createRecognizer().also {
                    it.setRecognitionListener(listener)
                }
            }
            recognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Diag.event("เริ่มฟังไม่ได้: ${e.javaClass.simpleName}")
            recognizer?.destroy()
            recognizer = null
            scheduleRestart(2_000)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, delayMs)
    }

    private fun resume() {
        busy = false
        refreshOrb()
        if (!userPaused) scheduleRestart(500)
    }

    // ---------- Command handling ----------

    private fun handle(texts: List<String>) {
        if (userPaused || busy) return

        val wake = Prefs.wake(this)
        val secret = Prefs.secret(this)
        val phrases = Command.values().associateWith { Prefs.phrases(this, it) }

        var needSecret = false
        var sawPhrase = false
        var run: Command? = null
        for (t in texts) {
            when (val r = CommandParser.parse(t, wake, secret, phrases)) {
                is Parsed.Run -> {
                    run = r.cmd
                    break
                }
                Parsed.NeedSecret -> needSecret = true
                Parsed.NoPhrase -> sawPhrase = true
                Parsed.NoWake -> {}
            }
        }

        val heard = if (texts.isEmpty()) "(ว่าง)" else texts.take(2).joinToString(" | ") { "\"$it\"" }
        val outcome = when {
            run != null -> "→ ${run.label}"
            needSecret -> "→ ต้องพูดรหัสลับ"
            sawPhrase -> "→ ไม่ตรงวลีคำสั่งใด ๆ"
            else -> "→ ไม่ตรงคำปลุก"
        }
        Diag.heard("$heard $outcome")
        updateNotif("ได้ยิน: ${texts.firstOrNull() ?: "-"} $outcome")

        when {
            run != null -> runCommand(run)
            needSecret -> {
                busy = true
                refreshOrb()
                speakThen("ต้องบอกรหัสด้วยครับ") { resume() }
            }
            else -> scheduleRestart(200)
        }
    }

    private fun runCommand(cmd: Command) {
        busy = true
        refreshOrb()
        recognizer?.cancel()
        Diag.event("กำลังสั่ง ${cmd.label}…")

        val svc = ZontesAccessibilityService.instance
        if (svc == null) {
            Diag.event("Accessibility ไม่ได้เปิด (ถูกระบบปิด?) เปิดหน้าตั้งค่าให้แล้ว")
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            speakThen("ยังไม่ได้เปิดสิทธิ์การเข้าถึงให้แอพ") { resume() }
            return
        }
        svc.execute(cmd) { ok ->
            Diag.event(if (ok) "สั่ง ${cmd.label} สำเร็จ" else "สั่ง ${cmd.label} ไม่สำเร็จ")
            speakThen(if (ok) cmd.doneText else "สั่งรถไม่สำเร็จ") { resume() }
        }
    }

    // ---------- Text to speech ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { finishSpeak() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { finishSpeak() }
                    }
                })
                ttsReady = true
            }
        }
    }

    private fun speakThen(text: String, done: () -> Unit) {
        // เปิดเสียงคืนก่อนพูด เผื่อยังอยู่ในช่วงตัดเสียงติ๊ง
        main.removeCallbacks(unmuteRunnable)
        setBeepMuted(false)

        val t = tts
        if (t == null || !ttsReady) {
            done()
            return
        }
        pendingDone = done
        main.removeCallbacks(speakFallback)
        main.postDelayed(speakFallback, 8_000)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zv_${System.nanoTime()}")
    }

    private fun finishSpeak() {
        main.removeCallbacks(speakFallback)
        val d = pendingDone
        pendingDone = null
        d?.invoke()
    }
}
