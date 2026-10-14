package com.berkay.oyunceviri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.View.MeasureSpec

/**
 * Oyun ekranının küçük önizlemesi. Okunacak altyazı bölgesi parlak, geri kalanı karartılmış çizilir;
 * ayar çubuğu oynadıkça bölge canlı değişir.
 */
class RegionPreviewView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    var regionTopPercent: Int = 72
        set(value) { field = value; invalidate() }

    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val border = Paint().apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val placeholder = Paint().apply {
        color = Color.GRAY
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val bg = Paint().apply { color = Color.parseColor("#1E1E1E") }
    private val src = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (200 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText("Oyun başlayınca önizleme burada görünür", width / 2f, height / 2f, placeholder)
            return
        }
        // Görüntüyü ortalayarak sığdır
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        val dst = RectF(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dst, src)

        val regionY = top + dh * regionTopPercent / 100f
        canvas.drawRect(dst.left, dst.top, dst.right, regionY, dim)
        canvas.drawRect(dst.left, regionY, dst.right, dst.bottom, border)
    }
}
