package com.dlab.myaipiceditor

import com.dlab.myaipiceditor.ui.PathTextRenderer
import androidx.compose.ui.geometry.Offset
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import com.dlab.myaipiceditor.data.AdjustmentValues
import com.dlab.myaipiceditor.data.TextStyle
import kotlin.math.max
import kotlin.math.min

object PhotoEditorUtils {

    fun crop(input: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val safeX = maxOf(0, minOf(x, input.width - 1))
        val safeY = maxOf(0, minOf(y, input.height - 1))
        val safeWidth = minOf(width, input.width - safeX)
        val safeHeight = minOf(height, input.height - safeY)
        return Bitmap.createBitmap(input, safeX, safeY, safeWidth, safeHeight)
    }

    fun resize(input: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(input, newWidth, newHeight, true)
    }

    fun rotate(input: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }

    fun addText(input: Bitmap, text: String, x: Float, y: Float, font: Typeface): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            this.typeface = font
            this.textSize = minOf(input.width, input.height) * 0.08f
            this.isAntiAlias = true
            this.color = android.graphics.Color.WHITE
            this.setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        }
        canvas.drawText(text, x, y, paint)
        return output
    }

    // Replace the addStyledText function in PhotoEditorUtils.kt

    fun addStyledText(
        input: Bitmap,
        text: String,
        x: Float,
        y: Float,
        style: TextStyle,
        density: Float = 2f,
        rotation: Float = 0f,
        context: android.content.Context? = null  // ✅ Add this parameter
    ): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        // Use PathTextRenderer for professional stroke rendering
        PathTextRenderer.renderText(
            canvas = canvas,
            text = text,
            x = x,
            y = y,
            fontSize = style.fontSize * density,
            fontFamily = style.fontFamily,
            isBold = style.isBold,
            textColor = style.color,
            opacity = style.opacity,
            strokeWidth = style.strokeWidth,
            strokeColor = style.strokeColor,
            scaleX = style.scaleX,
            scaleY = style.scaleY,
            rotation = rotation,
            highlightColor = style.highlightColor,
            highlightPadding = 10f * density,
            letterSpacing = style.letterSpacing,
            shadowRadius = 0f,
            shadowColor = Color.Transparent,
            shadowOffset = Offset.Zero,
            density = density,
            context = context  // ✅ Pass context to renderer
        )

        return output
    }

    fun applyAdjustments(input: Bitmap, adjustments: AdjustmentValues): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val colorMatrix = ColorMatrix()

        // Apply brightness
        if (adjustments.brightness != 0f) {
            val brightnessMatrix = ColorMatrix().apply {
                val value = adjustments.brightness * 2.55f
                set(floatArrayOf(
                    1f, 0f, 0f, 0f, value,
                    0f, 1f, 0f, 0f, value,
                    0f, 0f, 1f, 0f, value,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            colorMatrix.postConcat(brightnessMatrix)
        }

        // Apply contrast
        if (adjustments.contrast != 0f) {
            val contrastValue = (adjustments.contrast + 100f) / 100f
            val offset = (1f - contrastValue) * 128f
            val contrastMatrix = ColorMatrix().apply {
                set(floatArrayOf(
                    contrastValue, 0f, 0f, 0f, offset,
                    0f, contrastValue, 0f, 0f, offset,
                    0f, 0f, contrastValue, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            colorMatrix.postConcat(contrastMatrix)
        }

        // Apply saturation
        if (adjustments.saturation != 0f) {
            val satValue = (adjustments.saturation + 100f) / 100f
            val saturationMatrix = ColorMatrix()
            saturationMatrix.setSaturation(satValue)
            colorMatrix.postConcat(saturationMatrix)
        }

        // Apply warmth (temperature)
        if (adjustments.warmth != 0f) {
            val warmthValue = adjustments.warmth / 100f
            val warmthMatrix = ColorMatrix().apply {
                set(floatArrayOf(
                    1f + warmthValue * 0.2f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f - warmthValue * 0.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            colorMatrix.postConcat(warmthMatrix)
        }

        // Apply tint
        if (adjustments.tint != 0f) {
            val tintValue = adjustments.tint / 100f
            val tintMatrix = ColorMatrix().apply {
                set(floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f + tintValue * 0.2f, 0f, 0f, 0f,
                    0f, 0f, 1f - tintValue * 0.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            colorMatrix.postConcat(tintMatrix)
        }

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(input, 0f, 0f, paint)

        // Apply highlights and shadows
        var result = output
        if (adjustments.highlights != 0f || adjustments.shadows != 0f) {
            result = applyHighlightsShadows(result, adjustments.highlights, adjustments.shadows)
        }

        // Apply sharpness
        if (adjustments.sharpness != 0f) {
            result = applySharpness(result, adjustments.sharpness)
        }

        return result
    }

    private fun applyHighlightsShadows(input: Bitmap, highlights: Float, shadows: Float): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

        val highlightFactor = highlights / 100f
        val shadowFactor = shadows / 100f

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            val a = (pixel shr 24) and 0xff

            val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255f

            var newR = r
            var newG = g
            var newB = b

            if (luminance > 0.5f && highlightFactor != 0f) {
                val factor = (luminance - 0.5f) * 2f
                val adjustment = highlightFactor * factor * 50f
                newR = (r + adjustment).toInt().coerceIn(0, 255)
                newG = (g + adjustment).toInt().coerceIn(0, 255)
                newB = (b + adjustment).toInt().coerceIn(0, 255)
            } else if (luminance <= 0.5f && shadowFactor != 0f) {
                val factor = (0.5f - luminance) * 2f
                val adjustment = shadowFactor * factor * 50f
                newR = (r + adjustment).toInt().coerceIn(0, 255)
                newG = (g + adjustment).toInt().coerceIn(0, 255)
                newB = (b + adjustment).toInt().coerceIn(0, 255)
            }

            pixels[i] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        output.setPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return output
    }

    private fun applySharpness(input: Bitmap, sharpness: Float): Bitmap {
        if (sharpness == 0f) return input

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)

        val amount = sharpness / 100f
        val kernel = floatArrayOf(
            0f, -amount, 0f,
            -amount, 1f + 4f * amount, -amount,
            0f, -amount, 0f
        )

        val tempPixels = pixels.clone()

        for (y in 1 until input.height - 1) {
            for (x in 1 until input.width - 1) {
                var r = 0f
                var g = 0f
                var b = 0f

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val idx = (y + ky) * input.width + (x + kx)
                        val pixel = tempPixels[idx]
                        val kernelIdx = (ky + 1) * 3 + (kx + 1)
                        val kernelValue = kernel[kernelIdx]

                        r += ((pixel shr 16) and 0xff) * kernelValue
                        g += ((pixel shr 8) and 0xff) * kernelValue
                        b += (pixel and 0xff) * kernelValue
                    }
                }

                val idx = y * input.width + x
                val a = (tempPixels[idx] shr 24) and 0xff
                pixels[idx] = (a shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
            }
        }

        output.setPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return output
    }
}