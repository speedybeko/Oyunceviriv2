package com.berkay.oyunceviri

import android.content.Context
import android.speech.tts.TextToSpeech
import com.berkay.oyunceviri.core.SubtitleSink
import java.util.Locale

/** Türkçe seslendirme. Altyazı hedeflerinden biri (SubtitleSink); ana thread'de oluşturulmalı. */
class TtsManager(
    context: Context,
    private val onProblem: (String) -> Unit
) : SubtitleSink, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onProblem("Seslendirme başlatılamadı")
            return
        }
        val result = tts?.setLanguage(Locale("tr", "TR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onProblem("Telefonda Türkçe ses verisi yok (Ayarlar > Metin okuma)")
        } else {
            ready = true
        }
    }

    override fun show(text: String) {
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sub")
    }

    /** Altyazı kaybolsa da konuşma yarıda kesilmez. */
    override fun clear() = Unit

    fun close() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
