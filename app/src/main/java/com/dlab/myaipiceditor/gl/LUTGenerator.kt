package com.dlab.myaipiceditor.gl

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import java.io.File

object LUTGenerator {

    /**
     * Creates an RGB LUT from normalized control points (0..1 range)
     * ✅ FIXED: Proper handling of master curve application
     */
    fun createRGBLUTNormalized(
        pointsMaster: List<Offset>,
        pointsRed: List<Offset>,
        pointsGreen: List<Offset>,
        pointsBlue: List<Offset>
    ): FloatArray {
        val masterLUT = interpolateCurveNormalized(pointsMaster)
        val redLUT = interpolateCurveNormalized(pointsRed)
        val greenLUT = interpolateCurveNormalized(pointsGreen)
        val blueLUT = interpolateCurveNormalized(pointsBlue)

        val lut = FloatArray(256 * 3)
        for (i in 0 until 256) {
            // ✅ FIXED: Apply master curve to the OUTPUT of each channel, not multiply
            // First apply the individual channel curve, then apply master curve to that result
            val rValue = redLUT[i]
            val gValue = greenLUT[i]
            val bValue = blueLUT[i]

            // Convert back to index (0-255) and lookup in master
            val rIndex = (rValue * 255f).toInt().coerceIn(0, 255)
            val gIndex = (gValue * 255f).toInt().coerceIn(0, 255)
            val bIndex = (bValue * 255f).toInt().coerceIn(0, 255)

            lut[i * 3 + 0] = masterLUT[rIndex].coerceIn(0f, 1f)
            lut[i * 3 + 1] = masterLUT[gIndex].coerceIn(0f, 1f)
            lut[i * 3 + 2] = masterLUT[bIndex].coerceIn(0f, 1f)
        }
        return lut
    }

    /**
     * Creates an RGB LUT (256x3 float array) from given control points for each channel.
     */
    fun createRGBLUT(
        pointsMaster: List<Offset>,
        pointsRed: List<Offset>,
        pointsGreen: List<Offset>,
        pointsBlue: List<Offset>,
        width: Int,
        height: Int
    ): FloatArray {
        val masterLUT = interpolateCurve(pointsMaster, width, height)
        val redLUT = interpolateCurve(pointsRed, width, height)
        val greenLUT = interpolateCurve(pointsGreen, width, height)
        val blueLUT = interpolateCurve(pointsBlue, width, height)

        val lut = FloatArray(256 * 3)
        for (i in 0 until 256) {
            // Apply channel curves first, then master
            val rValue = redLUT[i]
            val gValue = greenLUT[i]
            val bValue = blueLUT[i]

            val rIndex = (rValue * 255f).toInt().coerceIn(0, 255)
            val gIndex = (gValue * 255f).toInt().coerceIn(0, 255)
            val bIndex = (bValue * 255f).toInt().coerceIn(0, 255)

            lut[i * 3 + 0] = masterLUT[rIndex].coerceIn(0f, 1f)
            lut[i * 3 + 1] = masterLUT[gIndex].coerceIn(0f, 1f)
            lut[i * 3 + 2] = masterLUT[bIndex].coerceIn(0f, 1f)
        }
        return lut
    }

    /**
     * Apply LUT to a bitmap and return a new bitmap
     */
    fun applyLUTToBitmap(bitmap: Bitmap, lut: FloatArray): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val bitmapConfig = bitmap.config ?: Bitmap.Config.ARGB_8888
        val result = Bitmap.createBitmap(width, height, bitmapConfig)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val a = (pixel shr 24) and 0xFF

            val newR = (lut[r * 3] * 255f).toInt().coerceIn(0, 255)
            val newG = (lut[g * 3 + 1] * 255f).toInt().coerceIn(0, 255)
            val newB = (lut[b * 3 + 2] * 255f).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Converts the 256x3 LUT into a 256x1 Bitmap for GPU texture upload.
     */
    fun createBitmapFromRGB(lut: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        for (i in 0 until 256) {
            val r = (lut[i * 3] * 255f).toInt().coerceIn(0, 255)
            val g = (lut[i * 3 + 1] * 255f).toInt().coerceIn(0, 255)
            val b = (lut[i * 3 + 2] * 255f).toInt().coerceIn(0, 255)
            bmp.setPixel(i, 0, Color.rgb(r, g, b))
        }
        return bmp
    }

