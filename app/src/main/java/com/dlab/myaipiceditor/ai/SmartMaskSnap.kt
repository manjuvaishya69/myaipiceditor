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

object SmartMaskSnap {
    private const val TAG = "SmartMaskSnap"
    private const val MODEL_INPUT_SIZE = 640  // FastSAM uses 640x640

    suspend fun snapToObject(
        context: Context,
        bitmap: Bitmap,
        roughMask: Bitmap
    ): Bitmap = withContext(Dispatchers.Default) {
        var resized: Bitmap? = null
        var segmentationMask: Bitmap? = null
        var finalMask: Bitmap? = null

        try {
            Log.d(TAG, "Starting FastSAM segmentation")
            val interpreter = AiModelManager.getInterpreter(AiModelManager.ModelType.FOR_SEGMENTATION)

            // Resize image to 640x640
            resized = Bitmap.createScaledBitmap(
                bitmap,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

            // Get bounding box from rough mask
            val bbox = getBoundingBoxFromRoughMask(roughMask, bitmap.width, bitmap.height)
            Log.d(TAG, "Bounding box: $bbox")

            // Prepare input
            val inputBuffer = preprocessImage(resized)

            // Prepare output buffers
            // FastSAM has multiple outputs, we need the mask output (usually output 1)
            // Output 0: [1, 8400, 4] - bounding boxes
            // Output 1: [1, 32, H, W] - mask prototypes (what we need)

            // Log all outputs to understand the model
            for (i in 0 until interpreter.outputTensorCount) {
                val shape = interpreter.getOutputTensor(i).shape()
                Log.d(TAG, "Output $i shape: ${shape.contentToString()}")
            }

            // Get the mask output (typically output index 1)
            val maskOutputIndex = if (interpreter.outputTensorCount > 1) 1 else 0
            val outputShape = interpreter.getOutputTensor(maskOutputIndex).shape()
            Log.d(TAG, "Using output $maskOutputIndex with shape: ${outputShape.contentToString()}")

            // Calculate buffer size based on actual dimensions
            var bufferSize = 4 // start with float size
            for (dim in outputShape) {
                bufferSize *= dim
            }

            val outputBuffer = ByteBuffer.allocateDirect(bufferSize).apply {
                order(ByteOrder.nativeOrder())
            }

            // Run inference with proper output mapping
            val outputs = mutableMapOf<Int, Any>()
            outputs[maskOutputIndex] = outputBuffer

            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
            outputBuffer.rewind()

            // Post-process output
            segmentationMask = postprocessMask(
                outputBuffer,
                outputShape,
                bbox
            )

            // Resize back to original dimensions
            finalMask = Bitmap.createScaledBitmap(
                segmentationMask,
                bitmap.width,
                bitmap.height,
                true
            )

            // Refine with rough mask
            val refinedMask = refineMaskWithRoughMask(finalMask, roughMask)

            Log.d(TAG, "Segmentation complete")
            refinedMask

        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}", e)
            e.printStackTrace()
            // Fallback to rough mask
            roughMask.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            resized?.recycle()
            segmentationMask?.recycle()
            finalMask?.recycle()
        }
    }

