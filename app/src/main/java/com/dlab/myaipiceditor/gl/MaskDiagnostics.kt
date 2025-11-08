package com.dlab.myaipiceditor.gl

import android.graphics.Bitmap
import android.util.Log

/**
 * 🔍 DIAGNOSTIC TOOL: Use this to check if the mask has blur/gradient values
 */
object MaskDiagnostics {

    /**
     * Analyzes a mask bitmap to detect blur/gradient artifacts
     * Call this AFTER drawing to the mask to see if blur was introduced
     */
    fun analyzeMask(mask: Bitmap, tag: String = "MaskDiagnostics") {
        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        // Count different gray levels
        val grayLevels = mutableMapOf<Int, Int>()
        var pureBlack = 0
        var pureWhite = 0
        var grayPixels = 0

        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // For grayscale, r=g=b, so just check r
            when (r) {
                0 -> pureBlack++
                255 -> pureWhite++
                else -> {
                    grayPixels++
                    grayLevels[r] = (grayLevels[r] ?: 0) + 1
                }
            }
        }

        val totalPixels = pixels.size
        val grayPercentage = (grayPixels.toFloat() / totalPixels * 100f)

        Log.d(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(tag, "📊 MASK ANALYSIS REPORT")
        Log.d(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(tag, "Total pixels: $totalPixels")
        Log.d(tag, "Pure BLACK (0,0,0): $pureBlack (${pureBlack.toFloat()/totalPixels*100f}%)")
        Log.d(tag, "Pure WHITE (255,255,255): $pureWhite (${pureWhite.toFloat()/totalPixels*100f}%)")
        Log.d(tag, "GRAY pixels (blur!): $grayPixels ($grayPercentage%)")

        if (grayPixels > 0) {
            Log.e(tag, "⚠️ WARNING: BLUR DETECTED! Found $grayPixels gray pixels!")
            Log.e(tag, "Gray levels found:")
            grayLevels.entries.sortedBy { it.key }.take(10).forEach { (level, count) ->
                Log.e(tag, "  Level $level: $count pixels")
            }
            if (grayLevels.size > 10) {
                Log.e(tag, "  ... and ${grayLevels.size - 10} more gray levels")
            }
        } else {
            Log.i(tag, "✅ PERFECT! No blur detected - only pure black and white pixels!")
        }
        Log.d(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Sample a specific region of the mask to see pixel values
     */
    fun sampleRegion(mask: Bitmap, centerX: Int, centerY: Int, radius: Int = 5, tag: String = "MaskSample") {
        val width = mask.width
        val height = mask.height

        Log.d(tag, "🔬 SAMPLING REGION: center=($centerX, $centerY), radius=$radius")

        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(height - 1)) {
            val line = StringBuilder()
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(width - 1)) {
                val pixel = mask.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF

                // Show as: . = black, # = white, grayscale number for blur
                val char = when {
                    r == 0 -> "."
                    r == 255 -> "#"
                    else -> r.toString().padStart(3, '0')
                }
                line.append("$char ")
            }
            Log.d(tag, "Row ${y - centerY}: $line")
        }
    }
}