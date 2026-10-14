package com.berkay.oyunceviri.core

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/** Telefon <-> TV WebSocket protokolü: ortak sabitler ve mesaj üreticileri. */
object TvProtocol {
    const val PORT = 8765
    const val SERVICE_TYPE = "_oyunceviri._tcp."
    const val SERVICE_NAME = "OyunCeviri-TV"

    /** Sunucu yanlış/eksik token'lı bağlantıyı bu kodla kapatır (WebSocket "policy violation"). */
    const val CLOSE_UNAUTHORIZED = 1008

    fun subtitle(text: String): String = """{"type":"sub","text":${quote(text)}}"""

    fun clear(): String = """{"type":"clear"}"""

    /** TV, doğru koda sahip bağlantıya ilk iş bunu yollar; telefon "bağlandı" saymak için bunu bekler. */
    fun hello(): String = """{"type":"hello"}"""

    fun isHello(message: String): Boolean = message.contains("\"type\":\"hello\"")

    fun style(sizeSp: Int, bgPercent: Int, shadow: Boolean): String =
        """{"type":"style","size":$sizeSp,"bg":$bgPercent,"shadow":$shadow}"""

    /** Bağlantı adresindeki `?token=...` değerini çıkarır; yoksa null. */
    fun tokenFromResource(resource: String?): String? {
        val query = resource?.substringAfter('?', "") ?: return null
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') != "token") continue
            return try {
                URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    /** JSON dizesi üretir (tırnak, ters bölü ve kontrol karakterleri kaçırılır). */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** Basit eşleştirme kodu: TV üretir ve ekranda gösterir, telefon bağlanırken gönderir. */
object TokenAuth {
    // Karışan karakterler (0/O, 1/I) yok
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8

    fun generate(random: Random = SecureRandom()): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Kullanıcının yazdığı kodu temizler: boşluk/tire atılır, büyük harfe çevrilir. */
    fun normalize(input: String): String =
        input.uppercase().filter { ALPHABET.indexOf(it) >= 0 }

    /** Sabit zamanlı karşılaştırma. */
    fun matches(expected: String, provided: String?): Boolean {
        if (provided == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(
            normalize(expected).toByteArray(Charsets.UTF_8),
            normalize(provided).toByteArray(Charsets.UTF_8)
        )
    }
}
