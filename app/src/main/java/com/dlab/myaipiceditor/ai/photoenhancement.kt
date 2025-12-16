package com.dlab.myaipiceditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

object PhotoEnhancement {
    private const val TAG = "PhotoEnhancement"
    // --- UPDATED CONSTANTS FOR Real-ESRGAN x4 ---
    private const val TILE_SIZE = 128 // Real-ESRGAN input size is 128x128
    private const val UPSCALE_FACTOR = 4 // Real-ESRGAN_x4plus_float is a 4x model
    private const val INPUT_TENSOR_SIZE = 128 // Explicitly defined input size
    private const val PADDING_SIZE = 10 // Real-ESRGAN tile padding for border artifacts

    suspend fun enhance(
        context: Context,
        input: Bitmap,
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        var scaledInput: Bitmap? = null // This will be our safe, mutable copy
        var result: Bitmap? = null
        var finalResult: Bitmap? = null
        var interpreter: Interpreter? = null

        try {
            Log.d(TAG, "Starting photo enhancement (Real-ESRGAN x4): ${input.width}x${input.height}")
            onProgress(0.05f)

            // --- TFLite: Get the Interpreter instead of OrtSession ---
            interpreter = AiModelManager.getInterpreter(AiModelManager.ModelType.IMAGE_UPSCALER)

            onProgress(0.1f)

            // --- FIX 1: Ensure we work on a copy, not the original bitmap ---
            val processedBitmap = resizeForProcessing(input)
            // If resizeForProcessing returned the original, make a mutable copy.
            // Otherwise, use the new resized bitmap.
            scaledInput = if (processedBitmap === input) {
                input.copy(input.config ?: Bitmap.Config.ARGB_8888, true)
            } else {
                processedBitmap
            }
            // --- END FIX 1 ---

            Log.d(TAG, "Processing size: ${scaledInput.width}x${scaledInput.height}")

            val outputWidth = scaledInput.width * UPSCALE_FACTOR
            val outputHeight = scaledInput.height * UPSCALE_FACTOR
            result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)

            // Calculate tile counts based on INPUT_TENSOR_SIZE and PADDING_SIZE
            val effectiveTileSize = INPUT_TENSOR_SIZE - 2 * PADDING_SIZE
            val tilesX = ceil(scaledInput.width.toFloat() / effectiveTileSize).toInt()
            val tilesY = ceil(scaledInput.height.toFloat() / effectiveTileSize).toInt()
            val totalTiles = tilesX * tilesY

            Log.d(TAG, "Processing ${totalTiles} tiles (${tilesX}x${tilesY}) with ${PADDING_SIZE}px overlap")
            onProgress(0.15f)

            var processedTiles = 0

            for (tileY in 0 until tilesY) {
                for (tileX in 0 until tilesX) {
                    // Process tile using the TFLite Interpreter
                    processTile(
                        scaledInput, result, interpreter,
                        tileX, tileY
                    )

                    processedTiles++
                    val progress = 0.15f + (processedTiles.toFloat() / totalTiles) * 0.70f
                    onProgress(progress)

                    // Gentle GC every few tiles
                    if (processedTiles % 3 == 0) {
                        System.gc()
                        delay(30)
                    }
                }
            }

            // Since scaledInput is now guaranteed to be a copy or a new bitmap,
            // it's safe to recycle it.
            if (scaledInput != input) {
                scaledInput.recycle()
                scaledInput = null
            }

            onProgress(0.90f)

            // Resize to original aspect ratio
            finalResult = resizeToOriginalAspect(result, input.width, input.height)
            if (finalResult != result) {
                result.recycle()
                result = null
            }

            onProgress(0.95f)

            // Apply final enhancement boost (optional)
            val improvedResult = applyEnhancementBoost(finalResult)
            if (improvedResult != finalResult) {
                finalResult.recycle()
                finalResult = null
            }

            onProgress(1.0f)
            Log.d(TAG, "Enhancement complete")

            improvedResult
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed: ${e.message}", e)
            // Clean up resources on error
            scaledInput?.recycle()
            result?.recycle()
            finalResult?.recycle()
            throw e
        }
    }

    private fun processTile(
        scaledInput: Bitmap,
        result: Bitmap,
        interpreter: Interpreter,
        tileX: Int,
        tileY: Int
    ) {
        var paddedTile: Bitmap? = null
        var enhancedTile: Bitmap? = null

        try {
            val effectiveTileSize = INPUT_TENSOR_SIZE - 2 * PADDING_SIZE

            // --- FIX 2: Simplified and more robust tile boundary calculation ---
            val startX = tileX * effectiveTileSize
            val startY = tileY * effectiveTileSize

            // 1. Calculate source region (with padding/overlap)
            val tileSrcX = (startX - PADDING_SIZE).coerceAtLeast(0)
            val tileSrcY = (startY - PADDING_SIZE).coerceAtLeast(0)

            // Calculate width/height from the top-left corner, ensuring we don't exceed bitmap bounds.
            val srcWidth = min(INPUT_TENSOR_SIZE, scaledInput.width - tileSrcX)
            val srcHeight = min(INPUT_TENSOR_SIZE, scaledInput.height - tileSrcY)

            if (srcWidth <= 0 || srcHeight <= 0) {
                Log.w(TAG, "Skipping invalid source tile at ($tileX, $tileY)")
                return
            }
            // --- END FIX 2 ---

            // 2. Extract and Pad the tile to the exact INPUT_TENSOR_SIZE
            val extractedTile = Bitmap.createBitmap(scaledInput, tileSrcX, tileSrcY, srcWidth, srcHeight)
            paddedTile = padTileToInputSize(extractedTile)
            extractedTile.recycle() // Clean up intermediate bitmap

            // 3. Preprocess and Run TFLite Inference
            val inputBuffer = preprocessImage(paddedTile)
            // Output tensor is [1, 512, 512, 3] for 4x (128*4)
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, INPUT_TENSOR_SIZE * UPSCALE_FACTOR, INPUT_TENSOR_SIZE * UPSCALE_FACTOR, 3), DataType.FLOAT32)

            interpreter.run(inputBuffer.buffer, outputBuffer.buffer)

            // 4. Postprocess and Trim Padding
            enhancedTile = postprocessImage(outputBuffer)

            val outputPadding = PADDING_SIZE * UPSCALE_FACTOR

            // Determine the region of the enhanced tile to copy, removing the padded areas.
            val copySrcX = if (tileX > 0) outputPadding else 0
            val copySrcY = if (tileY > 0) outputPadding else 0

            val copyWidth = min(
                effectiveTileSize * UPSCALE_FACTOR,
                enhancedTile.width - copySrcX
            )
            val copyHeight = min(
                effectiveTileSize * UPSCALE_FACTOR,
                enhancedTile.height - copySrcY
            )

            val trimmedTile = Bitmap.createBitmap(
                enhancedTile,
                copySrcX,
                copySrcY,
                copyWidth,
                copyHeight
            )

            // 5. Calculate destination coordinates and copy
            val destX = startX * UPSCALE_FACTOR
            val destY = startY * UPSCALE_FACTOR

            copyTileToResult(trimmedTile, result, destX, destY)
            trimmedTile.recycle() // Clean up trimmed tile

        } finally {
            // CRITICAL: Clean up all resources
            paddedTile?.recycle()
            enhancedTile?.recycle()
        }
    }

    /**
     * Pads a source tile to the exact INPUT_TENSOR_SIZE (128x128) for the TFLite model.
     * It now ALWAYS returns a new bitmap to avoid recycling errors.
     */
    private fun padTileToInputSize(src: Bitmap): Bitmap {
        // --- FIX 3: Always create a new bitmap to avoid recycling the source tile prematurely. ---
        val padded = Bitmap.createBitmap(INPUT_TENSOR_SIZE, INPUT_TENSOR_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        // Draw the source bitmap onto the top-left of the padded bitmap
        canvas.drawBitmap(src, 0f, 0f, null)
        // Remaining area (if any) is transparent/black, which acts as implicit padding.
        return padded
        // --- END FIX 3 ---
    }

    private fun copyTileToResult(tile: Bitmap, result: Bitmap, destX: Int, destY: Int) {
        val tilePixels = IntArray(tile.width * tile.height)
        tile.getPixels(tilePixels, 0, tile.width, 0, 0, tile.width, tile.height)

        // Optimized copy using setPixels (faster than setPixel loop)
        val copyWidth = min(tile.width, result.width - destX)
        val copyHeight = min(tile.height, result.height - destY)

        if (copyWidth > 0 && copyHeight > 0) {
            result.setPixels(tilePixels, 0, tile.width, destX, destY, copyWidth, copyHeight)
        }
    }

    /**
     * Updates resize logic to favor cropping/scaling to a max dimension
     * that is a multiple of the effective tile size for cleaner tiling boundaries.
     */
    private fun resizeForProcessing(bitmap: Bitmap): Bitmap {
        val effectiveTileSize = INPUT_TENSOR_SIZE - 2 * PADDING_SIZE
        val maxDimension = 512 // Target max dimension
        val targetMax = (maxDimension / effectiveTileSize) * effectiveTileSize // e.g., 500

        val width = bitmap.width
        val height = bitmap.height

        if (width <= targetMax && height <= targetMax) {
            return bitmap
        }

        val scale = targetMax.toFloat() / kotlin.math.max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Resizing from ${width}x${height} to ${newWidth}x${newHeight} for tilling")
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Preprocesses the image into the TFLite [1, H, W, 3] FloatBuffer format.
     */
    private fun preprocessImage(bitmap: Bitmap): TensorBuffer {
        val width = bitmap.width
        val height = bitmap.height

        // TFLite input tensor is [1, 128, 128, 3]
        val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, height, width, 3), DataType.FLOAT32)
        val floatBuffer = inputBuffer.buffer.asFloatBuffer()

        // Real-ESRGAN expects normalized RGB values [0, 1]
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                // HWC format
                floatBuffer.put(Color.red(pixel) / 255.0f)
                floatBuffer.put(Color.green(pixel) / 255.0f)
                floatBuffer.put(Color.blue(pixel) / 255.0f)
            }
        }

        floatBuffer.rewind()
        return inputBuffer
    }

    /**
     * Postprocesses the TFLite output (FloatBuffer) back into a Bitmap.
     * Output format: [1, 512, 512, 3] HWC
     */
    private fun postprocessImage(outputBuffer: TensorBuffer): Bitmap {
        val shape = outputBuffer.shape
        val height = shape[1] // H
        val width = shape[2] // W
        val floatArray = outputBuffer.floatArray

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // HWC to RGB conversion
        for (y in 0 until height) {
            for (x in 0 until width) {
                val baseIndex = (y * width + x) * 3
                val r = (floatArray[baseIndex].coerceIn(0f, 1f) * 255).toInt()
                val g = (floatArray[baseIndex + 1].coerceIn(0f, 1f) * 255).toInt()
                val b = (floatArray[baseIndex + 2].coerceIn(0f, 1f) * 255).toInt()

                pixels[y * width + x] = Color.rgb(r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun resizeToOriginalAspect(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            return bitmap
        }

        // This function was resizing to the original *upscaled* aspect ratio,
        // which might not be what's desired. Let's assume the goal is to fit
        // within the target width/height *after* upscaling.
        val finalWidth = targetWidth * UPSCALE_FACTOR
        val finalHeight = targetHeight * UPSCALE_FACTOR

        if (bitmap.width == finalWidth && bitmap.height == finalHeight) {
            return bitmap
        }

        Log.d(TAG, "Resizing final output to ${finalWidth}x${finalHeight}")
        return Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
    }

    private fun applyEnhancementBoost(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        // Gentler enhancement for Real-ESRGAN output
        val contrastFactor = 1.00f
        val gamma = 0.98f
        val saturationBoost = 1.08f

        // Process pixels in batch
        for (i in pixels.indices) {
            val pixel = pixels[i]

            var r = Color.red(pixel) / 255.0f
            var g = Color.green(pixel) / 255.0f
            var b = Color.blue(pixel) / 255.0f

            r = ((r - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
            g = ((g - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
            b = ((b - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)

            r = r.pow(gamma)
            g = g.pow(gamma)
            b = b.pow(gamma)

            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            r = (gray + (r - gray) * saturationBoost).coerceIn(0f, 1f)
            g = (gray + (g - gray) * saturationBoost).coerceIn(0f, 1f)
            b = (gray + (b - gray) * saturationBoost).coerceIn(0f, 1f)

            val newR = (r * 255).toInt()
            val newG = (g * 255).toInt()
            val newB = (b * 255).toInt()

            pixels[i] = Color.rgb(newR, newG, newB)
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}

