package com.berkay.oyunceviri.core

/**
 * Altyazının gösterileceği/okunacağı yerler (telefon kutusu, TV, seslendirme).
 * Gözlemci/Composite deseni: boru hattı tek bir [SubtitleSink] görür.
 * Android arayüz nesneleri olduğu için çağrılar ana thread'den yapılır.
 */
interface SubtitleSink {
    fun show(text: String)
    fun clear()
}

/** Birden çok [SubtitleSink]'i tek bir sink gibi kullanır; birinin hatası diğerlerini engellemez. */
class CompositeSink(
    private val sinks: List<SubtitleSink>,
    private val onError: (SubtitleSink, Exception) -> Unit = { _, _ -> }
) : SubtitleSink {

    override fun show(text: String) = forEachSink { it.show(text) }

    override fun clear() = forEachSink { it.clear() }

    private inline fun forEachSink(action: (SubtitleSink) -> Unit) {
        for (s in sinks) {
            try {
                action(s)
            } catch (e: Exception) {
                onError(s, e)
            }
        }
    }
}
