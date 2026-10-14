package com.berkay.oyunceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.berkay.oyunceviri.core.CompositeSink
import com.berkay.oyunceviri.core.SubtitleEvent
import com.berkay.oyunceviri.core.SubtitleSink
import com.berkay.oyunceviri.core.SubtitleTracker
import com.berkay.oyunceviri.core.TokenAuth
import com.berkay.oyunceviri.core.TranslationException
import com.berkay.oyunceviri.core.TranslatorManager
import com.berkay.oyunceviri.core.TvProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Yalnızca "orkestra şefi": parçaları kurar, boru hattını bağlar, kapanışta hepsini temizler.
 *
 *   ScreenCapturer.frames ──► OcrProcessor ──► SubtitleTracker ──► TranslatorManager ──► SubtitleSink
 *     (conflated Flow)        (ham metin)      (karar: Show/Clear)   (Gemini -> çevrimdışı)   (ekran + TV + ses)
 *
 * Son aşamada collectLatest kullanılır: yeni olay gelince eski satırın bekleyen çevirisi iptal edilir,
 * böylece eski altyazı yeni altyazının üstüne yazılmaz.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.berkay.oyunceviri.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val TAG = "OyunCeviri"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private var capturer: ScreenCapturer? = null
    private var ocr: OcrProcessor? = null
    private var translator: TranslatorManager? = null
    private var overlay: SubtitleOverlay? = null
    private var tts: TtsManager? = null
    private var tvLink: TvLink? = null
    private var pipelineJob: Job? = null
    private var regionJob: Job? = null
    private var lastNotice = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground() // Android 14: projection almadan önce ön plana çıkmak şart

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        releaseAll() // tekrar başlatılırsa temiz başla
        try {
            start(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "Başlatılamadı", e)
            notice("Başlatılamadı: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        val settings = SettingsStore(this).load()
        SessionBus.regionTop.value = settings.regionTop

        val overlay = SubtitleOverlay(this).also { it.setRegionTop(settings.regionTop) }
        this.overlay = overlay

        val sinks = mutableListOf<SubtitleSink>(overlay)
        if (settings.tts) {
            tts = TtsManager(this) { notice(it) }.also { sinks += it }
        }
        if (settings.tv) {
            val token = TokenAuth.normalize(settings.tvToken)
            if (token.length != TokenAuth.LENGTH) {
                notice("TV kodu eksik. TV'de görünen 8 haneli kodu uygulamaya yaz.")
            } else {
                val style = TvProtocol.style(settings.tvSize, settings.tvBg, true)
                tvLink = TvLink(this, scope, token) { style }.also {
                    sinks += it
                    it.start()
                }
            }
        }
        val sink = CompositeSink(sinks) { s, e -> Log.w(TAG, "Sink hatası: ${s::class.simpleName}", e) }

        val offline = MlKitTranslator { notice("Çeviri modeli hazır") }
        val gemini = if (settings.gemini) {
            if (settings.geminiKey.isBlank()) {
                notice("Gemini anahtarı boş, çevrimdışı çeviri kullanılacak")
                null
            } else {
                GeminiTranslator(settings.geminiKey, settings.geminiModel, settings.gameHint)
            }
        } else null
        val manager = TranslatorManager(gemini, offline, { SystemClock.elapsedRealtime() }) { notice(it) }
        translator = manager

        val ocr = OcrProcessor().also { this.ocr = it }

        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(code, data)
        val capturer = ScreenCapturer(this, projection) { main.post { stopSelf() } }
        this.capturer = capturer

        regionJob = scope.launch {
            SessionBus.regionTop.collect { top ->
                withContext(Dispatchers.Main) { this@CaptureService.overlay?.setRegionTop(top) }
            }
        }
        pipelineJob = scope.launch { runPipeline(capturer, ocr, manager, sink) }
        SessionBus.setRunning(true)
    }

    private suspend fun runPipeline(
        capturer: ScreenCapturer,
        ocr: OcrProcessor,
        translator: TranslatorManager,
        sink: SubtitleSink
    ) {
        val tracker = SubtitleTracker(stableFramesRequired = 2, clearDelayMs = 500)
        try {
            capturer.frames
                .map { bitmap ->
                    try {
                        ocr.recognize(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                .map { text ->
                    SessionBus.setLastOcr(text.replace('\n', ' ').trim())
                    synchronized(tracker) { tracker.onOcr(text, SystemClock.elapsedRealtime()) }
                }
                .filter { it !is SubtitleEvent.None }
                .collectLatest { event ->
                    when (event) {
                        is SubtitleEvent.Show -> showTranslated(event.text, translator, sink, tracker)
                        SubtitleEvent.Clear -> withContext(Dispatchers.Main) { sink.clear() }
                        SubtitleEvent.None -> Unit
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Boru hattı durdu", e)
            notice("Beklenmeyen hata: ${e.message}")
        }
    }

    private suspend fun showTranslated(
        text: String,
        translator: TranslatorManager,
        sink: SubtitleSink,
        tracker: SubtitleTracker
    ) {
        val translated = try {
            translator.translate(text)
        } catch (e: TranslationException) {
            notice(e.message ?: "Çeviri hatası")
            delay(2_000) // yeni olay gelirse bu iptal olur
            synchronized(tracker) { tracker.reset() } // aynı satır tekrar denensin
            return
        }
        SessionBus.setLastTranslation(translated)
        withContext(Dispatchers.Main) { sink.show(translated) }
    }

    // Ekran döndürülünce (Xbox uygulaması yatay moda geçer) yakalamayı yeniden kur
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            capturer?.configure()
            overlay?.setRegionTop(SessionBus.regionTop.value)
        }, 400)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cap", "Oyun Çeviri", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val notif = Notification.Builder(this, "cap")
            .setContentTitle("Oyun Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_delete, "Durdur", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    /** Aynı mesajı arka arkaya göstermez (hatalı model her 2 sn'de toast yağdırmasın). */
    private fun notice(msg: String) {
        main.post {
            if (msg == lastNotice) return@post
            lastNotice = msg
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            main.postDelayed({ if (lastNotice == msg) lastNotice = "" }, 10_000)
        }
    }

    // ---------- Temizlik ----------

    private fun releaseAll() {
        pipelineJob?.cancel(); pipelineJob = null
        regionJob?.cancel(); regionJob = null
        capturer?.release(); capturer = null       // VirtualDisplay, ImageReader, MediaProjection, thread
        ocr?.close(); ocr = null
        translator?.close(); translator = null     // Gemini + ML Kit
        tts?.close(); tts = null
        tvLink?.stop(); tvLink = null              // WebSocket, NSD, OkHttp
        overlay?.close(); overlay = null
        SessionBus.clearSession()
    }

    override fun onDestroy() {
        releaseAll()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
