package com.berkay.oyunceviri

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.berkay.oyunceviri.core.SubtitleSink

/**
 * Telefon ekranındaki çeviri kutusu. OCR bölgesinin hemen ÜSTÜNE konur;
 * böylece kendi çevirimizi yeniden okumayız. Ana thread'de kullanılır.
 */
class SubtitleOverlay(context: Context) : SubtitleSink {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(32, 16, 32, 16)
        setBackgroundColor(0xB3000000.toInt())
        setShadowLayer(3f, 1f, 1f, 0xCC000000.toInt()) // hafif gölge: okunurluk için
        visibility = View.GONE
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM }

    private var attached = false

    init {
        wm.addView(view, params)
        attached = true
    }

    /** Kutu, okunan bölgenin (ekranın alt %[100 - regionTopPercent]'i) hemen üstünde durur. */
    @Suppress("DEPRECATION")
    fun setRegionTop(regionTopPercent: Int) {
        if (!attached) return
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        params.y = (dm.heightPixels * (100 - regionTopPercent) / 100f).toInt()
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    override fun show(text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    override fun clear() {
        view.visibility = View.GONE
    }

    fun close() {
        if (!attached) return
        attached = false
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }
}
