package com.berkay.oyunceviri.core

import kotlin.math.abs
import kotlin.math.max

/**
 * OCR çıktısını temizleme ve karşılaştırma yardımcıları. Android'e bağımlı değildir.
 */
object SubtitleText {

    private val WHITESPACE = Regex("\\s+")

    /** Satır sonlarını ve tekrar eden boşlukları tek boşluğa indirir. */
    fun normalize(raw: String): String = raw.replace(WHITESPACE, " ").trim()

    /** Harf oranı düşük çıktı (gürültü, semboller) altyazı sayılmaz. */
    fun looksLikeSubtitle(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        return letters >= 3 && letters * 100 / text.length >= 60
    }

    /** Karşılaştırma anahtarı: büyük/küçük harf, boşluk ve noktalama yok sayılır. */
    fun key(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** İki anahtar yaklaşık %8 farkla aynıysa aynı altyazıdır (OCR'ın tek harf titremesi). */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return true
        if (abs(a.length - b.length) > maxLen / 5) return false
        return editDistance(a, b) <= maxLen / 12 + 1
    }

    /** Levenshtein uzaklığı (iki satırlı bellek kullanımı). */
    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
