package com.berkay.oyunceviri

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.berkay.oyunceviri.core.TokenAuth
import kotlinx.coroutines.launch

/**
 * Ayar ekranı. İzin akışı Activity Result API ile (registerForActivityResult):
 * kaplama izni -> bildirim izni -> ekran yakalama izni -> servis.
 * Her adım kendi launcher'ının sonucunda bir sonrakine geçer; requestCode/onActivityResult yok.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore

    private lateinit var status: TextView
    private lateinit var preview: RegionPreviewView
    private lateinit var lastOcr: TextView
    private lateinit var lastTranslation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var regionLabel: TextView
    private lateinit var regionSeek: SeekBar
    private lateinit var ttsSwitch: Switch
    private lateinit var tvSwitch: Switch
    private lateinit var tvTokenEdit: EditText
    private lateinit var tvSizeSeek: SeekBar
    private lateinit var tvSizeLabel: TextView
    private lateinit var tvBgSeek: SeekBar
    private lateinit var tvBgLabel: TextView
    private lateinit var geminiSwitch: Switch
    private lateinit var gKeyEdit: EditText
    private lateinit var gModelEdit: EditText
    private lateinit var gGameEdit: EditText

    // ---- Activity Result API ----

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Ayar ekranından dönülünce izin verilmişse akışa devam et
        if (Settings.canDrawOverlays(this)) continueAfterOverlay()
        else status.text = "Diğer uygulamaların üzerinde gösterme izni verilmedi."
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestCapture() // bildirim izni verilmese de çalışır (yalnızca bildirim görünmez)
    }

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            status.text = "Ekran yakalama izni verilmedi."
        } else {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
            )
            status.text = "Çalışıyor. Şimdi Xbox uygulamasına geç. İlk seferde çeviri modeli inerken internet gerekir."
        }
    }

    // ---- yardımcılar ----

    private fun tvSize() = 20 + tvSizeSeek.progress   // 20..48 sp
    private fun tvBg() = tvBgSeek.progress            // 0..100 %
    private fun regionTop() = 50 + regionSeek.progress // 50..95 %
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun SeekBar.onChange(block: () -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = block()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { persist() }
        })
    }

    private fun readSettings() = AppSettings(
        regionTop = regionTop(),
        tts = ttsSwitch.isChecked,
        tv = tvSwitch.isChecked,
        tvSize = tvSize(),
        tvBg = tvBg(),
        tvToken = tvTokenEdit.text.toString(),
        gemini = geminiSwitch.isChecked,
        geminiKey = gKeyEdit.text.toString(),
        geminiModel = gModelEdit.text.toString(),
        gameHint = gGameEdit.text.toString()
    )

    private fun persist() = store.save(readSettings())

    // ---- arayüz ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val s = store.load()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#121212"))
        }

        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
        }

        fun field(hintText: String, initial: String, secret: Boolean = false) = EditText(this).apply {
            setSingleLine(true)
            hint = hintText
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { persist() }
            })
        }

        fun switch(t: String, checked: Boolean) = Switch(this).apply {
            text = t
            setTextColor(Color.WHITE)
            isChecked = checked
            setOnCheckedChangeListener { _, _ -> persist() }
        }

        root.addView(label("Oyun Çeviri", 26f))
        root.addView(
            label(
                "1) Başlat'a bas, ekran yakalamaya izin ver.\n" +
                    "2) Xbox uygulamasını aç, oyunu uzaktan oynat.\n" +
                    "3) Altyazı ekranın alt kısmında olmalı; çubukla okunacak bölgenin üst kenarını ayarla.",
                14f
            )
        )

        // Önizleme + bölge ayarı
        preview = RegionPreviewView(this).apply { regionTopPercent = s.regionTop }
        root.addView(preview)
        regionLabel = label("")
        regionSeek = SeekBar(this).apply {
            max = 45
            progress = s.regionTop - 50
            onChange {
                updateRegionLabel()
                preview.regionTopPercent = regionTop()
                SessionBus.regionTop.value = regionTop() // çalışan servis anında kullanır
            }
        }
        root.addView(regionLabel)
        root.addView(regionSeek)
        updateRegionLabel()

        lastOcr = label("Okunan: -", 13f)
        lastTranslation = label("Çeviri: -", 13f)
        root.addView(lastOcr)
        root.addView(lastTranslation)

        ttsSwitch = switch("Türkçe seslendirme", s.tts)
        root.addView(ttsSwitch)

        // Gemini (isteğe bağlı)
        geminiSwitch = switch("Gemini ile çevir (daha doğal, internet gerekir)", s.gemini)
        root.addView(geminiSwitch)
        gKeyEdit = field("Gemini API anahtarı (aistudio.google.com/apikey)", s.geminiKey, true)
        gModelEdit = field("Model adı", s.geminiModel)
        gGameEdit = field("Oyun adı (isteğe bağlı, isimler için)", s.gameHint)
        root.addView(gKeyEdit)
        root.addView(gModelEdit)
        root.addView(gGameEdit)

        // Android TV
        tvSwitch = switch("Çeviriyi TV'ye de gönder (TV'de Oyun Çeviri TV açık olmalı)", s.tv)
        root.addView(tvSwitch)
        tvTokenEdit = field("TV'de görünen eşleştirme kodu", s.tvToken).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(tvTokenEdit)
        tvStatus = label("TV: kapalı", 13f)
        root.addView(tvStatus)

        tvSizeLabel = label("")
        tvSizeSeek = SeekBar(this).apply {
            max = 28
            progress = s.tvSize - 20
            onChange { updateTvLabels() }
        }
        tvBgLabel = label("")
        tvBgSeek = SeekBar(this).apply {
            max = 100
            progress = s.tvBg
            onChange { updateTvLabels() }
        }
        root.addView(tvSizeLabel)
        root.addView(tvSizeSeek)
        root.addView(tvBgLabel)
        root.addView(tvBgSeek)
        updateTvLabels()

        root.addView(Button(this).apply {
            text = "Başlat"
            setOnClickListener { onStartClicked() }
        })
        root.addView(Button(this).apply {
            text = "Durdur"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "Durduruldu."
            }
        })
        status = label("Hazır.", 14f).apply { gravity = Gravity.START }
        root.addView(status)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            addView(root)
        })

        observeSession()
    }

    /** Servisin yayınladığı canlı durumu (önizleme, metinler, TV durumu) toplar. */
    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SessionBus.preview.collect { preview.bitmap = it } }
                launch { SessionBus.lastOcr.collect { lastOcr.text = "Okunan: ${it.ifEmpty { "-" }}" } }
                launch {
                    SessionBus.lastTranslation.collect { lastTranslation.text = "Çeviri: ${it.ifEmpty { "-" }}" }
                }
                launch { SessionBus.tvState.collect { tvStatus.text = describe(it) } }
                launch {
                    SessionBus.running.collect { if (!it && status.text.startsWith("Çalışıyor")) status.text = "Durdu." }
                }
            }
        }
    }

    private fun describe(state: TvLink.State): String = when (state) {
        TvLink.State.Idle -> "TV: kapalı"
        TvLink.State.Searching -> "TV: aranıyor..."
        is TvLink.State.Connecting -> "TV: bağlanıyor (${state.host})"
        is TvLink.State.Connected -> "TV: bağlandı (${state.host})"
        TvLink.State.Unauthorized -> "TV: kod yanlış. TV'deki kodu kontrol et, sonra Başlat'a tekrar bas."
        TvLink.State.Lost -> "TV: bağlantı koptu, yeniden deneniyor"
    }

    // Ana ekran görünürken yakalama durur (kendi arayüzümüzü okumayalım); önizleme son oyun karesinde kalır
    override fun onStart() {
        super.onStart()
        SessionBus.uiVisible = true
    }

    override fun onStop() {
        SessionBus.uiVisible = false
        persist()
        super.onStop()
    }

    private fun updateTvLabels() {
        tvSizeLabel.text = "TV yazı boyutu: ${tvSize()}"
        tvBgLabel.text = "TV arka plan koyuluğu: %${tvBg()}"
    }

    private fun updateRegionLabel() {
        regionLabel.text = "Okunacak bölge: ekranın alt %${100 - regionTop()}'i"
    }

    // ---- izin akışı ----

    private fun onStartClicked() {
        persist()
        if (tvSwitch.isChecked && TokenAuth.normalize(tvTokenEdit.text.toString()).length != TokenAuth.LENGTH) {
            status.text = "TV için TV ekranındaki 8 haneli kodu yaz (ya da TV anahtarını kapat)."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Diğer uygulamaların üzerinde gösterme iznini ver, geri dönünce kendiliğinden devam eder."
            overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        continueAfterOverlay()
    }

    private fun continueAfterOverlay() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        capturePermission.launch(mpm.createScreenCaptureIntent())
    }
}
