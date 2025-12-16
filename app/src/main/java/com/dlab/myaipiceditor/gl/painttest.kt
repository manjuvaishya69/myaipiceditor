package com.dlab.myaipiceditor.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log

/**
 * 🧪 TEST: Create a simple test to verify if Android Canvas is introducing blur
 */
object PaintTest {

    fun runHardEdgeTest(): Bitmap {
        Log.d("PaintTest", "🧪 Running hard-edge paint test...")

        // Create a small 100x100 test bitmap
        val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        // Fill with white
        val pixels = IntArray(100 * 100) { Color.WHITE }
        testBitmap.setPixels(pixels, 0, 100, 0, 0, 100, 100)

        val canvas = Canvas(testBitmap)

        // Draw a black circle with EXACTLY the same settings as your eraser
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 30f
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
            isAntiAlias = false
            isDither = false
            isFilterBitmap = false
            maskFilter = null
        }

        Log.d("PaintTest", "🎨 Drawing test circle with settings:")
        Log.d("PaintTest", "  - xfermode: SRC")
        Log.d("PaintTest", "  - color: BLACK")
        Log.d("PaintTest", "  - strokeWidth: 30")
        Log.d("PaintTest", "  - strokeCap: SQUARE")
        Log.d("PaintTest", "  - strokeJoin: MITER")
        Log.d("PaintTest", "  - isAntiAlias: false")

        // Draw circle at center
        canvas.drawCircle(50f, 50f, 15f, paint)

        // Analyze the result
        MaskDiagnostics.analyzeMask(testBitmap, "PAINT-TEST")
        MaskDiagnostics.sampleRegion(testBitmap, 50, 50, 20, "PAINT-TEST-SAMPLE")

        return testBitmap
    }

    /**
     * Test if the issue is hardware acceleration related
     */
    fun testCanvasTypes(): String {
        val hwBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val swBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        // Test with hardware-accelerated canvas
        val hwCanvas = Canvas(hwBitmap)
        val isHWAccelerated = hwCanvas.isHardwareAccelerated

        Log.d("PaintTest", "📱 Canvas hardware acceleration: $isHWAccelerated")

        return if (isHWAccelerated) {
            "HARDWARE_ACCELERATED"
        } else {
            "SOFTWARE"
        }
    }
}