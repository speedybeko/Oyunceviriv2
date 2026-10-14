package com.berkay.oyunceviri.tv

import android.content.Context
import com.berkay.oyunceviri.core.TokenAuth

/** TV'nin eşleştirme kodu: ilk açılışta üretilir, kalıcıdır; "Yeni kod üret" ile değiştirilir. */
object TvToken {
    private const val PREFS = "tv_cfg"
    private const val KEY = "token"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.length == TokenAuth.LENGTH }?.let { return it }
        return regenerate(context)
    }

    fun regenerate(context: Context): String {
        val token = TokenAuth.generate()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, token).apply()
        return token
    }

    /** Ekranda okunaklı olsun: ABCD-2345 */
    fun pretty(token: String): String = token.chunked(4).joinToString("-")
}
