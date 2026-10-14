package com.berkay.oyunceviri

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Tek sorumluluk: bitmap -> ham metin. Karar mantığı (altyazı mı, yeni mi, ne zaman silinsin)
 * burada değil, Android'siz test edilebilen SubtitleTracker'dadır.
 */
class OcrProcessor {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Bitmap'i okur; çağıran, bitmap'in geri dönüşümünden (recycle) sorumludur. */
    suspend fun recognize(bitmap: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().text

    fun close() = recognizer.close()
}
