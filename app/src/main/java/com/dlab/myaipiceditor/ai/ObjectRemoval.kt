package com.dlab.myaipiceditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object ObjectRemoval {
    private const val TAG = "ObjectRemoval"
    private const val MODEL_INPUT_SIZE = 512  // AOT-GAN typically uses 512x512

    suspend fun removeObject(
        context: Context,
        image: Bitmap,
        mask: Bitmap,
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        var resizedImage: Bitmap? = null
        var resizedMask: Bitmap? = null
        var result: Bitmap? = null

        try {
            Log.d(TAG, "Starting object removal: ${image.width}x${image.height}")
            onProgress(0.1f)

            val interpreter = AiModelManager.getInterpreter(AiModelManager.ModelType.OBJECT_REMOVAL)

            // Resize to model input size
            resizedImage = Bitmap.createScaledBitmap(
                image,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )
            resizedMask = Bitmap.createScaledBitmap(
                mask,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

            onProgress(0.2f)

            // Prepare inputs
            val imageBuffer = preprocessImage(resizedImage)
            val maskBuffer = preprocessMask(resizedMask)

            onProgress(0.3f)

            // Get output tensor info
            val outputShape = interpreter.getOutputTensor(0).shape()
            Log.d(TAG, "Output shape: ${outputShape.contentToString()}")

            val outputBuffer = ByteBuffer.allocateDirect(
                outputShape[0] * outputShape[1] * outputShape[2] * outputShape[3] * 4
            ).apply {
                order(ByteOrder.nativeOrder())
            }

            onProgress(0.4f)

            // Run inference
            // AOT-GAN takes image and mask as inputs
            val inputs = arrayOf(imageBuffer, maskBuffer)
            val outputs = mapOf(0 to outputBuffer)

            interpreter.runForMultipleInputsOutputs(inputs, outputs)

            onProgress(0.7f)

            // Post-process output
            outputBuffer.rewind()
            result = postprocessImage(outputBuffer, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

            onProgress(0.9f)

            // Resize back to original size
            val finalResult = Bitmap.createScaledBitmap(
                result,
                image.width,
                image.height,
                true
            )

            onProgress(1.0f)
            Log.d(TAG, "Object removal complete")

            finalResult

        } catch (e: Exception) {
            Log.e(TAG, "Object removal failed: ${e.message}", e)
            e.printStackTrace()
            // Return original image on failure
            image.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            resizedImage?.recycle()
            resizedMask?.recycle()
            result?.recycle()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            1 * 3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4
        ).apply {
            order(ByteOrder.nativeOrder())
        }

        // Normalize to [-1, 1] range (common for inpainting models)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        // CHW format
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> Color.red(pixel)
                    1 -> Color.green(pixel)
                    else -> Color.blue(pixel)
                }
                // Normalize to [-1, 1]
                val normalized = (value / 127.5f) - 1f
                buffer.putFloat(normalized)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun preprocessMask(mask: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            1 * 1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4
        ).apply {
            order(ByteOrder.nativeOrder())
        }

        // Mask is binary: 1 for areas to inpaint, 0 for keep
        for (y in 0 until MODEL_INPUT_SIZE) {
            for (x in 0 until MODEL_INPUT_SIZE) {
                val pixel = mask.getPixel(x, y)
                val value = Color.red(pixel)
                // Normalize to [0, 1]
                val normalized = if (value > 127) 1f else 0f
                buffer.putFloat(normalized)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun postprocessImage(
        outputBuffer: ByteBuffer,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // Output format is [1, 3, H, W] with values in [-1, 1] or [0, 1]
        val rChannel = FloatArray(width * height)
        val gChannel = FloatArray(width * height)
        val bChannel = FloatArray(width * height)

        // Read CHW format
        for (i in rChannel.indices) rChannel[i] = outputBuffer.getFloat()
        for (i in gChannel.indices) gChannel[i] = outputBuffer.getFloat()
        for (i in bChannel.indices) bChannel[i] = outputBuffer.getFloat()

        // Convert to bitmap
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x

                // Denormalize from [-1, 1] to [0, 255]
                val r = ((rChannel[idx] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                val g = ((gChannel[idx] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                val b = ((bChannel[idx] + 1f) * 127.5f).toInt().coerceIn(0, 255)

                pixels[idx] = Color.rgb(r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Tile-based processing for larger images (if needed)
     */
    suspend fun removeObjectTiled(
        context: Context,
        image: Bitmap,
        mask: Bitmap,
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        // If image is small enough, use direct processing
        if (image.width <= MODEL_INPUT_SIZE && image.height <= MODEL_INPUT_SIZE) {
            return@withContext removeObject(context, image, mask, onProgress)
        }

        // For larger images, process in tiles
        val tileSize = MODEL_INPUT_SIZE
        val tilesX = (image.width + tileSize - 1) / tileSize
        val tilesY = (image.height + tileSize - 1) / tileSize
        val totalTiles = tilesX * tilesY

        Log.d(TAG, "Processing ${totalTiles} tiles (${tilesX}x${tilesY})")

        val result = image.copy(Bitmap.Config.ARGB_8888, true)
        var processedTiles = 0

        for (tileY in 0 until tilesY) {
            for (tileX in 0 until tilesX) {
                val x = tileX * tileSize
                val y = tileY * tileSize
                val w = min(tileSize, image.width - x)
                val h = min(tileSize, image.height - y)

                // Extract tile
                val imageTile = Bitmap.createBitmap(image, x, y, w, h)
                val maskTile = Bitmap.createBitmap(mask, x, y, w, h)

                // Process tile
                val processedTile = removeObject(context, imageTile, maskTile) { }

                // Copy back to result
                for (py in 0 until h) {
                    for (px in 0 until w) {
                        result.setPixel(x + px, y + py, processedTile.getPixel(px, py))
                    }
                }

                imageTile.recycle()
                maskTile.recycle()
                processedTile.recycle()

                processedTiles++
                onProgress(processedTiles.toFloat() / totalTiles)
            }
        }

        result
    }
}