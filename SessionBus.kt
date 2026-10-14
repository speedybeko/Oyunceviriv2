package com.berkay.oyunceviri

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Servis (CaptureService) ile ekran (MainActivity) arasındaki canlı durum köprüsü.
 * Singleton + gözlemlenebilir durum (StateFlow): servis yazar, arayüz toplar.
 * Aynı süreçte çalıştıkları için Binder/AIDL gerekmez.
 */
object SessionBus {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** Oyun ekranının küçük (≈480 px) kopyası; yalnızca uygulama arayüzü görünmüyorken güncellenir. */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _lastOcr = MutableStateFlow("")
    val lastOcr: StateFlow<String> = _lastOcr.asStateFlow()

    private val _lastTranslation = MutableStateFlow("")
    val lastTranslation: StateFlow<String> = _lastTranslation.asStateFlow()

    private val _tvState = MutableStateFlow<TvLink.State>(TvLink.State.Idle)
    val tvState: StateFlow<TvLink.State> = _tvState.asStateFlow()

    /** Okunacak bölgenin üst kenarı (%). Ayar çubuğu canlı günceller, servis her karede okur. */
    val regionTop = MutableStateFlow(72)

    /** Ana ekran görünürken yakalama durur: yoksa kendi arayüzümüzü okurduk. */
    @Volatile var uiVisible: Boolean = false

    fun setRunning(value: Boolean) { _running.value = value }
    fun setPreview(bitmap: Bitmap?) { _preview.value = bitmap }
    fun setLastOcr(text: String) { _lastOcr.value = text }
    fun setLastTranslation(text: String) { _lastTranslation.value = text }
    fun setTvState(state: TvLink.State) { _tvState.value = state }

    /** Oturum bittiğinde eski kareyi ve metinleri bırakmamak için. */
    fun clearSession() {
        _running.value = false
        _preview.value = null
        _lastOcr.value = ""
        _lastTranslation.value = ""
        _tvState.value = TvLink.State.Idle
    }
}