    private fun getBoundingBoxFromRoughMask(
        roughMask: Bitmap,
        originalWidth: Int,
        originalHeight: Int
    ): FloatArray {
        val width = roughMask.width
        val height = roughMask.height
        val pixels = IntArray(width * height)
        roughMask.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var hasPixels = false

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = Color.red(pixels[y * width + x])
                if (value > 25) {
                    hasPixels = true
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }

        return if (hasPixels) {
            // Normalize to [0, 1] and scale to model input
            val scaleX = MODEL_INPUT_SIZE.toFloat() / originalWidth
            val scaleY = MODEL_INPUT_SIZE.toFloat() / originalHeight

            floatArrayOf(
                (minX.toFloat() / width * originalWidth * scaleX).coerceIn(0f, MODEL_INPUT_SIZE.toFloat()),
                (minY.toFloat() / height * originalHeight * scaleY).coerceIn(0f, MODEL_INPUT_SIZE.toFloat()),
                (maxX.toFloat() / width * originalWidth * scaleX).coerceIn(0f, MODEL_INPUT_SIZE.toFloat()),
                (maxY.toFloat() / height * originalHeight * scaleY).coerceIn(0f, MODEL_INPUT_SIZE.toFloat())
            )
        } else {
            // Default to center region
            floatArrayOf(
                MODEL_INPUT_SIZE * 0.25f,
                MODEL_INPUT_SIZE * 0.25f,
                MODEL_INPUT_SIZE * 0.75f,
                MODEL_INPUT_SIZE * 0.75f
            )
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(
            1 * 3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4
        ).apply {
            order(ByteOrder.nativeOrder())
        }

        // FastSAM uses RGB normalization: (pixel / 255.0)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        // CHW format: [C, H, W]
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> Color.red(pixel) / 255f
                    1 -> Color.green(pixel) / 255f
                    else -> Color.blue(pixel) / 255f
                }
                inputBuffer.putFloat(value)
            }
        }

        inputBuffer.rewind()
        return inputBuffer
    }

    private fun postprocessMask(
        outputBuffer: ByteBuffer,
        outputShape: IntArray,
        bbox: FloatArray
    ): Bitmap {
        // Handle different output shapes
        // Common formats: [1, N, H, W] or [N, H, W] or [H, W]
        val (numMasks, height, width) = when (outputShape.size) {
            4 -> Triple(outputShape[1], outputShape[2], outputShape[3])  // [1, N, H, W]
            3 -> Triple(outputShape[0], outputShape[1], outputShape[2])  // [N, H, W]
            2 -> Triple(1, outputShape[0], outputShape[1])               // [H, W]
            else -> {
                Log.e(TAG, "Unexpected output shape: ${outputShape.contentToString()}")
                return Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            }
        }

        Log.d(TAG, "Processing $numMasks masks of size ${width}x${height}")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // Find the best mask that overlaps with bbox
        var bestMask: FloatArray? = null
        var bestScore = 0f

        for (maskIdx in 0 until numMasks) {
            val maskData = FloatArray(width * height)
            for (i in maskData.indices) {
                maskData[i] = outputBuffer.getFloat()
            }

            // Calculate overlap with bbox
            val score = calculateMaskScore(maskData, width, height, bbox)
            if (score > bestScore) {
                bestScore = score
                bestMask = maskData
            }
        }

        // Use the best mask or first mask if no good match
        if (bestMask == null) {
            outputBuffer.rewind()
            bestMask = FloatArray(width * height)
            for (i in bestMask.indices) {
                bestMask[i] = outputBuffer.getFloat()
            }
        }

        // Convert mask to bitmap
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = bestMask[y * width + x]
                // Threshold at 0.5
                val maskValue = if (value > 0.5f) 255 else 0
                pixels[y * width + x] = Color.argb(255, maskValue, maskValue, maskValue)
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun calculateMaskScore(
        maskData: FloatArray,
        width: Int,
        height: Int,
        bbox: FloatArray
    ): Float {
        val x1 = bbox[0].toInt().coerceIn(0, width - 1)
        val y1 = bbox[1].toInt().coerceIn(0, height - 1)
        val x2 = bbox[2].toInt().coerceIn(0, width - 1)
        val y2 = bbox[3].toInt().coerceIn(0, height - 1)

        var score = 0f
        var count = 0

        for (y in y1..y2) {
            for (x in x1..x2) {
                score += maskData[y * width + x]
                count++
            }
        }

        return if (count > 0) score / count else 0f
    }

    private fun refineMaskWithRoughMask(segMask: Bitmap, roughMask: Bitmap): Bitmap {
        val width = segMask.width
        val height = segMask.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val segPixels = IntArray(width * height)
        val roughPixels = IntArray(width * height)

        segMask.getPixels(segPixels, 0, width, 0, 0, width, height)
        roughMask.getPixels(roughPixels, 0, width, 0, 0, width, height)

        val roughBounds = getRoughMaskBounds(roughPixels, width, height)

        if (roughBounds == null) {
            return segMask.copy(Bitmap.Config.ARGB_8888, true)
        }

        val expandedBounds = expandBounds(roughBounds, width, height, 30)

        val resultPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val segValue = Color.red(segPixels[idx])
                val roughValue = Color.red(roughPixels[idx])

                val inBounds = x >= expandedBounds[0] && x <= expandedBounds[2] &&
                        y >= expandedBounds[1] && y <= expandedBounds[3]

                val finalValue = if (inBounds) {
                    if (roughValue > 10 || segValue > 200) {
                        segValue
                    } else {
                        0
                    }
                } else {
                    if (segValue > 240) segValue else 0
                }

                resultPixels[idx] = Color.argb(255, finalValue, finalValue, finalValue)
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun getRoughMaskBounds(pixels: IntArray, width: Int, height: Int): IntArray? {
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var hasPixels = false

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = Color.red(pixels[y * width + x])
                if (value > 25) {
                    hasPixels = true
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }

        return if (hasPixels) intArrayOf(minX, minY, maxX, maxY) else null
    }

    private fun expandBounds(
        bounds: IntArray,
        width: Int,
        height: Int,
        expansion: Int
    ): IntArray {
        return intArrayOf(
            max(0, bounds[0] - expansion),
            max(0, bounds[1] - expansion),
            min(width - 1, bounds[2] + expansion),
            min(height - 1, bounds[3] + expansion)
        )
    }
}