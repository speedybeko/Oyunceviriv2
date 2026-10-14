package com.berkay.oyunceviri.core

/** Altyazı takipçisinin karar çıktısı. */
sealed interface SubtitleEvent {
    /** Yeni bir altyazı satırı kararlı hale geldi: çevirilip gösterilmeli. */
    data class Show(val text: String) : SubtitleEvent

    /** Altyazı bir süredir yok: ekran temizlenmeli. */
    object Clear : SubtitleEvent

    /** Yapılacak bir şey yok. */
    object None : SubtitleEvent
}

/**
 * OCR karelerinden "ne zaman çevir, ne zaman temizle" kararını verir (durum makinesi).
 * Android ve zamanlayıcı kullanmaz: zaman, [onOcr]'a verilen damgayla ölçülür, bu yüzden test edilebilir.
 *
 * - Aynı satır [stableFramesRequired] karede art arda görülürse [SubtitleEvent.Show] döner
 *   (tek karelik OCR gürültüsü yok sayılır).
 * - Ekranda gösterilen satırın OCR'daki küçük titremeleri yeni satır sayılmaz.
 * - Altyazı [clearDelayMs] boyunca görünmezse [SubtitleEvent.Clear] döner. Bu gecikme, altyazı
 *   bir an kaybolup geri geldiğinde kutunun yanıp sönmesini önler (debounce).
 *
 * Tek bir thread'den kullanılmalıdır.
 */
class SubtitleTracker(
    private val stableFramesRequired: Int = 2,
    private val clearDelayMs: Long = 500L
) {
    private var shownKey = ""
    private var candidateKey = ""
    private var candidateCount = 0
    private var emptySince = NOT_EMPTY

    fun onOcr(raw: String, nowMs: Long): SubtitleEvent {
        val text = SubtitleText.normalize(raw)
        if (!SubtitleText.looksLikeSubtitle(text)) return onEmptyFrame(nowMs)

        emptySince = NOT_EMPTY
        val key = SubtitleText.key(text)

        // Ekrandaki satırın aynısı: bir şey yapma
        if (shownKey.isNotEmpty() && SubtitleText.similar(key, shownKey)) {
            candidateKey = ""
            candidateCount = 0
            return SubtitleEvent.None
        }

        if (candidateKey.isNotEmpty() && SubtitleText.similar(key, candidateKey)) {
            candidateCount++
        } else {
            candidateKey = key
            candidateCount = 1
        }
        if (candidateCount < stableFramesRequired) return SubtitleEvent.None

        shownKey = key
        candidateKey = ""
        candidateCount = 0
        return SubtitleEvent.Show(text)
    }

    private fun onEmptyFrame(nowMs: Long): SubtitleEvent {
        if (emptySince == NOT_EMPTY) emptySince = nowMs
        if (nowMs - emptySince < clearDelayMs) return SubtitleEvent.None

        val hadSubtitle = shownKey.isNotEmpty()
        reset()
        return if (hadSubtitle) SubtitleEvent.Clear else SubtitleEvent.None
    }

    /** Tüm durumu sıfırlar. */
    fun reset() {
        shownKey = ""
        candidateKey = ""
        candidateCount = 0
        emptySince = NOT_EMPTY
    }

    private companion object {
        const val NOT_EMPTY = -1L
    }
}
