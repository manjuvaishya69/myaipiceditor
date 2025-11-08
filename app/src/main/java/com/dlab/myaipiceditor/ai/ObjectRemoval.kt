package com.dlab.myaipiceditor.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * ObjectRemoval using DeepFillv2 ONNX model
 *
 * This model is complex and requires a very specific pre-processing pipeline.
 *
 * 1.  **Normalization:** Input pixels are normalized to [-1, 1].
 * 2.  **Strict Size:** Input tensor MUST be (1, 5, 1080, 1920).
 * 3.  **Letterboxing:** The image/mask are resized to fit 1080x1920,
 * maintaining aspect ratio, and then padded.
 * 4.  **5-Channel Input:** The model takes a single 5-channel tensor:
 * - Channel 1: Red (masked)
 * - Channel 2: Green (masked)
 * - Channel 3: Blue (masked)
 * - Channel 4: Mask (1.0 for hole, 0.0 for keep)
 * - Channel 5: A channel of all 1.0s
 */
object ObjectRemoval {

    private const val TAG = "ObjectRemoval"
    private const val MODEL_WIDTH = 1920
    private const val MODEL_HEIGHT = 1080

    // Stores padding info to un-pad the image after inference
    private data class LetterboxInfo(
        val top: Int,
        val left: Int,
        val newWidth: Int,
        val newHeight: Int
    )

