package com.dlab.myaipiceditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * SmartMaskSnap – Final Fixed Version
 *
 * Includes:
 *  ✔ Horizontal gap closing
 *  ✔ Vertical gap closing
 *  ✔ Diagonal gap closing
 *  ✔ Hole-fill flood fill
 *  ✔ Edge smoothing
 *  ✔ Noise removal
 *
 * Goal: Perfect mask refinement from rough strokes, under 40ms.
 */
object SmartMaskSnap {

    private const val TAG = "SmartMaskSnap"

    suspend fun snapToObject(
        context: Context,
        bitmap: Bitmap,
        roughMask: Bitmap
    ): Bitmap = withContext(Dispatchers.Default) {

        val t = System.currentTimeMillis()
        val width = bitmap.width
        val height = bitmap.height

        val pix = IntArray(width * height)
        roughMask.getPixels(pix, 0, width, 0, 0, width, height)

        val binary = IntArray(width * height) { i ->
            if ((pix[i] shr 16) and 0xFF > 127) 1 else 0
        }

        // ============================================================
        // PIPELINE
        // ============================================================

        // 1) Slight close (smooth strokes)
        var refined = morphologicalClose(binary, width, height, 2)

        // 2) CLOSE GAPS (horizontal + vertical + both diagonals)
        refined = closeContourGaps(refined, width, height, 40)

        // 3) FILL INSIDE HOLES
        refined = fillHoles(refined, width, height)

        // 4) Remove noise
        refined = removeSmallRegions(refined, width, height, 50)

        // 5) Final smoothing
        refined = dilate(refined, width, height, 1)
        refined = erode(refined, width, height, 1)

        // Convert to Bitmap
        val outPix = IntArray(width * height) { i ->
            if (refined[i] == 1) Color.WHITE else Color.BLACK
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPix, 0, width, 0, 0, width, height)

        Log.d(TAG, "SmartMaskSnap completed in ${System.currentTimeMillis() - t}ms")
        return@withContext out
    }

    // =====================================================================
    // GAP CLOSING — horizontal, vertical, diagonal (\), diagonal (/)
    // =====================================================================
    private fun closeContourGaps(mask: IntArray, width: Int, height: Int, maxGap: Int): IntArray {
        val out = mask.copyOf()

        // Horizontal scan
        for (y in 0 until height) {
            var lastX = -1
            for (x in 0 until width) {
                val idx = y * width + x
                if (mask[idx] == 1) {
                    if (lastX != -1) {
                        val gap = x - lastX
                        if (gap in 2..maxGap) {
                            for (gx in lastX..x) out[y * width + gx] = 1
                        }
                    }
                    lastX = x
                }
            }
        }

        // Vertical scan
        for (x in 0 until width) {
            var lastY = -1
            for (y in 0 until height) {
                val idx = y * width + x
                if (mask[idx] == 1) {
                    if (lastY != -1) {
                        val gap = y - lastY
                        if (gap in 2..maxGap) {
                            for (gy in lastY..y) out[gy * width + x] = 1
                        }
                    }
                    lastY = y
                }
            }
        }

        // Diagonal scan (\ direction)
        for (start in 0 until width + height) {
            var last = -1
            var yy = start
            var xx = 0

            while (yy >= 0) {
                if (yy < height && xx < width) {
                    val idx = yy * width + xx
                    if (mask[idx] == 1) {
                        if (last != -1) {
                            val gap = yy - last
                            if (gap in 2..maxGap) {
                                for (gy in last..yy) {
                                    val gx = xx - (yy - gy)
                                    if (gx in 0 until width)
                                        out[gy * width + gx] = 1
                                }
                            }
                        }
                        last = yy
                    }
                }
                yy--
                xx++
            }
        }

        // Diagonal scan (/ direction)
        for (start in -height until width) {
            var last = -1
            var y = 0
            var x = start

            while (y < height) {
                if (x in 0 until width) {
                    val idx = y * width + x
                    if (mask[idx] == 1) {
                        if (last != -1) {
                            val gap = y - last
                            if (gap in 2..maxGap) {
                                for (gy in last..y) {
                                    val gx = start + (gy - last)
                                    if (gx in 0 until width)
                                        out[gy * width + gx] = 1
                                }
                            }
                        }
                        last = y
                    }
                }
                y++
            }
        }

        return out
    }

