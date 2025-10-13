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
        var resizedMask: Bitmap? = null
        var segmentationMask: Bitmap? = null
        var finalMask: Bitmap? = null

        try {
            Log.d(TAG, "Starting FastSAM segmentation")
            val interpreter = AiModelManager.getInterpreter(AiModelManager.ModelType.FOR_SEGMENTATION)

            // Resize image and mask to 640x640
            resized = Bitmap.createScaledBitmap(
                bitmap,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

            resizedMask = Bitmap.createScaledBitmap(
                roughMask,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                true
            )

            // Get bounding box from rough mask
            val bbox = getBoundingBoxFromRoughMask(resizedMask, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
            Log.d(TAG, "Bounding box: ${bbox.contentToString()}")

            // Prepare input
            val inputBuffer = preprocessImage(resized)

            // Log all outputs to understand the model
            for (i in 0 until interpreter.outputTensorCount) {
                val shape = interpreter.getOutputTensor(i).shape()
                Log.d(TAG, "Output $i shape: ${shape.contentToString()}")
            }

            // FastSAM outputs:
            // Output 0: [1, 8400, 4] - bounding boxes
            // Output 1: [1, 8400] - confidence scores
            // Output 2: [1, 8400, 32] - mask coefficients
            // Output 3: [1, 160, 160, 32] - mask prototypes

            // We need outputs 0, 1, 2, and 3
            val boxesBuffer = createOutputBuffer(interpreter.getOutputTensor(0).shape())
            val scoresBuffer = createOutputBuffer(interpreter.getOutputTensor(1).shape())
            val coeffsBuffer = createOutputBuffer(interpreter.getOutputTensor(2).shape())
            val protosBuffer = createOutputBuffer(interpreter.getOutputTensor(3).shape())

            // Run inference
            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = boxesBuffer
            outputs[1] = scoresBuffer
            outputs[2] = coeffsBuffer
            outputs[3] = protosBuffer

            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            // Process FastSAM outputs
            segmentationMask = processFastSAMOutputs(
                boxesBuffer,
                scoresBuffer,
                coeffsBuffer,
                protosBuffer,
                bbox,
                interpreter.getOutputTensor(0).shape(),
                interpreter.getOutputTensor(1).shape(),
                interpreter.getOutputTensor(2).shape(),
                interpreter.getOutputTensor(3).shape()
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
            // Fallback to rough mask with simple refinement
            enhanceRoughMask(roughMask)
        } finally {
            resized?.recycle()
            resizedMask?.recycle()
            segmentationMask?.recycle()
            finalMask?.recycle()
        }
    }

    private fun createOutputBuffer(shape: IntArray): ByteBuffer {
        var size = 4 // float size
        for (dim in shape) {
            size *= dim
        }
        return ByteBuffer.allocateDirect(size).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private fun enhanceRoughMask(roughMask: Bitmap): Bitmap {
        // Simple fallback: blur and threshold the rough mask for smoother edges
        val width = roughMask.width
        val height = roughMask.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        roughMask.getPixels(pixels, 0, width, 0, 0, width, height)

        // Simple 3x3 average filter for smoothing
        val smoothed = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            sum += Color.red(pixels[ny * width + nx])
                            count++
                        }
                    }
                }

                val avg = sum / count
                smoothed[y * width + x] = Color.rgb(avg, avg, avg)
            }
        }

        result.setPixels(smoothed, 0, width, 0, 0, width, height)
        return result
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

    private fun processFastSAMOutputs(
        boxesBuffer: ByteBuffer,
        scoresBuffer: ByteBuffer,
        coeffsBuffer: ByteBuffer,
        protosBuffer: ByteBuffer,
        targetBbox: FloatArray,
        boxesShape: IntArray,
        scoresShape: IntArray,
        coeffsShape: IntArray,
        protosShape: IntArray
    ): Bitmap {
        boxesBuffer.rewind()
        scoresBuffer.rewind()
        coeffsBuffer.rewind()
        protosBuffer.rewind()

        val numDetections = boxesShape[1] // 8400
        val numCoeffs = coeffsShape[2] // 32
        val protoH = protosShape[1] // 160
        val protoW = protosShape[2] // 160

        Log.d(TAG, "Processing FastSAM: $numDetections detections, $numCoeffs coeffs, proto ${protoH}x${protoW}")

        // Find best detection that overlaps with target bbox
        var bestIdx = -1
        var bestScore = 0f
        var bestIoU = 0f

        for (i in 0 until numDetections) {
            val score = scoresBuffer.getFloat(i * 4)

            if (score < 0.3f) continue // Skip low confidence

            val x1 = boxesBuffer.getFloat((i * 4 + 0) * 4)
            val y1 = boxesBuffer.getFloat((i * 4 + 1) * 4)
            val x2 = boxesBuffer.getFloat((i * 4 + 2) * 4)
            val y2 = boxesBuffer.getFloat((i * 4 + 3) * 4)

            // Calculate IoU with target bbox
            val iou = calculateIoU(
                x1, y1, x2, y2,
                targetBbox[0], targetBbox[1], targetBbox[2], targetBbox[3]
            )

            if (iou > bestIoU) {
                bestIoU = iou
                bestScore = score
                bestIdx = i
            }
        }

        Log.d(TAG, "Best detection: idx=$bestIdx, score=$bestScore, IoU=$bestIoU")

        if (bestIdx < 0) {
            Log.w(TAG, "No good detection found, returning empty mask")
            return Bitmap.createBitmap(protoH, protoW, Bitmap.Config.ARGB_8888)
        }

        // Get mask coefficients for best detection
        val coeffs = FloatArray(numCoeffs)
        for (c in 0 until numCoeffs) {
            coeffs[c] = coeffsBuffer.getFloat((bestIdx * numCoeffs + c) * 4)
        }

        // Get mask prototypes
        val protos = Array(protoH) { Array(protoW) { FloatArray(numCoeffs) } }
        for (h in 0 until protoH) {
            for (w in 0 until protoW) {
                for (c in 0 until numCoeffs) {
                    protos[h][w][c] = protosBuffer.getFloat(((h * protoW + w) * numCoeffs + c) * 4)
                }
            }
        }

        // Combine coefficients with prototypes to get final mask
        val maskData = Array(protoH) { FloatArray(protoW) }
        for (h in 0 until protoH) {
            for (w in 0 until protoW) {
                var sum = 0f
                for (c in 0 until numCoeffs) {
                    sum += coeffs[c] * protos[h][w][c]
                }
                // Sigmoid activation
                maskData[h][w] = 1f / (1f + kotlin.math.exp(-sum))
            }
        }

        // Convert to bitmap
        val bitmap = Bitmap.createBitmap(protoW, protoH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(protoW * protoH)

        for (h in 0 until protoH) {
            for (w in 0 until protoW) {
                val value = (maskData[h][w] * 255).toInt().coerceIn(0, 255)
                pixels[h * protoW + w] = Color.rgb(value, value, value)
            }
        }

        bitmap.setPixels(pixels, 0, protoW, 0, 0, protoW, protoH)

        // Resize to 640x640
        return Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
    }

    private fun calculateIoU(
        x1a: Float, y1a: Float, x2a: Float, y2a: Float,
        x1b: Float, y1b: Float, x2b: Float, y2b: Float
    ): Float {
        val xA = max(x1a, x1b)
        val yA = max(y1a, y1b)
        val xB = min(x2a, x2b)
        val yB = min(y2a, y2b)

        val interArea = max(0f, xB - xA) * max(0f, yB - yA)
        val boxAArea = (x2a - x1a) * (y2a - y1a)
        val boxBArea = (x2b - x1b) * (y2b - y1b)

        return interArea / (boxAArea + boxBArea - interArea + 1e-6f)
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