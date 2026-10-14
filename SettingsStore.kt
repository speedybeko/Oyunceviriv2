package com.berkay.oyunceviri

import android.content.Context

/** Kullanıcı ayarları (tek yerde, değişmez veri sınıfı). */
data class AppSettings(
    val regionTop: Int = 72,        // okunacak bölgenin üst kenarı: ekran yüksekliğinin % değeri (50..95)
    val tts: Boolean = false,
    val tv: Boolean = false,
    val tvSize: Int = 28,           // sp (20..48)
    val tvBg: Int = 70,             // % (0..100)
    val tvToken: String = "",       // TV'de görünen eşleştirme kodu
    val gemini: Boolean = false,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash-lite",
    val gameHint: String = "Mortal Kombat 11"
)

/**
 * SharedPreferences sarmalayıcısı (Repository). Anahtar adları önceki sürümle aynı,
 * bu yüzden güncelleme sonrası ayarlar korunur.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            regionTop = prefs.getInt("top", d.regionTop).coerceIn(50, 95),
            tts = prefs.getBoolean("tts", d.tts),
            tv = prefs.getBoolean("tv", d.tv),
            tvSize = prefs.getInt("tvSize", d.tvSize).coerceIn(20, 48),
            tvBg = prefs.getInt("tvBg", d.tvBg).coerceIn(0, 100),
            tvToken = prefs.getString("tvtoken", d.tvToken).orEmpty(),
            gemini = prefs.getBoolean("gemini", d.gemini),
            geminiKey = prefs.getString("gkey", d.geminiKey).orEmpty(),
            geminiModel = prefs.getString("gmodel", d.geminiModel).orEmpty().ifBlank { d.geminiModel },
            gameHint = prefs.getString("ggame", d.gameHint).orEmpty()
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt("top", s.regionTop)
            .putBoolean("tts", s.tts)
            .putBoolean("tv", s.tv)
            .putInt("tvSize", s.tvSize)
            .putInt("tvBg", s.tvBg)
            .putString("tvtoken", s.tvToken.trim())
            .putBoolean("gemini", s.gemini)
            .putString("gkey", s.geminiKey.trim())
            .putString("gmodel", s.geminiModel.trim())
            .putString("ggame", s.gameHint.trim())
            .apply()
    }
}