    // =====================================================================
    // HOLE FILLING USING FLOOD FILL FROM BORDERS
    // =====================================================================
    private fun fillHoles(mask: IntArray, width: Int, height: Int): IntArray {
        val size = width * height
        val visited = BooleanArray(size)
        val q = ArrayDeque<Int>()

        fun addIfZero(x: Int, y: Int) {
            val idx = y * width + x
            if (mask[idx] == 0 && !visited[idx]) {
                visited[idx] = true
                q.add(idx)
            }
        }

        // Add borders
        for (x in 0 until width) {
            addIfZero(x, 0)
            addIfZero(x, height - 1)
        }
        for (y in 0 until height) {
            addIfZero(0, y)
            addIfZero(width - 1, y)
        }

        val neighbors = intArrayOf(-1, 1, -width, width)

        // Flood fill from borders
        while (q.isNotEmpty()) {
            val idx = q.removeFirst()
            for (n in neighbors) {
                val next = idx + n
                if (next in 0 until size && !visited[next] && mask[next] == 0) {
                    visited[next] = true
                    q.add(next)
                }
            }
        }

        // Everything NOT visited is inside → fill it
        val out = mask.copyOf()
        for (i in 0 until size) {
            if (!visited[i]) out[i] = 1
        }

        return out
    }

    // =====================================================================
    // MORPHOLOGICAL OPS
    // =====================================================================
    private fun morphologicalClose(mask: IntArray, width: Int, height: Int, iterations: Int): IntArray {
        var m = mask
        repeat(iterations) {
            m = dilate(m, width, height, 1)
        }
        repeat(iterations) {
            m = erode(m, width, height, 1)
        }
        return m
    }

    private fun dilate(mask: IntArray, width: Int, height: Int, iterations: Int): IntArray {
        var out = mask.copyOf()
        repeat(iterations) {
            val tmp = out.copyOf()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    if (
                        out[i - 1] == 1 || out[i + 1] == 1 ||
                        out[i - width] == 1 || out[i + width] == 1 ||
                        out[i - width - 1] == 1 || out[i - width + 1] == 1 ||
                        out[i + width - 1] == 1 || out[i + width + 1] == 1
                    ) tmp[i] = 1
                }
            }
            out = tmp
        }
        return out
    }

    private fun erode(mask: IntArray, width: Int, height: Int, iterations: Int): IntArray {
        var out = mask.copyOf()
        repeat(iterations) {
            val tmp = out.copyOf()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    if (out[i] == 1) {
                        if (
                            out[i - 1] == 0 || out[i + 1] == 0 ||
                            out[i - width] == 0 || out[i + width] == 0 ||
                            out[i - width - 1] == 0 || out[i - width + 1] == 0 ||
                            out[i + width - 1] == 0 || out[i + width + 1] == 0
                        ) tmp[i] = 0
                    }
                }
            }
            out = tmp
        }
        return out
    }

    // =====================================================================
    // REMOVE SMALL DOTS
    // =====================================================================
    private fun removeSmallRegions(mask: IntArray, width: Int, height: Int, minSize: Int): IntArray {
        val out = mask.copyOf()
        val visited = BooleanArray(out.size)

        for (i in out.indices) {
            if (out[i] == 1 && !visited[i]) {
                val region =
                    mutableListOf<Int>()
                val q = ArrayDeque<Int>()
                q.add(i)
                visited[i] = true

                while (q.isNotEmpty()) {
                    val idx =
                        q.removeFirst()
                    region.add(idx)

                    val x = idx % width
                    val y = idx / width

                    val neigh = arrayOf(idx - 1, idx + 1, idx - width, idx + width)
                    for (n in neigh) {
                        if (n in 0 until out.size && !visited[n] && out[n] == 1) {
                            val nx = n % width
                            val ny = n / width
                            if (abs(nx - x) <= 1 && abs(ny - y) <= 1) {
                                visited[n] = true
                                q.add(n)
                            }
                        }
                    }
                }

                if (region.size < minSize) {
                    region.forEach { out[it] = 0 }
                }
            }
        }

        return out
    }
}
