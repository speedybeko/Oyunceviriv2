package com.berkay.oyunceviri.core

/** Çeviri motoru hatası. [code]: HTTP kodu (ör. 429), ağ/ayrıştırma hatasında 0. */
class TranslationException(val code: Int, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** İngilizce -> Türkçe altyazı çevirmeni (Strateji deseni: çevrimdışı ve Gemini aynı arayüzü uygular). */
interface SubtitleTranslator {
    /** @throws TranslationException çeviri yapılamazsa */
    suspend fun translate(text: String): String

    fun close() {}
}

/**
 * Birincil motoru (Gemini) dener, olmazsa yedek motora (çevrimdışı) düşer.
 *
 * - 429 (limit): birincil motor [rateLimitPauseMs] boyunca atlanır.
 * - Diğer 4xx (anahtar/model hatası): birincil motor oturum boyunca kapatılır.
 * - Ağ hatası, 5xx, boş yanıt (kod 0 ya da 5xx): yalnızca o çeviri için yedek kullanılır.
 *
 * Android'e bağımlı değildir; zaman [clock] ile verilir.
 */
class TranslatorManager(
    primary: SubtitleTranslator?,
    private val fallback: SubtitleTranslator,
    private val clock: () -> Long,
    private val rateLimitPauseMs: Long = 60_000L,
    private val onNotice: (String) -> Unit = {}
) {
    @Volatile private var primary: SubtitleTranslator? = primary
    @Volatile private var pausedUntil = 0L

    suspend fun translate(text: String): String {
        val p = primary
        if (p != null && clock() >= pausedUntil) {
            try {
                return p.translate(text)
            } catch (e: TranslationException) {
                when {
                    e.code == 429 -> {
                        pausedUntil = clock() + rateLimitPauseMs
                        onNotice("Gemini sınırına takıldı, 1 dk çevrimdışı çeviri kullanılacak")
                    }
                    e.code in 400..499 -> {
                        primary = null
                        p.close()
                        onNotice("Gemini hatası (${e.code}). Anahtarı ve model adını kontrol et. Çevrimdışı çeviriye geçildi.")
                    }
                    else -> Unit // geçici hata: bu satır için yedek motor
                }
            }
        }
        return fallback.translate(text)
    }

    fun close() {
        primary?.close()
        fallback.close()
    }
}
