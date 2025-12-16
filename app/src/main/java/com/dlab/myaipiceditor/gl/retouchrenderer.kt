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

    // Framebuffer
    private var fboId = 0

    @Volatile
    private var isInitialized = false
    private var currentTool: RetouchTool = RetouchTool.NONE

    // Working bitmap for modifications (kept in sync with currentTextureId)
    private var workingBitmap: Bitmap? = null

    // Surface dimensions
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1f)
        GLES20.glDisable(GLES20.GL_BLEND)

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

            // Initialize textures and FBO if we have an initial bitmap
            bitmap?.let { bmp ->
                val width = bmp.width
                val height = bmp.height

                textureA = createTexture(width, height)
                textureB = createTexture(width, height)

                // Load initial image into BOTH textures
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

                currentTextureId = textureA
                workingBitmap = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
            }

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
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Ensure renderer initialized and we have a valid texture to draw
        if (!isInitialized || currentTextureId == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        passThroughProgram.use()
        // Use setTexture to bind the current texture
        passThroughProgram.setTexture("uTexture", currentTextureId, 0)

        // When drawing to the screen (default framebuffer) we should NOT flip Y
        drawFullScreenQuad(passThroughProgram, flipY = false)
    }

    /**
     * Set or replace the working bitmap. This must be called from the GL thread (e.g. via
     * GLSurfaceView.queueEvent { renderer.setBitmap(newBmp) }) to avoid context issues.
     */
    fun setBitmap(newBitmap: Bitmap) {
        // Must be called in GL thread and after initialization
        if (!isInitialized || fboId == 0) return

        try {
            workingBitmap?.recycle()
            workingBitmap = newBitmap.copy(newBitmap.config ?: Bitmap.Config.ARGB_8888, true)

            // Update both textures so ping-pong starts from the same image
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, workingBitmap, 0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, workingBitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            currentTextureId = textureA
        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error in setBitmap", e)
        }
    }

    /**
     * Public API to apply a stroke. This function assumes it is executed on the GL thread.
     * Call it via GLSurfaceView.queueEvent { renderer.applyRetouchStroke(...) } from UI.
     */
    fun applyRetouchStroke(
        stroke: List<Offset>,
        brush: RetouchBrush,
        tool: RetouchTool
    ) {
        // Return early if not ready or no stroke
        if (!isInitialized || workingBitmap == null || stroke.isEmpty() || fboId == 0) return

        try {
            val width = workingBitmap!!.width
            val height = workingBitmap!!.height

            val program = when (tool) {
                RetouchTool.BLEMISH -> blemishProgram
                RetouchTool.SMOOTH -> smoothProgram
                RetouchTool.SKIN_TONE -> skinToneProgram
                RetouchTool.WRINKLE -> wrinkleProgram
                RetouchTool.TEETH_WHITENING -> teethProgram
                else -> return
            }

            // Set common uniforms once per stroke (can be changed per-point if needed)
            program.use()
            program.setUniform("uBrushRadius", brush.size / width.toFloat())
            program.setUniform("uBrushStrength", brush.strength)
            program.setUniform("uBrushHardness", brush.hardness)
            program.setUniform("uResolution", width.toFloat(), height.toFloat())

            stroke.forEach { point ->
                // Ping-pong: input is currentTextureId, output is the other texture
                val inputTexture = currentTextureId
                val outputTexture = if (currentTextureId == textureA) textureB else textureA

                // Bind FBO and attach output texture
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    outputTexture,
                    0
                )

                // Render at full bitmap resolution so shader coords match
                GLES20.glViewport(0, 0, width, height)

                // Bind input texture
                program.setTexture("uTexture", inputTexture, 0)

                
                program.setUniform("uBrushCenter", point.x, point.y)

                // IMPORTANT: When rendering into an FBO that was created with GL TexImage2D,
                // the Y orientation is often inverted relative to the screen. To keep the
                // visual orientation constant across ping-pong swaps, we flip Y when
                // rendering into the offscreen FBO.
                drawFullScreenQuad(program, flipY = true)

                // Swap current texture (ping-pong)
                currentTextureId = outputTexture
            }

            // Unbind FBO and restore viewport to screen size
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)

        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error applying retouch stroke", e)
        }
    }

    private fun updateWorkingBitmapFromTexture() {
        if (workingBitmap == null || !isInitialized || currentTextureId == 0 || fboId == 0) return

        try {
            val width = workingBitmap!!.width
            val height = workingBitmap!!.height
            val savedViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, currentTextureId, 0)

            val buffer = ByteBuffer.allocateDirect(width * height * 4)
            buffer.order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])

            buffer.rewind()
            workingBitmap?.copyPixelsFromBuffer(buffer)

        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error updating working bitmap", e)
        }
    }

    /**
     * Draws a fullscreen quad using the provided program.
     * flipY == true flips the texture coordinate Y (useful when rendering to FBO).
     */
    private fun drawFullScreenQuad(program: RetouchGLProgram, flipY: Boolean) {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, if (flipY) 0f else 1f,
            1f, -1f, 0f, 1f, if (flipY) 0f else 1f,
            -1f,  1f, 0f, 0f, if (flipY) 1f else 0f,
            1f,  1f, 0f, 1f, if (flipY) 1f else 0f
        )
        val buffer = RetouchGLProgram.floatBuffer(vertices)

        // Provide vertex attributes
        program.setVertexAttrib("aPosition", buffer, 3, 5 * 4, 0)
        program.setVertexAttrib("aTexCoord", buffer, 2, 5 * 4, 3 * 4)

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex attribute arrays to avoid leaking state between programs
        val posLoc = program.getAttribLocation("aPosition")
        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        val texLoc = program.getAttribLocation("aTexCoord")
        if (texLoc >= 0) GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun createTexture(width: Int, height: Int): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        if (texIds[0] == 0) return 0

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

        return texIds[0]
    }

    fun getCurrentBitmap(): Bitmap? {
        // Ensure this is called from GL thread or caller knows it's safe; otherwise the readpixels may fail.
        updateWorkingBitmapFromTexture()
        return workingBitmap?.copy(workingBitmap?.config ?: Bitmap.Config.ARGB_8888, false)
    }

    fun cleanup() {
        try {
            if (textureA != 0 || textureB != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(textureA, textureB), 0)
            }
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            }
        } catch (e: Exception) {
            Log.e("RetouchRenderer", "Error deleting GL resources", e)
        }

        passThroughProgram.cleanup()
        blemishProgram.cleanup()
        smoothProgram.cleanup()
        skinToneProgram.cleanup()
        wrinkleProgram.cleanup()
        teethProgram.cleanup()

        workingBitmap?.recycle()
        workingBitmap = null
        isInitialized = false
    }
}
