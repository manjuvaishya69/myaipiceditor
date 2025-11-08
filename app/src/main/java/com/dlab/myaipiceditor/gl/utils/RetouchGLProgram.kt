package com.dlab.myaipiceditor.gl.utils

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class RetouchGLProgram(vertexShaderCode: String, fragmentShaderCode: String) {

    private var programId: Int = 0
    private val attributeLocations = mutableMapOf<String, Int>()
    private val uniformLocations = mutableMapOf<String, Int>()

    init {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("Error linking program: $error")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling shader: $error")
        }

        return shader
    }

    fun use() {
        GLES20.glUseProgram(programId)
    }

    fun getAttribLocation(name: String): Int {
        return attributeLocations.getOrPut(name) {
            GLES20.glGetAttribLocation(programId, name)
        }
    }

    fun getUniformLocation(name: String): Int {
        return uniformLocations.getOrPut(name) {
            GLES20.glGetUniformLocation(programId, name)
        }
    }

    fun setVertexAttrib(name: String, buffer: FloatBuffer, size: Int, stride: Int, offset: Int) {
        val location = getAttribLocation(name)
        if (location >= 0) {
            buffer.position(offset / 4)
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, stride, buffer)
        }
    }

    fun setTexture(name: String, textureId: Int, unit: Int) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(location, unit)
        }
    }

    fun setUniform(name: String, value: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES20.glUniform1f(location, value)
        }
    }

    fun setUniform(name: String, x: Float, y: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES20.glUniform2f(location, x, y)
        }
    }

    fun setUniform(name: String, x: Float, y: Float, z: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES20.glUniform3f(location, x, y, z)
        }
    }

    fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float) {
        val location = getUniformLocation(name)
        if (location >= 0) {
            GLES20.glUniform4f(location, x, y, z, w)
        }
    }

    fun cleanup() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    companion object {
        fun floatBuffer(data: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
                .position(0) as FloatBuffer
        }
    }
}