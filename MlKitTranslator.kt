package com.berkay.oyunceviri

import com.berkay.oyunceviri.core.SubtitleTranslator
import com.berkay.oyunceviri.core.TranslationException
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Çevrimdışı İngilizce -> Türkçe (ML Kit). Model ilk çeviride bir kez indirilir. */
class MlKitTranslator(
    private val onModelReady: () -> Unit = {}
) : SubtitleTranslator {

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )
    private val modelLock = Mutex()
    @Volatile private var modelReady = false

    override suspend fun translate(text: String): String {
        ensureModel()
        return try {
            translator.translate(text).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(0, "Çeviri başarısız", e)
        }
    }

    private suspend fun ensureModel() {
        if (modelReady) return
        modelLock.withLock {
            if (modelReady) return
            try {
                translator.downloadModelIfNeeded().awaitResult()
                modelReady = true
                onModelReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw TranslationException(0, "Çeviri modeli indirilemedi. İnternete bağlanıp tekrar dene.", e)
            }
        }
    }

    override fun close() = translator.close()
}