    /**
     * Exports LUT to a readable CSV (optional).
     */
    fun exportLUTToFile(lut: FloatArray, file: File) {
        val text = buildString {
            for (i in lut.indices step 3) {
                append("${lut[i]},${lut[i + 1]},${lut[i + 2]}\n")
            }
        }
        file.writeText(text)
    }

    /**
     * Interpolate curve from normalized points (0..1 range)
     */
    private fun interpolateCurveNormalized(points: List<Offset>): FloatArray {
        if (points.isEmpty()) return FloatArray(256) { it / 255f }

        val sorted = points.sortedBy { it.x }
        val xs = sorted.map { it.x.coerceIn(0f, 1f) }
        val ys = sorted.map { it.y.coerceIn(0f, 1f) }

        val output = FloatArray(256)
        for (i in 0 until 256) {
            val t = i / 255f
            output[i] = cubicSpline(xs, ys, t)
        }
        return output
    }

    /**
     * Generates smooth curve values (0..1 range) from given control points using cubic interpolation.
     */
    private fun interpolateCurve(points: List<Offset>, width: Int, height: Int): FloatArray {
        if (points.isEmpty()) return FloatArray(256) { it / 255f }

        // Normalize control points to [0,1] range
        val sorted = points.sortedBy { it.x }
        val xs = sorted.map { (it.x / width).coerceIn(0f, 1f) }
        val ys = sorted.map { 1f - (it.y / height).coerceIn(0f, 1f) }

        val output = FloatArray(256)
        for (i in 0 until 256) {
            val t = i / 255f
            output[i] = cubicSpline(xs, ys, t)
        }
        return output
    }

    /**
     * Cubic spline interpolation between multiple control points.
     */
    private fun cubicSpline(xs: List<Float>, ys: List<Float>, t: Float): Float {
        if (xs.size < 2) return ys.firstOrNull() ?: t

        // ✅ --- START FIX ---
        // Handle the simple linear case for exactly 2 points
        if (xs.size == 2) {
            val x1 = xs[0]
            val x2 = xs[1]
            val y1 = ys[0]
            val y2 = ys[1]

            // Avoid division by zero if points are identical
            val denominator = (x2 - x1)
            if (denominator == 0f) return y1

            // Linear interpolation: y = y1 + (t - x1) * (y2 - y1) / (x2 - x1)
            val mu = (t - x1) / denominator
            return (y1 + mu * (y2 - y1)).coerceIn(0f, 1f)
        }
        // ✅ --- END FIX ---

        for (i in 0 until xs.lastIndex) {
            if (t in xs[i]..xs[i + 1]) {
                val x0 = if (i > 0) xs[i - 1] else xs[i]
                val x1 = xs[i]
                val x2 = xs[i + 1]
                val x3 = if (i + 2 < xs.size) xs[i + 2] else xs[i + 1]

                val y0 = if (i > 0) ys[i - 1] else ys[i]
                val y1 = ys[i]
                val y2 = ys[i + 1]
                val y3 = if (i + 2 < ys.size) ys[i + 2] else ys[i + 1]

                // ✅ FIXED: Added check for 0f denominator
                val mu = (t - x1) / (x2 - x1).let { if (it == 0f) 1f else it }
                return catmullRom(y0, y1, y2, y3, mu)
            }
        }
        return ys.last()
    }

    private fun catmullRom(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
        val mu2 = mu * mu
        val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val a1 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val a2 = -0.5f * y0 + 0.5f * y2
        val a3 = y1
        return (a0 * mu * mu2 + a1 * mu2 + a2 * mu + a3).coerceIn(0f, 1f)
    }
}