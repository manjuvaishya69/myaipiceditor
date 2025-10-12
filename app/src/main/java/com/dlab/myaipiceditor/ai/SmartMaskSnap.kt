package com.dlab.myaipiceditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object SmartMaskSnap {
    private const val TAG = "SmartMaskSnap"
    private const val MODEL_INPUT_SIZE = 1024  // MobileSAM encoder input
    private const val DECODER_INPUT_MASK_SIZE = 256 // Low-res mask input required by the decoder (4x embeddings)

    suspend fun snapToObject(
        context: Context,
        bitmap: Bitmap,
        roughMask: Bitmap
    ): Bitmap = withContext(Dispatchers.Default) {
        // Tensors and resources must be declared here so they can be closed in the finally block
        var resized: Bitmap? = null
        var segmentationMask: Bitmap? = null
        var finalMaskScaled: Bitmap? = null
        var refinedMask: Bitmap? = null

        var imageTensor: OnnxTensor? = null
        var pointCoordsTensor: OnnxTensor? = null
        var pointLabelsTensor: OnnxTensor? = null
        var imageEmbeddingTensor: OnnxTensor? = null
        var maskInputTensor: OnnxTensor? = null
        var hasMaskInputTensor: OnnxTensor? = null
        var decoderOutputs: ai.onnxruntime.OrtSession.Result? = null
        var encoderOutputs: ai.onnxruntime.OrtSession.Result? = null

        try {
            // 1. Image Preprocessing (Encoder Input)
            val (resizedBitmap, preprocessedImage) = preprocessImage(bitmap, context)
            resized = resizedBitmap
            val ortEnvironment = AiModelManager.getEnvironment()

            // Model expects [1024, 1024, 3] in HWC format
            val imageShape = longArrayOf(MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong(), 3)
            imageTensor = OnnxTensor.createTensor(ortEnvironment, preprocessedImage, imageShape)

            // 2. Point Prompt Generation (Encoder Input)
            val centerPoint = getCenterPoint(roughMask)
            if (centerPoint == null) {
                Log.e(TAG, "Rough mask is empty, cannot generate center point in SmartMaskSnap.")
                return@withContext roughMask
            }
            Log.d(TAG, "Center point: ${centerPoint.x}, ${centerPoint.y}")

            val (scaledPointCoords, pointLabel) = preparePointPrompt(centerPoint, bitmap.width, bitmap.height)

            // Point coords: [1, 1, 2]
            val pointCoordsBuffer = ByteBuffer.allocateDirect(1 * 1 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            pointCoordsBuffer.put(scaledPointCoords[0][0])
            pointCoordsBuffer.rewind()
            pointCoordsTensor = OnnxTensor.createTensor(ortEnvironment, pointCoordsBuffer, longArrayOf(1, 1, 2))

            // Point labels: [1, 1]
            val pointLabelsBuffer = ByteBuffer.allocateDirect(1 * 1 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            pointLabelsBuffer.put(pointLabel)
            pointLabelsBuffer.rewind()
            pointLabelsTensor = OnnxTensor.createTensor(ortEnvironment, pointLabelsBuffer, longArrayOf(1, 1))

            // 3. Encoder Inference: Generate Image Embeddings
            Log.d(TAG, "Running encoder inference...")
            val encoderSession = AiModelManager.getSession(AiModelManager.ModelType.MOBILE_SAM_ENCODER)
            val encoderInputs = mapOf(
                "input_image" to imageTensor
            )
            encoderOutputs = encoderSession.run(encoderInputs)

            // Get the image embeddings output - it's wrapped in an Optional
            val rawEmbeddingOptional = encoderOutputs.get("image_embeddings")

            if (!rawEmbeddingOptional.isPresent) {
                throw IllegalStateException("image_embeddings output not found in encoder results")
            }

            val rawEmbeddingOrtValue = rawEmbeddingOptional.get()

            if (rawEmbeddingOrtValue is OnnxTensor) {
                // Direct tensor - just use it
                Log.d(TAG, "Using direct tensor, shape: ${rawEmbeddingOrtValue.info.shape.contentToString()}")
                imageEmbeddingTensor = rawEmbeddingOrtValue
            } else {
                throw IllegalStateException("Expected OnnxTensor but got: ${rawEmbeddingOrtValue?.javaClass?.name}")
            }

            // 4. Decoder Inference: Generate Segmentation Mask
            Log.d(TAG, "Running decoder inference...")
            val decoderSession = AiModelManager.getSession(AiModelManager.ModelType.MOBILE_SAM_DECODER)

            // Create an empty mask input tensor
            val maskInputShape = longArrayOf(1, 1, DECODER_INPUT_MASK_SIZE.toLong(), DECODER_INPUT_MASK_SIZE.toLong())
            val maskInputByteBuffer = ByteBuffer.allocateDirect(1 * 1 * DECODER_INPUT_MASK_SIZE * DECODER_INPUT_MASK_SIZE * 4)
            maskInputByteBuffer.order(ByteOrder.nativeOrder())
            val maskInputBuffer = maskInputByteBuffer.asFloatBuffer()
            maskInputTensor = OnnxTensor.createTensor(ortEnvironment, maskInputBuffer, maskInputShape)

            val hasMaskInputBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            hasMaskInputBuffer.put(0f)
            hasMaskInputBuffer.rewind()
            hasMaskInputTensor = OnnxTensor.createTensor(ortEnvironment, hasMaskInputBuffer, longArrayOf(1))

            // Create orig_im_size tensor - this is the original image size before resizing
            val origImSizeBuffer = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            origImSizeBuffer.put(MODEL_INPUT_SIZE.toFloat())  // height
            origImSizeBuffer.put(MODEL_INPUT_SIZE.toFloat())  // width
            origImSizeBuffer.rewind()
            val origImSizeTensor = OnnxTensor.createTensor(ortEnvironment, origImSizeBuffer, longArrayOf(2))

            val decoderInputs = mapOf(
                "image_embeddings" to imageEmbeddingTensor,
                "point_coords" to pointCoordsTensor,
                "point_labels" to pointLabelsTensor,
                "mask_input" to maskInputTensor,
                "has_mask_input" to hasMaskInputTensor,
                "orig_im_size" to origImSizeTensor
            )
            decoderOutputs = decoderSession.run(decoderInputs)

            // Close the orig_im_size tensor as it's not tracked elsewhere
            origImSizeTensor.close()

            // Extract and process output logits
            val rawLogitsOptional = decoderOutputs.get("masks")

            if (!rawLogitsOptional.isPresent) {
                throw IllegalStateException("masks output not found in decoder results")
            }

            val rawLogitsOrtValue = rawLogitsOptional.get()
            val rawLogitsArray = rawLogitsOrtValue.value

            if (rawLogitsArray !is Array<*> || rawLogitsArray.size != 1 || rawLogitsArray[0] !is Array<*>) {
                throw IllegalStateException("Unexpected format for decoder 'masks' output.")
            }

            @Suppress("UNCHECKED_CAST")
            val outputLogits = rawLogitsArray as Array<Array<Array<FloatArray>>>

            // 5. Post-processing
            segmentationMask = postProcessMask(outputLogits, bitmap.width, bitmap.height, context)

            // 6. Refine mask by blending with rough mask
            refinedMask = refineMaskWithRoughMask(segmentationMask, roughMask)

            return@withContext refinedMask
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}", e)
            return@withContext roughMask
        } finally {
            // Close all resources
            resized?.recycle()
            segmentationMask?.recycle()
            finalMaskScaled?.recycle()
            imageTensor?.close()
            pointCoordsTensor?.close()
            pointLabelsTensor?.close()
            // Don't close imageEmbeddingTensor if it's from encoderOutputs - it will be closed with encoderOutputs
            if (imageEmbeddingTensor != null && encoderOutputs?.get("image_embeddings")?.orElse(null) != imageEmbeddingTensor) {
                imageEmbeddingTensor?.close()
            }
            maskInputTensor?.close()
            hasMaskInputTensor?.close()
            decoderOutputs?.close()
            encoderOutputs?.close()
        }
    }

    private fun preprocessImage(bitmap: Bitmap, context: Context): Pair<Bitmap, FloatBuffer> {
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)

        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        val numFloats = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3
        val directBuffer = ByteBuffer.allocateDirect(numFloats * 4)
        directBuffer.order(ByteOrder.nativeOrder())
        val floatBuffer = directBuffer.asFloatBuffer()

        val means = floatArrayOf(123.675f, 116.28f, 103.53f)
        val stds = floatArrayOf(58.395f, 57.12f, 57.375f)

        // HWC order: For each pixel, write R, G, B consecutively
        for (i in pixels.indices) {
            val pixel = pixels[i]

            // Red channel
            val r = Color.red(pixel)
            floatBuffer.put((r - means[0]) / stds[0])

            // Green channel
            val g = Color.green(pixel)
            floatBuffer.put((g - means[1]) / stds[1])

            // Blue channel
            val b = Color.blue(pixel)
            floatBuffer.put((b - means[2]) / stds[2])
        }

        floatBuffer.rewind()
        return Pair(resized, floatBuffer)
    }

    private fun getCenterPoint(mask: Bitmap): Point? {
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)

        val bounds = getRoughMaskBounds(pixels, mask.width, mask.height) ?: return null

        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]

        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2

        return Point(centerX, centerY)
    }

    private fun preparePointPrompt(center: Point, originalWidth: Int, originalHeight: Int): Pair<Array<Array<FloatArray>>, FloatArray> {
        // SAM models expect coordinates in the input image space (before any transformations)
        // Just scale proportionally based on the longest dimension
        val longestSide = max(originalWidth, originalHeight)
        val scale = MODEL_INPUT_SIZE.toFloat() / longestSide.toFloat()

        val scaledX = center.x.toFloat() * scale
        val scaledY = center.y.toFloat() * scale

        Log.d(TAG, "Original point: (${center.x}, ${center.y}), Scaled point: ($scaledX, $scaledY)")
        Log.d(TAG, "Original size: ${originalWidth}x${originalHeight}, Scale: $scale")

        val coords = arrayOf(arrayOf(floatArrayOf(scaledX, scaledY)))
        val labels = floatArrayOf(1f)

        return Pair(coords, labels)
    }

    private fun postProcessMask(
        outputLogits: Array<Array<Array<FloatArray>>>,
        originalWidth: Int,
        originalHeight: Int,
        context: Context
    ): Bitmap {
        val logits = outputLogits[0][0]
        val maskSize = DECODER_INPUT_MASK_SIZE * DECODER_INPUT_MASK_SIZE
        val finalMask = Bitmap.createBitmap(DECODER_INPUT_MASK_SIZE, DECODER_INPUT_MASK_SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskSize)

        for (y in 0 until DECODER_INPUT_MASK_SIZE) {
            for (x in 0 until DECODER_INPUT_MASK_SIZE) {
                val logit = logits[y][x]
                val probability = 1.0f / (1.0f + exp(-logit))
                val grayValue = (probability * 255).toInt().coerceIn(0, 255)
                pixels[y * DECODER_INPUT_MASK_SIZE + x] = Color.argb(255, grayValue, grayValue, grayValue)
            }
        }

        finalMask.setPixels(pixels, 0, DECODER_INPUT_MASK_SIZE, 0, 0, DECODER_INPUT_MASK_SIZE, DECODER_INPUT_MASK_SIZE)

        return Bitmap.createScaledBitmap(finalMask, originalWidth, originalHeight, true).apply {
            finalMask.recycle()
        }
    }

    private fun refineMaskWithRoughMask(segmentation: Bitmap, roughMask: Bitmap): Bitmap {
        val width = segmentation.width
        val height = segmentation.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val resultPixels = IntArray(width * height)

        val segPixels = IntArray(width * height)
        segmentation.getPixels(segPixels, 0, width, 0, 0, width, height)

        val roughPixels = IntArray(width * height)
        roughMask.getPixels(roughPixels, 0, width, 0, 0, width, height)

        // Get the bounds of the rough mask
        val bounds = getRoughMaskBounds(roughPixels, width, height)

        if (bounds == null) {
            // If no rough mask, return the segmentation as-is
            Log.d(TAG, "No rough mask bounds found, returning segmentation")
            return segmentation
        }

        // Expand bounds slightly (5 pixels for minimal expansion)
        val expandedBounds = expandBounds(bounds, width, height, 5)

        Log.d(TAG, "Rough mask bounds: [${bounds[0]}, ${bounds[1]}, ${bounds[2]}, ${bounds[3]}]")
        Log.d(TAG, "Expanded bounds: [${expandedBounds[0]}, ${expandedBounds[1]}, ${expandedBounds[2]}, ${expandedBounds[3]}]")

        // Only consider segmentation inside the expanded rough mask area
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val segValue = Color.red(segPixels[idx])
                val roughValue = Color.red(roughPixels[idx])

                val finalValue = if (x < expandedBounds[0] || x > expandedBounds[2] ||
                    y < expandedBounds[1] || y > expandedBounds[3]) {
                    // Outside expanded bounds - keep empty
                    0
                } else if (roughValue > 25) {
                    // Inside rough mask area - use intersection of rough and segmentation
                    if (segValue > 128) segValue else 0
                } else {
                    // Outside rough mask - ignore completely
                    0
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
        val minX = max(0, bounds[0] - expansion)
        val minY = max(0, bounds[1] - expansion)
        val maxX = min(width - 1, bounds[2] + expansion)
        val maxY = min(height - 1, bounds[3] + expansion)
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private data class Point(val x: Int, val y: Int)
}