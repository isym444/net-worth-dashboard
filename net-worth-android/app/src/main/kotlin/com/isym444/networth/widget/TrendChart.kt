package com.isym444.networth.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.isym444.networth.domain.TrendPoint

/** Draws the net-worth trend as a line with a soft fill; points are placed by date, so uneven snapshot spacing is drawn honestly. Transparent background, so it works in light and dark. */
internal object TrendChart {
    fun render(points: List<TrendPoint>, widthPx: Int, heightPx: Int, density: Float, up: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(2), heightPx.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        if (points.size < 2) return bmp
        val values = points.map { it.total }
        val t0 = points.first().epochMs
        val tSpan = (points.last().epochMs - t0).takeIf { it > 0L } ?: 1L
        val color = if (up) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt()
        val pad = 6f * density
        val w = bmp.width - 2 * pad
        val h = bmp.height - 2 * pad
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        fun x(i: Int) = pad + w * (points[i].epochMs - t0) / tSpan
        fun y(v: Double) = (pad + h * (1 - (v - min) / span)).toFloat()

        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        val fill = Path(line).apply {
            lineTo(x(values.size - 1), bmp.height.toFloat())
            lineTo(x(0), bmp.height.toFloat())
            close()
        }
        val canvas = Canvas(bmp)
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, 0f, 0f, bmp.height.toFloat(), (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.color = color
        })
        canvas.drawCircle(x(values.size - 1), y(values.last()), 3.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return bmp
    }
}
