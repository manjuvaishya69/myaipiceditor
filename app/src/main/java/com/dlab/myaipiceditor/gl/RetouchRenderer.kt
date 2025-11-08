package com.dlab.myaipiceditor.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.dlab.myaipiceditor.gl.utils.RetouchGLProgram
import com.dlab.myaipiceditor.ui.RetouchTool
import com.dlab.myaipiceditor.ui.RetouchBrush
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class RetouchRenderer(private var bitmap: Bitmap?) : GLSurfaceView.Renderer {

    // Shader Programs
    private lateinit var passThroughProgram: RetouchGLProgram
    private lateinit var blemishProgram: RetouchGLProgram
    private lateinit var smoothProgram: RetouchGLProgram
    private lateinit var skinToneProgram: RetouchGLProgram
    private lateinit var wrinkleProgram: RetouchGLProgram
    private lateinit var teethProgram: RetouchGLProgram

    // Textures for double-buffering (ping-pong)
    private var textureA = 0
    private var textureB = 0
    // Holds the ID of the texture that currently contains the latest image state
    private var currentTextureId = 0

    // Framebuffers
    private var fboId = 0

    private var isInitialized = false
    private var currentTool: RetouchTool = RetouchTool.NONE

    // Working bitmap for modifications (kept in sync with currentTextureId)
    private var workingBitmap: Bitmap? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.26f, 0.26f, 0.26f, 1f)
        GLES20.glDisable(GLES20.GL_BLEND) // Important: Disable blending for FBO operations

        try {
            // Compile and link shaders
            passThroughProgram = RetouchGLProgram(
                RetouchShaders.VERTEX_SHADER,
                RetouchShaders.FRAGMENT_SHADER_PASSTHROUGH
            )

            blemishProgram = RetouchGLProgram(
                RetouchShaders.VERTEX_SHADER,
                RetouchShaders.FRAGMENT_SHADER_BLEMISH
            )

            smoothProgram = RetouchGLProgram(
                RetouchShaders.VERTEX_SHADER,
                RetouchShaders.FRAGMENT_SHADER_SMOOTH
            )

            skinToneProgram = RetouchGLProgram(
                RetouchShaders.VERTEX_SHADER,
                RetouchShaders.FRAGMENT_SHADER_SKIN_TONE
            )

            wrinkleProgram = RetouchGLProgram(
                RetouchShaders.VERTEX_SHADER,
                RetouchShaders.FRAGMENT_SHADER_WRINKLE
            )

            teethProgram = RetouchGLProgram(
                RetouchShaders.VERTEX_SHADER,
                RetouchShaders.FRAGMENT_SHADER_TEETH
            )

            // Initialize textures and FBO
            bitmap?.let { bmp ->
                val width = bmp.width
                val height = bmp.height

                // Create two identical textures for ping-ponging
                textureA = createTexture(width, height)
                textureB = createTexture(width, height)

                // Load initial image into Texture A
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

                // Also need to load it into Texture B to ensure it's initialized correctly
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

                currentTextureId = textureA
                workingBitmap = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
            }

            // Create framebuffer
            val fboIds = IntArray(1)
            GLES20.glGenFramebuffers(1, fboIds, 0)
            fboId = fboIds[0]

            isInitialized = true
        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error in onSurfaceCreated", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear to a dark background color so we can see the image clearly
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (!isInitialized || currentTextureId == 0) return

        // Simply draw the current state texture to the screen
        passThroughProgram.use()

        // Important: Set active texture unit before binding
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTextureId)
        passThroughProgram.setUniform("uTexture", 0f) // Tell shader to use texture unit 0

        drawFullScreenQuad(passThroughProgram)
    }

    // --- Public Methods (called from RetouchScreen.kt) ---

    fun setTool(tool: RetouchTool) {
        currentTool = tool
    }

    fun setBitmap(newBitmap: Bitmap) {
        if (!isInitialized) return

        workingBitmap?.recycle()
        workingBitmap = newBitmap.copy(newBitmap.config ?: Bitmap.Config.ARGB_8888, true)

        // Load new bitmap into BOTH textures to reset state
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, workingBitmap, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, workingBitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        currentTextureId = textureA
    }

    fun getCurrentBitmap(): Bitmap? {
        // Ensure workingBitmap is up-to-date before returning it
        updateWorkingBitmapFromTexture()
        return workingBitmap?.copy(workingBitmap?.config ?: Bitmap.Config.ARGB_8888, false)
    }

    fun applyRetouchStroke(
        stroke: List<Offset>,
        brush: RetouchBrush,
        tool: RetouchTool
    ) {
        if (!isInitialized || workingBitmap == null || stroke.isEmpty()) return

        try {
            val width = workingBitmap!!.width
            val height = workingBitmap!!.height

            // Select appropriate shader
            val program = when (tool) {
                RetouchTool.BLEMISH -> blemishProgram
                RetouchTool.SMOOTH -> smoothProgram
                RetouchTool.SKIN_TONE -> skinToneProgram
                RetouchTool.WRINKLE -> wrinkleProgram
                RetouchTool.TEETH_WHITENING -> teethProgram
                else -> return
            }

            program.use()
            program.setUniform("uBrushRadius", brush.size / width.toFloat())
            program.setUniform("uBrushStrength", brush.strength)
            program.setUniform("uBrushHardness", brush.hardness)
            program.setUniform("uResolution", width.toFloat(), height.toFloat())

            // For each point in the stroke, perform a ping-pong draw
            stroke.forEach { point ->
                // Determine which texture is input (read) and which is output (write)
                val inputTexture = currentTextureId
                val outputTexture = if (currentTextureId == textureA) textureB else textureA

                // 1. Bind FBO and attach output texture
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    outputTexture,
                    0
                )

                // Verify FBO is complete
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    Log.e("RetouchRenderer", "Framebuffer incomplete: 0x${Integer.toHexString(status)}")
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    return
                }

                // Set viewport to match texture size (crucial!)
                GLES20.glViewport(0, 0, width, height)

                // 2. Bind input texture for reading
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
                program.setUniform("uTexture", 0f)

                // 3. Set brush position uniform
                program.setUniform("uBrushCenter", point.x, point.y)

                // 4. Draw!
                drawFullScreenQuad(program)

                // 5. Swap! The output is now the latest state.
                currentTextureId = outputTexture
            }

            // Unbind FBO to return to default framebuffer (screen)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            // Important: Reset viewport to surface size for next onDrawFrame
            // We don't have surface dimensions here, so we'll rely on onDrawFrame not needing it immediately
            // or it being set in onSurfaceChanged.

            // Optionally sync to CPU bitmap if needed immediately (can be slow, do sparingly)
            // updateWorkingBitmapFromTexture()

        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error applying retouch stroke", e)
        }
    }

    // --- Private Helper Functions ---

    private fun updateWorkingBitmapFromTexture() {
        if (workingBitmap == null || !isInitialized) return

        try {
            val width = workingBitmap!!.width
            val height = workingBitmap!!.height

            // Bind the FBO and attach current texture to read from it
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, currentTextureId, 0)

            val buffer = ByteBuffer.allocateDirect(width * height * 4)
            buffer.order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            buffer.rewind()
            workingBitmap?.copyPixelsFromBuffer(buffer)

        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error updating working bitmap", e)
        }
    }

    private fun drawFullScreenQuad(program: RetouchGLProgram) {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f,
            1f, -1f, 0f, 1f, 1f,
            -1f,  1f, 0f, 0f, 0f,
            1f,  1f, 0f, 1f, 0f
        )
        val buffer = RetouchGLProgram.floatBuffer(vertices)

        program.setVertexAttrib("aPosition", buffer, 3, 5 * 4, 0)
        program.setVertexAttrib("aTexCoord", buffer, 2, 5 * 4, 3 * 4)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createTexture(width: Int, height: Int): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        if (texIds[0] == 0) return 0

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])

        // Set filtering
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Allocate texture memory without data
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

        return texIds[0]
    }

    fun cleanup() {
        if (textureA != 0 || textureB != 0) {
            val textures = intArrayOf(textureA, textureB)
            GLES20.glDeleteTextures(2, textures, 0)
        }

        if (fboId != 0) {
            val fbos = intArrayOf(fboId)
            GLES20.glDeleteFramebuffers(1, fbos, 0)
        }

        workingBitmap?.recycle()
        workingBitmap = null
    }
}