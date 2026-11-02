package com.procam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Colour filters expressed as 4x5 colour matrices.
 * The same matrix drives the live preview (TextureView layer paint) and the saved
 * result, so what you see is what you get. Filters are never applied to the RAW (DNG)
 * file or to the saved "original" PNG - they only produce an extra "_filtered" PNG.
 */
enum class ProFilter(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    FADE("Fade"),
    SEPIA("Sepia"),
    MONO("Mono"),
    NOIR("Noir");

    /** Matrix blended with identity by [intensity] (0f..1f). */
    fun matrix(intensity: Float): ColorMatrix {
        val target = target().array
        val identity = ColorMatrix().array
        val t = intensity.coerceIn(0f, 1f)
        val out = FloatArray(20) { identity[it] + (target[it] - identity[it]) * t }
        return ColorMatrix(out)
    }

    private fun target(): ColorMatrix = when (this) {
        NONE -> ColorMatrix()
        VIVID -> chain(saturation(1.5f), contrast(1.1f))
        WARM -> scaleRgb(1.08f, 1.0f, 0.90f)
        COOL -> scaleRgb(0.92f, 1.0f, 1.10f)
        FADE -> chain(saturation(0.85f), contrast(0.85f), offset(18f))
        SEPIA -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        MONO -> saturation(0f)
        NOIR -> chain(saturation(0f), contrast(1.4f))
    }

    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun contrast(c: Float): ColorMatrix {
        val shift = 128f * (1f - c)
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, shift,
                0f, c, 0f, 0f, shift,
                0f, 0f, c, 0f, shift,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun offset(o: Float) = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, o,
            0f, 1f, 0f, 0f, o,
            0f, 0f, 1f, 0f, o,
            0f, 0f, 0f, 1f, 0f
        )
    )

    private fun scaleRgb(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

    private fun chain(vararg ms: ColorMatrix): ColorMatrix {
        val result = ColorMatrix()
        for (m in ms) result.postConcat(m)
        return result
    }
}

object FilterEngine {
    /** Paint for the live preview (TextureView.setLayerPaint). */
    fun previewPaint(filter: ProFilter, intensity: Float): Paint? {
        if (filter == ProFilter.NONE || intensity <= 0f) return null
        return Paint().apply { colorFilter = ColorMatrixColorFilter(filter.matrix(intensity)) }
    }

    /** Returns a new ARGB_8888 bitmap; the source is left untouched (non-destructive). */
    fun apply(src: Bitmap, filter: ProFilter, intensity: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(filter.matrix(intensity))
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