    /**
     * Main function to run DeepFillv2 inpainting.
     *
     * @param context Context for loading models.
     * @param image The original user image.
     * @param mask A black-and-white mask (WHITE = area to remove).
     * @param onProgress A callback to report progress.
     * @return The inpainted bitmap.
     */
    suspend fun removeObject(
        context: Context,
        image: Bitmap,
        mask: Bitmap,
        onProgress: ((Float) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {

        Log.d(TAG, "========================================")
        Log.d(TAG, "STARTING DEEPFILLV2 OBJECT REMOVAL")
        Log.d(TAG, "========================================")
        Log.d(TAG, "Original Image: ${image.width}x${image.height}")

        val startTime = System.currentTimeMillis()
        var session: OrtSession? = null
        var env: OrtEnvironment? = null
        var imageTensor: OnnxTensor? = null
        var resizedImage: Bitmap? = null
        var resizedMask: Bitmap? = null

        try {
            // --- STEP 1: LOAD MODEL ---
            Log.d(TAG, "Loading DeepFillv2 model...")
            onProgress?.invoke(0.1f)
            AiModelManager.initialize(context) // Ensure manager is ready
            session = AiModelManager.getSession(AiModelManager.ModelType.OBJECT_REMOVAL)
            env = AiModelManager.getEnvironment()
            Log.d(TAG, "Model loaded in ${System.currentTimeMillis() - startTime}ms")

            // --- STEP 2: RESIZE & LETTERBOX ---
            Log.d(TAG, "Resizing and letterboxing inputs to ${MODEL_WIDTH}x${MODEL_HEIGHT}...")
            onProgress?.invoke(0.2f)
            val (paddedImage, paddedMask, letterboxInfo) = resizeAndLetterbox(
                image, mask, MODEL_WIDTH, MODEL_HEIGHT
            )
            resizedImage = paddedImage
            resizedMask = paddedMask
            Log.d(TAG, "Letterboxing complete. Padded size: ${paddedImage.width}x${paddedImage.height}")

            // --- STEP 3: CREATE 5-CHANNEL INPUT TENSOR ---
            Log.d(TAG, "Creating 5-channel input tensor...")
            onProgress?.invoke(0.4f)
            val inputBuffer = create5ChannelInputBuffer(paddedImage, paddedMask)
            Log.d(TAG, "Input buffer created (size: ${inputBuffer.capacity()})")

            // Create the tensor
            val shape = longArrayOf(1, 5, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
            imageTensor = OnnxTensor.createTensor(env, inputBuffer, shape)
            onProgress?.invoke(0.6f)

            // --- STEP 4: RUN INFERENCE ---
            Log.d(TAG, "Running inference...")
            val inputs = mapOf("input" to imageTensor) // Model's input node name is often 'input'
            val inferenceTime = measureTimeMillis {
                val results = session.run(inputs)
                onProgress?.invoke(0.8f)

                Log.d(TAG, "Inference complete. Processing output...")
                // --- STEP 5: PROCESS OUTPUT ---
                // Output is [1, 3, 1080, 1920] normalized [-1, 1]
                val outputTensor = results[0].value as Array<*> // [1, 3, H, W]
                val outputImage = tensorToBitmap(outputTensor, MODEL_WIDTH, MODEL_HEIGHT)
                results.close()

                // --- STEP 6: UN-PAD AND RESIZE ---
                Log.d(TAG, "Un-padding and resizing output...")
                onProgress?.invoke(0.9f)
                // Crop the letterboxing
                val croppedOutput = Bitmap.createBitmap(
                    outputImage,
                    letterboxInfo.left,
                    letterboxInfo.top,
                    letterboxInfo.newWidth,
                    letterboxInfo.newHeight
                )

                // Resize back to original
                val finalBitmap = Bitmap.createScaledBitmap(
                    croppedOutput, image.width, image.height, true
                )

                // Recycle intermediate bitmaps
                outputImage.recycle()
                croppedOutput.recycle()

                Log.d(TAG, "========================================")
                Log.d(TAG, "DEEPFILLV2 COMPLETE in ${(System.currentTimeMillis() - startTime)}ms")
                Log.d(TAG, "========================================")

                return@withContext finalBitmap
            }
            Log.d(TAG, "Inference time: ${inferenceTime}ms")

        } catch (e: Exception) {
            Log.e(TAG, "DeepFillv2 failed: ${e.message}", e)
            throw e // Re-throw to inform the user
        } finally {
            // --- STEP 7: CLEANUP ---
            imageTensor?.close()
            resizedImage?.recycle()
            resizedMask?.recycle()
            // Note: Do not close the session, AiModelManager handles it.
        }

        // Should not be reached, but as a fallback
        return@withContext image
    }

    /**
     * Resizes and pads an image/mask to fit a target size.
     */
    private fun resizeAndLetterbox(
        image: Bitmap,
        mask: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Triple<Bitmap, Bitmap, LetterboxInfo> {
        val originalWidth = image.width
        val originalHeight = image.height

        val widthRatio = targetWidth.toFloat() / originalWidth
        val heightRatio = targetHeight.toFloat() / originalHeight
        val ratio = minOf(widthRatio, heightRatio)

        val newWidth = (originalWidth * ratio).roundToInt()
        val newHeight = (originalHeight * ratio).roundToInt()

        val top = (targetHeight - newHeight) / 2
        val left = (targetWidth - newWidth) / 2

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // --- Process Image ---
        val paddedImage = Bitmap.createBitmap(targetWidth, targetHeight, image.config ?: Bitmap.Config.ARGB_8888)
        val canvasImg = Canvas(paddedImage)
        canvasImg.drawColor(Color.BLACK) // Pad with black
        val targetRectImg = RectF(left.toFloat(), top.toFloat(), (left + newWidth).toFloat(), (top + newHeight).toFloat())
        canvasImg.drawBitmap(image, null, targetRectImg, paint)

        // --- Process Mask ---
        val paddedMask = Bitmap.createBitmap(targetWidth, targetHeight, mask.config ?: Bitmap.Config.ARGB_8888)
        val canvasMask = Canvas(paddedMask)
        canvasMask.drawColor(Color.BLACK) // Pad mask with BLACK (0.0 = keep)
        val targetRectMask = RectF(left.toFloat(), top.toFloat(), (left + newWidth).toFloat(), (top + newHeight).toFloat())
        canvasMask.drawBitmap(mask, null, targetRectMask, paint)

        val info = LetterboxInfo(top, left, newWidth, newHeight)
        return Triple(paddedImage, paddedMask, info)
    }

    /**
     * Creates the 5-channel CHW FloatBuffer for the DeepFillv2 model.
     * Channels: [R_masked, G_masked, B_masked, Mask, Ones]
     * Normalized: [-1, 1]
     */
    private fun create5ChannelInputBuffer(image: Bitmap, mask: Bitmap): FloatBuffer {
        val width = image.width // Should be 1920
        val height = image.height // Should be 1080
        val totalPixels = width * height
        val buffer = FloatBuffer.allocate(5 * totalPixels)

        val imagePixels = IntArray(totalPixels)
        image.getPixels(imagePixels, 0, width, 0, 0, width, height)

        val maskPixels = IntArray(totalPixels)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        // Pre-calculate mask values
        val maskF = FloatArray(totalPixels) { i ->
            if (Color.red(maskPixels[i]) > 127) 1.0f else 0.0f
        }
        val maskInvF = FloatArray(totalPixels) { i -> 1.0f - maskF[i] }

        // --- Channels 1, 2, 3 (R, G, B) ---
        // This is slow, but required for CHW format
        // Channel 1: Red
        for (i in 0 until totalPixels) {
            val pixel = imagePixels[i]
            val r_norm = (Color.red(pixel) / 127.5f) - 1.0f
            buffer.put(r_norm * maskInvF[i]) // R_masked
        }
        // Channel 2: Green
        for (i in 0 until totalPixels) {
            val pixel = imagePixels[i]
            val g_norm = (Color.green(pixel) / 127.5f) - 1.0f
            buffer.put(g_norm * maskInvF[i]) // G_masked
        }
        // Channel 3: Blue
        for (i in 0 until totalPixels) {
            val pixel = imagePixels[i]
            val b_norm = (Color.blue(pixel) / 127.5f) - 1.0f
            buffer.put(b_norm * maskInvF[i]) // B_masked
        }

        // --- Channel 4 (Mask) ---
        buffer.put(maskF)

        // --- Channel 5 (Ones) ---
        // This is a fast way to fill a segment of the buffer
        val ones = FloatArray(totalPixels) { 1.0f }
        buffer.put(ones)

        buffer.rewind()
        return buffer
    }

    /**
     * Converts the output tensor [1, 3, H, W] (normalized [-1, 1]) to a Bitmap.
     */
    private fun tensorToBitmap(
        tensorData: Array<*>,
        width: Int,
        height: Int
    ): Bitmap {
        // [1, 3, H, W] -> get [3, H, W]
        val tensor3d = (tensorData[0] as Array<*>)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // Extract channels (CHW format)
        val rChannel = tensor3d[0] as Array<*> // [H, W]
        val gChannel = tensor3d[1] as Array<*> // [H, W]
        val bChannel = tensor3d[2] as Array<*> // [H, W]

        for (y in 0 until height) {
            val rowR = rChannel[y] as FloatArray
            val rowG = gChannel[y] as FloatArray
            val rowB = bChannel[y] as FloatArray

            for (x in 0 until width) {
                val idx = y * width + x

                // De-normalize from [-1, 1] to [0, 255]
                val r = ((rowR[x] + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                val g = ((rowG[x] + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                val b = ((rowB[x] + 1.0f) * 127.5f).toInt().coerceIn(0, 255)

                pixels[idx] = Color.argb(255, r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}