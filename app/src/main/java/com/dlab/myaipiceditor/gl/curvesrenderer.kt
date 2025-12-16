package com.dlab.myaipiceditor.gl

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.dlab.myaipiceditor.gl.utils.GLProgram
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CurvesRenderer(private val bitmap: Bitmap?) : GLSurfaceView.Renderer {

    private lateinit var program: GLProgram
    private var textureId = 0
    private var lutTextureId = 0
    private var maskTextureId = 0
    private var isInitialized = false

    // For thread-safe updates
    private var pendingLut: FloatArray? = null
    private var pendingMaskUpdate: MaskUpdate? = null

    // Reusable objects
    private var lutBitmap: Bitmap? = null
    private val lutPixels = IntArray(256)

    // Mask for erase operations
    private var maskBitmap: Bitmap? = null
    private var maskWidth = 0
    private var maskHeight = 0

    data class MaskUpdate(
        val points: List<Offset>,
        val brushSize: Float,
        val opacity: Float,
        val hardness: Float,
        val isErasing: Boolean
    )

    fun setPendingLut(lut: FloatArray) {
        pendingLut = lut
    }

    fun updateMask(points: List<Offset>, brushSize: Float, opacity: Float, hardness: Float, isErasing: Boolean = true) {
        Log.d("CurvesRenderer", "🎯 updateMask: points=${points.size}, size=$brushSize, opacity=$opacity, hardness=$hardness, isErasing=$isErasing")
        pendingMaskUpdate = MaskUpdate(points, brushSize, opacity, hardness, isErasing)
    }

    fun clearMask() {
        maskBitmap?.let { mask ->
            val pixels = IntArray(mask.width * mask.height) { 0xFFFFFFFF.toInt() }
            mask.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
            updateMaskTexture()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {

        GLES20.glClearColor(0.26f, 0.26f, 0.26f, 1f)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_TEXTURE_2D)

        try {
            program = GLProgram(vertexShaderCode, fragmentShaderCode)
            if (bitmap != null && !bitmap.isRecycled) {
                textureId = loadTexture(bitmap)

                lutBitmap = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
                lutTextureId = createLUTTexture(lutBitmap!!)
                Log.d("CurvesRenderer", "🎨 Created LUT texture: $lutTextureId")

                maskWidth = bitmap.width
                maskHeight = bitmap.height
                maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                val whiteMask = IntArray(maskWidth * maskHeight) { 0xFFFFFFFF.toInt() }
                maskBitmap!!.setPixels(whiteMask, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                maskTextureId = createMaskTexture(maskBitmap!!)

                val identityLUT = FloatArray(256 * 3) { i ->
                    val pixelIndex = i / 3
                    pixelIndex / 255f
                }

                updateCurve(identityLUT)
                isInitialized = true
            }
        } catch (e: Exception) {
            Log.e("CurvesRenderer", "❌ Error in onSurfaceCreated", e)
            e.printStackTrace()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!isInitialized || textureId == 0 || lutTextureId == 0 || maskTextureId == 0) {
            return
        }

        pendingLut?.let { newLut ->
            updateCurve(newLut)
            pendingLut = null
        }

        pendingMaskUpdate?.let { update ->
            applyMaskUpdate(update)
            pendingMaskUpdate = null
        }

        try {
            program.use()
            program.setTexture("uTexture", textureId, 0)
            program.setTexture("uCurveTex", lutTextureId, 1)
            program.setTexture("uMaskTex", maskTextureId, 2)
            drawFullScreenQuad()
        } catch (e: Exception) {
            Log.e("CurvesRenderer", "❌ Error in onDrawFrame", e)
            e.printStackTrace()
        }
    }

    private fun applyMaskUpdate(update: MaskUpdate) {
        maskBitmap?.let { mask ->
            // 🔍 DIAGNOSTIC: Check mask BEFORE drawing
            if (update.points.size == 1) { // Only on first stroke to avoid spam
                MaskDiagnostics.analyzeMask(mask, "BEFORE-DRAW (isErasing: ${update.isErasing})")
            }

            val canvas = android.graphics.Canvas(mask)

            val capStyle = if (update.hardness >= 100f) Paint.Cap.SQUARE else Paint.Cap.ROUND
            val joinStyle = if (update.hardness >= 100f) Paint.Join.MITER else Paint.Join.ROUND

            val paint = Paint().apply {
                if (update.hardness >= 100f && update.opacity >= 100f) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                    color = if (update.isErasing) {
                        android.graphics.Color.BLACK // Erase = 0% effect
                    } else {
                        android.graphics.Color.WHITE // Restore = 100% effect
                    }
                } else {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                    val alpha = (update.opacity / 100f * 255f).toInt()
                    color = if (update.isErasing) {
                        android.graphics.Color.argb(alpha, 0, 0, 0) // Erase (paint black)
                    } else {
                        android.graphics.Color.argb(alpha, 255, 255, 255) // Restore (paint white)
                    }
                }

                // Style is set dynamically below
                strokeWidth = update.brushSize
                strokeCap = capStyle
                strokeJoin = joinStyle
                isAntiAlias = update.hardness < 100f
                isDither = false
                isFilterBitmap = false
                maskFilter = null
            }

            if (update.points.size > 1) {
                paint.style = Paint.Style.STROKE // Use STROKE for paths
                val path = android.graphics.Path()
                val first = update.points.first()
                path.moveTo(first.x * maskWidth, first.y * maskHeight)

                for (i in 1 until update.points.size) {
                    val point = update.points[i]
                    path.lineTo(point.x * maskWidth, point.y * maskHeight)
                }

                canvas.drawPath(path, paint)

            } else if (update.points.size == 1) {
                paint.style = Paint.Style.FILL // Use FILL for single points

                val point = update.points.first()
                val centerX = (point.x * maskWidth)
                val centerY = (point.y * maskHeight)

                // 🔥 CRITICAL FIX: Draw a RECT for hard brush, not a CIRCLE
                if (update.hardness >= 100f) {
                    val brushRadius = update.brushSize / 2f
                    canvas.drawRect(
                        centerX - brushRadius,
                        centerY - brushRadius,
                        centerX + brushRadius,
                        centerY + brushRadius,
                        paint
                    )
                } else {
                    canvas.drawCircle(
                        centerX,
                        centerY,
                        update.brushSize / 2f,
                        paint
                    )
                }

                // 🔍 DIAGNOSTIC: Check mask AFTER drawing
                MaskDiagnostics.analyzeMask(mask, "AFTER-DRAW")
                MaskDiagnostics.sampleRegion(mask, centerX.toInt(), centerY.toInt(), 15, "BRUSH-SAMPLE")
            }

            updateMaskTexture()
        }
    }

    private fun updateMaskTexture() {
        maskBitmap?.let { mask ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, mask)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        }
    }

    fun updateCurve(newLut: FloatArray) {
        if (!isInitialized || lutBitmap == null || lutTextureId == 0) {
            return
        }

        try {
            for (i in 0 until 256) {
                val r = (newLut[i * 3] * 255f).toInt().coerceIn(0, 255)
                val g = (newLut[i * 3 + 1] * 255f).toInt().coerceIn(0, 255)
                val b = (newLut[i * 3 + 2] * 255f).toInt().coerceIn(0, 255)
                lutPixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }

            lutBitmap!!.setPixels(lutPixels, 0, 256, 0, 0, 256, 1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, lutBitmap!!)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        } catch (e: Exception) {
            Log.e("CurvesRenderer", "❌ Error in updateCurve", e)
            e.printStackTrace()
        }
    }

    fun getMaskBitmap(): Bitmap? {
        val config = maskBitmap?.config ?: Bitmap.Config.ARGB_8888
        return maskBitmap?.copy(config, false)
    }

    fun setMaskBitmap(newMask: Bitmap) {
        maskBitmap?.let { mask ->
            if (newMask.width == mask.width && newMask.height == mask.height) {
                val pixels = IntArray(mask.width * mask.height)
                newMask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                mask.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                updateMaskTexture()

            }
        }
    }

    // ✅ FIX: New helper to restore mask AND refresh GPU texture
    fun restoreMaskAndUpdate(mask: Bitmap) {
        maskBitmap?.let { current ->
            if (mask.width == current.width && mask.height == current.height) {
                val pixels = IntArray(mask.width * mask.height)
                mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                current.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)

                // ✅ Ensure GPU texture matches restored CPU bitmap
                updateMaskTexture()
            }
        }
    }

    private fun loadTexture(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled) return 0
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        if (texIds[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return texIds[0]
    }

    private fun createLUTTexture(lutBitmap: Bitmap): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        if (texIds[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lutBitmap, 0)
        return texIds[0]
    }

    private fun createMaskTexture(maskBitmap: Bitmap): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        if (texIds[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])

        // 🔥 CRITICAL FIX: Use NEAREST filtering to prevent GPU blur on mask
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // (These lines are correct)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, maskBitmap, 0)

        return texIds[0]
    }

    private fun drawFullScreenQuad() {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f,
            1f, -1f, 0f, 1f, 1f,
            -1f, 1f, 0f, 0f, 0f,
            1f, 1f, 0f, 1f, 0f
        )
        val buffer = GLProgram.floatBuffer(vertices)
        program.setVertexAttrib("aPosition", buffer, 3, 5 * 4, 0)
        program.setVertexAttrib("aTexCoord", buffer, 2, 5 * 4, 3 * 4)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // In CurvesRenderer.kt

    companion object {
        private const val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 🔥 CRITICAL FIX: Use step() function for hard-edge threshold
        private const val fragmentShaderCode = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform sampler2D uCurveTex;
            uniform sampler2D uMaskTex;
            varying vec2 vTexCoord;
            void main() {
                vec4 originalColor = texture2D(uTexture, vTexCoord);
                vec4 maskValue = texture2D(uMaskTex, vTexCoord);
                
                // Apply curves
                vec4 curvedColor = originalColor;
                curvedColor.r = texture2D(uCurveTex, vec2(originalColor.r, 0.0)).r;
                curvedColor.g = texture2D(uCurveTex, vec2(originalColor.g, 0.0)).g;
                
                // ✅✅✅ TYPO FIX HERE ✅✅✅
                curvedColor.b = texture2D(uCurveTex, vec2(originalColor.b, 0.0)).b;
                
                // 🔥 CRITICAL FIX: Use step() for hard threshold instead of smooth mix()
                // If mask value > 0.5 (closer to white), show curves. Otherwise show original.
                float maskAlpha = step(0.5, maskValue.r);
                
                // Use mix with the new binary maskAlpha (0.0 or 1.0 only)
                gl_FragColor = vec4(mix(originalColor.rgb, curvedColor.rgb, maskAlpha), originalColor.a);
            }
        """
    }
}