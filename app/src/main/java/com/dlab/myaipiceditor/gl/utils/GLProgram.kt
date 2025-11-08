package com.dlab.myaipiceditor.gl.utils

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GLProgram(vertexCode: String, fragmentCode: String) {
    private val program: Int

    init {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun use() = GLES20.glUseProgram(program)

    fun setTexture(name: String, texId: Int, unit: Int) {
        val handle = GLES20.glGetUniformLocation(program, name)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(handle, unit)
    }

    fun setVertexAttrib(name: String, buffer: FloatBuffer, size: Int, stride: Int, offset: Int) {
        val handle = GLES20.glGetAttribLocation(program, name)
        buffer.position(offset / 4)
        GLES20.glEnableVertexAttribArray(handle)
        GLES20.glVertexAttribPointer(handle, size, GLES20.GL_FLOAT, false, stride, buffer)
    }

    companion object {
        fun loadShader(type: Int, code: String): Int {
            return GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, code)
                GLES20.glCompileShader(it)
            }
        }

        fun floatBuffer(array: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(array.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(array).position(0) }
    }
}
