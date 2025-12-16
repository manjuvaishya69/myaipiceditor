package com.dlab.myaipiceditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

object ObjectRemoval {
    private const val TAG = "ObjectRemoval"
    private const val SIZE = 512

    suspend fun removeObject(
        context: Context,
        image: Bitmap,
        mask: Bitmap,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {

        Log.d(TAG, "Starting LaMa Inpainting (ONNX)...")
        var session: OrtSession? = null
        var env: OrtEnvironment? = null

        try {
            onProgress?.invoke(0.05f)
            AiModelManager.initialize(context)
            session = AiModelManager.getSession(AiModelManager.ModelType.OBJECT_REMOVAL)
            env = AiModelManager.getEnvironment()

            val resizedImage = Bitmap.createScaledBitmap(image, SIZE, SIZE, true)
            val resizedMask = Bitmap.createScaledBitmap(mask, SIZE, SIZE, true)

            val imgArray = bitmapToCHWFloat(resizedImage)
            val maskArray = bitmapMaskToCHWFloat(resizedMask)

            val imgTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(imgArray),
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
            )
            val maskTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(maskArray),
                longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong())
            )

            val inputs = mapOf("image" to imgTensor, "mask" to maskTensor)
            var outBitmap: Bitmap? = null

            onProgress?.invoke(0.3f)
            val inferenceTime = measureTimeMillis {
                session.run(inputs).use { results ->
                    val output = results[0].value
                    val outCHW: FloatArray = when (output) {
                        is Array<*> -> flattenOnnxOutput(output)
                        is FloatArray -> output
                        else -> throw IllegalArgumentException("Unexpected output type: ${output?.javaClass}")
                    }
                    // Normalize output (since model returns 0-255)
                    outBitmap = chwFloatToBitmap(outCHW, SIZE, SIZE, divideBy255 = true)
                }
            }

            imgTensor.close()
            maskTensor.close()
            onProgress?.invoke(0.7f)

            Log.d(TAG, "Inference done in ${inferenceTime}ms")

            val outResized = Bitmap.createScaledBitmap(outBitmap!!, image.width, image.height, true)
            val blended = alphaBlendWithMask(image, outResized, mask)

            onProgress?.invoke(1.0f)
            Log.d(TAG, "LAMA COMPLETE ✅")
            blended

        } catch (e: Exception) {
            Log.e(TAG, "LaMa failed..: ${e.message}", e)
            throw e
        }
    }

    // --- Utility functions ---
    private fun bitmapToCHWFloat(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = FloatArray(3 * w * h)
        var iR = 0
        var iG = w * h
        var iB = 2 * w * h

        for (p in pixels) {
            out[iR++] = ((p shr 16) and 0xFF) / 255f
            out[iG++] = ((p shr 8) and 0xFF) / 255f
            out[iB++] = (p and 0xFF) / 255f
        }
        return out
    }

    private fun bitmapMaskToCHWFloat(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in pixels.indices) {
            val v = (pixels[i] shr 16) and 0xFF
            out[i] = if (v > 0) 1f else 0f
        }
        return out
    }

    private fun chwFloatToBitmap(chw: FloatArray, w: Int, h: Int, divideBy255: Boolean = false): Bitmap {
        val pixels = IntArray(w * h)
        val area = w * h
        for (i in 0 until area) {
            var r = chw[i]
            var g = chw[i + area]
            var b = chw[i + 2 * area]
            if (divideBy255) {
                r /= 255f
                g /= 255f
                b /= 255f
            }
            val ir = (clamp01(r) * 255f + 0.5f).toInt()
            val ig = (clamp01(g) * 255f + 0.5f).toInt()
            val ib = (clamp01(b) * 255f + 0.5f).toInt()
            pixels[i] = Color.rgb(ir, ig, ib)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp01(v: Float) = max(0f, min(1f, v))

    private fun flattenOnnxOutput(output: Array<*>): FloatArray {
        val arr1 = output[0] as Array<*>
        val arr2 = arr1[0] as Array<*>
        val h = arr2.size
        val w = (arr2[0] as FloatArray).size
        val out = FloatArray(3 * h * w)
        var iR = 0
        var iG = h * w
        var iB = 2 * h * w
        for (y in 0 until h) {
            val rRow = (arr1[0] as Array<*>)[y] as FloatArray
            val gRow = (arr1[1] as Array<*>)[y] as FloatArray
            val bRow = (arr1[2] as Array<*>)[y] as FloatArray
            for (x in 0 until w) {
                out[iR++] = rRow[x]
                out[iG++] = gRow[x]
                out[iB++] = bRow[x]
            }
        }
        return out
    }

    private fun alphaBlendWithMask(bg: Bitmap, fg: Bitmap, mask: Bitmap): Bitmap {
        val w = bg.width
        val h = bg.height
        val bgPix = IntArray(w * h)
        val fgPix = IntArray(w * h)
        val mkPix = IntArray(w * h)

        bg.getPixels(bgPix, 0, w, 0, 0, w, h)
        fg.getPixels(fgPix, 0, w, 0, 0, w, h)
        mask.getPixels(mkPix, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        for (i in out.indices) {
            val alpha = ((mkPix[i] shr 16) and 0xFF) / 255f
            val br = (bgPix[i] shr 16) and 0xFF
            val bgc = (bgPix[i] shr 8) and 0xFF
            val bb = bgPix[i] and 0xFF
            val fr = (fgPix[i] shr 16) and 0xFF
            val fgC = (fgPix[i] shr 8) and 0xFF
            val fb = fgPix[i] and 0xFF
            val r = (fr * alpha + br * (1 - alpha)).toInt()
            val g = (fgC * alpha + bgc * (1 - alpha)).toInt()
            val b = (fb * alpha + bb * (1 - alpha)).toInt()
            out[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}