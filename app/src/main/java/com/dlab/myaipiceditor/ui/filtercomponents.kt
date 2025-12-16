package com.dlab.myaipiceditor.ui

import android.app.Application
import android.graphics.*
import android.opengl.*
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlab.myaipiceditor.data.EraserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.BufferedReader
import java.nio.*

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

private fun FloatArray.toBuffer(): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer()
    fb.put(this).position(0)
    return fb
}

// ============================================================================
// DATA CLASSES
// ============================================================================

data class Filter(
    val name: String,
    val category: String,
    val shaderCode: String
)

data class EraserStroke(
    val points: List<Offset>,
    val size: Float,
    val isRestore: Boolean = false
)

data class EraserState(
    val isErasing: Boolean = false,
    val strokes: List<EraserStroke> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val settings: EraserSettings = EraserSettings.DEFAULT,
    val showSettings: Boolean = false
)

// ============================================================================
// VIEW MODEL
// ============================================================================

class FiltersViewModel(app: Application) : AndroidViewModel(app) {

    // State Flows
    private val _filters = MutableStateFlow<Map<String, List<Filter>>>(emptyMap())
    val filters: StateFlow<Map<String, List<Filter>>> = _filters

    private val _selectedFilter = MutableStateFlow<Filter?>(null)
    val selectedFilter: StateFlow<Filter?> = _selectedFilter

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap

    private val _eraserState = MutableStateFlow(EraserState())
    val eraserState: StateFlow<EraserState> = _eraserState

    private val _livePreview = MutableStateFlow<Bitmap?>(null)
    val livePreview: StateFlow<Bitmap?> = _livePreview

    private val _tempStrokeBitmap = MutableStateFlow<Bitmap?>(null)
    val tempStrokeBitmap: StateFlow<Bitmap?> = _tempStrokeBitmap

    // Eraser State & History
    private val strokeHistory = mutableListOf<List<EraserStroke>>()
    private var strokeHistoryIndex = -1

    // Mask Canvas for CPU Drawing
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val maskPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    // Temp Stroke Canvas for Instant Preview
    private var tempStrokeCanvas: Canvas? = null
    private val tempStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    // Current Stroke Tracking
    private var currentStroke: MutableList<Offset>? = null
    private var lastStrokePoint: Offset? = null

    // GPU Rendering
    private var blendRenderer: GpuBlendRenderer? = null
    private var originalBitmap: Bitmap? = null
    private var filteredBitmap: Bitmap? = null

    // Single-threaded GPU executor for EGL context safety
    private val gpuExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gpuThread = gpuExecutor.asCoroutineDispatcher()

    // Throttling
    private var updateJob: Job? = null
    private var lastGpuUpdateTime = 0L
    private val gpuUpdateThrottleMs = 80 // ~12fps throttle (1000ms / 80ms)

    init {
        loadFilters()
        strokeHistory.add(emptyList())
        strokeHistoryIndex = 0
    }

    // ------------------------------------------------------------------------
    // FILTER LOADING
    // ------------------------------------------------------------------------

    private fun loadFilters() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>().applicationContext
            val result = mutableMapOf<String, MutableList<Filter>>()
            try {
                val categories = ctx.assets.list("glslfilters") ?: emptyArray()
                for (category in categories) {
                    val folder = "glslfilters/$category"
                    val files = ctx.assets.list(folder)?.filter { it.endsWith(".glsl") } ?: continue
                    for (file in files) {
                        val code = ctx.assets.open("$folder/$file")
                            .bufferedReader().use(BufferedReader::readText)
                        val filter = Filter(
                            name = file.removeSuffix(".glsl").uppercase(),
                            category = category.uppercase(),
                            shaderCode = code
                        )
                        result.getOrPut(category.uppercase()) { mutableListOf() }.add(filter)
                    }
                }
                _filters.value = result
                Log.d("FiltersViewModel", "Loaded filters: ${result.size} categories")
            } catch (e: Exception) {
                Log.e("FiltersViewModel", "Error loading filters", e)
            }
        }
    }

    // ------------------------------------------------------------------------
    // FILTER SELECTION & APPLICATION
    // ------------------------------------------------------------------------

    fun selectFilter(filter: Filter, original: Bitmap) {
        originalBitmap = original
        _selectedFilter.value = filter

        viewModelScope.launch(gpuThread) {
            blendRenderer?.release()
            blendRenderer = null
        }

        applyFilter(original, filter)
        resetEraser()
    }

    private fun applyFilter(original: Bitmap, filter: Filter) {
        viewModelScope.launch(gpuThread) {
            try {
                val processed = FilterProcessor.process(original, filter)
                filteredBitmap = processed
                _previewBitmap.value = processed

                // Initialize Mask Bitmap
                maskBitmap?.recycle()
                maskBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
                maskCanvas = Canvas(maskBitmap!!)
                maskCanvas?.drawColor(android.graphics.Color.BLACK)

                // Initialize Temp Stroke Bitmap
                _tempStrokeBitmap.value?.recycle()
                _tempStrokeBitmap.value = Bitmap.createBitmap(
                    original.width,
                    original.height,
                    Bitmap.Config.ARGB_8888
                )
                tempStrokeCanvas = Canvas(_tempStrokeBitmap.value!!)

                // Initialize GPU Renderer
                blendRenderer = GpuBlendRenderer().apply {
                    init(processed, original)
                }

            } catch (e: Exception) {
                Log.e("FiltersViewModel", "Failed applying filter: ${filter.name}", e)
            }
        }
    }

    // ------------------------------------------------------------------------
    // ERASER MODE CONTROLS
    // ------------------------------------------------------------------------

    fun toggleEraseMode() {
        _eraserState.value = _eraserState.value.copy(
            isErasing = !_eraserState.value.isErasing,
            showSettings = false
        )
        if (!_eraserState.value.isErasing) {
            _livePreview.value = null
            tempStrokeCanvas?.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            _tempStrokeBitmap.value = _tempStrokeBitmap.value
        }
    }

    fun toggleEraserSettings() {
        _eraserState.value = _eraserState.value.copy(
            showSettings = !_eraserState.value.showSettings
        )
    }

    fun updateEraserSettings(settings: EraserSettings) {
        _eraserState.value = _eraserState.value.copy(settings = settings)
    }

    // ------------------------------------------------------------------------
    // STROKE DRAWING (OPTIMIZED FOR SMOOTH PERFORMANCE)
    // ------------------------------------------------------------------------

    fun startStroke(point: Offset, isRestore: Boolean) {
        val size = _eraserState.value.settings.size

        // 1. Clear temp stroke bitmap (Main Thread)
        tempStrokeCanvas?.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 2. Configure paints (Main Thread)
        maskPaint.color = if (isRestore) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        maskPaint.strokeWidth = size * 2f

        tempStrokePaint.color = if (isRestore) {
            android.graphics.Color.argb(180, 33, 150, 243) // Blue for restore
        } else {
            android.graphics.Color.argb(180, 255, 255, 255) // White for erase
        }
        tempStrokePaint.strokeWidth = size * 2f

        // 3. Start tracking stroke (Main Thread)
        currentStroke = mutableListOf(point)
        lastStrokePoint = point

        // 4. Instant UI Feedback (Main Thread - SUPER SMOOTH)
        tempStrokeCanvas?.drawCircle(point.x, point.y, tempStrokePaint.strokeWidth / 2f, tempStrokePaint.apply {
            style = Paint.Style.FILL
        })
        tempStrokePaint.style = Paint.Style.STROKE // Reset temp paint
        _tempStrokeBitmap.value = _tempStrokeBitmap.value

        // 5. Update permanent mask (off-thread) and trigger throttled GPU blend (LIVE)
        viewModelScope.launch(Dispatchers.Default) {
            // We need to apply the FILL style for the dot on the mask, then reset
            maskPaint.style = Paint.Style.FILL
            maskCanvas?.drawCircle(point.x, point.y, maskPaint.strokeWidth / 2f, maskPaint)
            maskPaint.style = Paint.Style.STROKE

            // Trigger the throttled update
            updateLivePreview()
        }
    }

    fun continueStroke(point: Offset) {
        val lastPoint = lastStrokePoint ?: return
        val stroke = currentStroke ?: return

        // 1. Instant UI Feedback (Main Thread - SUPER SMOOTH)
        tempStrokeCanvas?.drawLine(lastPoint.x, lastPoint.y, point.x, point.y, tempStrokePaint)
        _tempStrokeBitmap.value = _tempStrokeBitmap.value // Trigger UI update

        // 2. Update tracking
        stroke.add(point)
        lastStrokePoint = point

        // 3. Update permanent mask (off-thread) and trigger throttled GPU blend (LIVE)
        viewModelScope.launch(Dispatchers.Default) {
            // maskPaint.style should be STROKE from startStroke's reset
            maskCanvas?.drawLine(lastPoint.x, lastPoint.y, point.x, point.y, maskPaint)
            // Trigger the throttled update
            updateLivePreview()
        }
    }

    fun finishStroke() {
        val strokePoints = currentStroke ?: return
        val lastPoint = lastStrokePoint ?: return
        val settings = _eraserState.value.settings

        // Ensure at least 2 points
        if (strokePoints.size == 1) {
            strokePoints.add(lastPoint)
        }

        // Create stroke record
        val newStroke = EraserStroke(
            points = strokePoints,
            size = settings.size,
            isRestore = (maskPaint.color == android.graphics.Color.BLACK)
        )

        val newStrokes = _eraserState.value.strokes + newStroke

        // Clear redo history
        while (strokeHistory.size > strokeHistoryIndex + 1) {
            strokeHistory.removeAt(strokeHistory.size - 1)
        }

        // Add to history
        strokeHistory.add(newStrokes)
        strokeHistoryIndex = strokeHistory.size - 1

        // Limit history size
        if (strokeHistory.size > 50) {
            strokeHistory.removeAt(0)
            strokeHistoryIndex--
        }

        // Update state
        _eraserState.value = _eraserState.value.copy(
            strokes = newStrokes,
            canUndo = strokeHistoryIndex > 0,
            canRedo = false
        )

        // Clear tracking
        currentStroke = null
        lastStrokePoint = null

        // Clear temp stroke
        tempStrokeCanvas?.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        _tempStrokeBitmap.value = _tempStrokeBitmap.value

        // NOW do the GPU blend (only once, at the end)
        updateLivePreview(forceGpu = true)
    }

    // ------------------------------------------------------------------------
    // UNDO/REDO
    // ------------------------------------------------------------------------

    fun undoStroke() {
        if (strokeHistoryIndex > 0) {
            strokeHistoryIndex--
            val strokes = strokeHistory[strokeHistoryIndex]
            _eraserState.value = _eraserState.value.copy(
                strokes = strokes,
                canUndo = strokeHistoryIndex > 0,
                canRedo = strokeHistoryIndex < strokeHistory.size - 1
            )
            // Rebuild and force update on background thread
            viewModelScope.launch(Dispatchers.Default) {
                rebuildMask(strokes)
                updateLivePreview(forceGpu = true)
            }
        }
    }

    fun redoStroke() {
        if (strokeHistoryIndex < strokeHistory.size - 1) {
            strokeHistoryIndex++
            val strokes = strokeHistory[strokeHistoryIndex]
            _eraserState.value = _eraserState.value.copy(
                strokes = strokes,
                canUndo = strokeHistoryIndex > 0,
                canRedo = strokeHistoryIndex < strokeHistory.size - 1
            )
            // Rebuild and force update on background thread
            viewModelScope.launch(Dispatchers.Default) {
                rebuildMask(strokes)
                updateLivePreview(forceGpu = true)
            }
        }
    }

    fun resetEraser() {
        strokeHistory.clear()
        strokeHistory.add(emptyList())
        strokeHistoryIndex = 0
        _eraserState.value = EraserState()

        maskCanvas?.drawColor(android.graphics.Color.BLACK)
        tempStrokeCanvas?.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        _livePreview.value = null
        _tempStrokeBitmap.value = _tempStrokeBitmap.value
    }

    // ------------------------------------------------------------------------
    // GPU BLENDING (Only called when needed)
    // ------------------------------------------------------------------------

    private fun updateLivePreview(forceGpu: Boolean = false) {
        val now = System.currentTimeMillis()

        // Allow 'forceGpu' to bypass throttle (for finishStroke, undo, etc.)
        if (!forceGpu) {
            // Throttle: Check if enough time has passed
            if (now - lastGpuUpdateTime < gpuUpdateThrottleMs) {
                return // Throttled
            }
            // Debounce: If a job is already running, skip
            if (updateJob?.isActive == true) {
                return
            }
        }

        // If 'forceGpu' is true, we cancel any pending job to run this one NOW.
        if (forceGpu && updateJob?.isActive == true) {
            updateJob?.cancel()
        }

        val mask = maskBitmap ?: return
        val renderer = blendRenderer ?: return

        lastGpuUpdateTime = now
        updateJob = viewModelScope.launch(gpuThread) {
            try {
                val blended = renderer.applyBlend(mask)
                _livePreview.value = blended
            } catch (e: Exception) {
                Log.e("FiltersViewModel", "Failed to update live preview", e)
            }
        }
    }

    private fun drawStrokeToMask(stroke: EraserStroke) {
        val canvas = maskCanvas ?: return

        // Configure paint for this specific stroke
        maskPaint.color = if (stroke.isRestore) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        maskPaint.strokeWidth = stroke.size * 2f

        if (stroke.points.isNotEmpty()) {
            val firstPoint = stroke.points.first()

            // Check if it's a dot (size 1, or 2 identical points from finishStroke)
            if (stroke.points.size == 1 || (stroke.points.size == 2 && stroke.points.last() == firstPoint)) {
                maskPaint.style = Paint.Style.FILL
                canvas.drawCircle(firstPoint.x, firstPoint.y, maskPaint.strokeWidth / 2f, maskPaint)
            }
            // It's a line
            else if (stroke.points.size > 1) {
                maskPaint.style = Paint.Style.STROKE
                val path = Path()
                path.moveTo(firstPoint.x, firstPoint.y)

                for (i in 1 until stroke.points.size) {
                    val point = stroke.points[i]
                    path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, maskPaint)
            }
        }
        // Reset paint style
        maskPaint.style = Paint.Style.STROKE
    }

    private fun rebuildMask(strokes: List<EraserStroke>) {
        val canvas = maskCanvas ?: return
        canvas.drawColor(android.graphics.Color.BLACK)

        // Use the new, bug-fixed drawing logic for each stroke
        strokes.forEach { stroke ->
            drawStrokeToMask(stroke)
        }

        // Reset paint style just in case
        maskPaint.style = Paint.Style.STROKE
    }

    // ------------------------------------------------------------------------
    // FINAL OUTPUT
    // ------------------------------------------------------------------------

    fun getFinalBitmap(): Bitmap? {
        return if (_eraserState.value.isErasing) {
            _livePreview.value
        } else {
            _previewBitmap.value
        }
    }

    // ------------------------------------------------------------------------
    // CLEANUP
    // ------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        _previewBitmap.value?.recycle()
        _livePreview.value?.recycle()
        _tempStrokeBitmap.value?.recycle()
        filteredBitmap?.recycle()
        maskBitmap?.recycle()

        viewModelScope.launch(gpuThread) {
            blendRenderer?.release()
            gpuExecutor.shutdown()
        }
    }
}

// ============================================================================
// COMPOSABLES
// ============================================================================

@Composable
fun FilterItem(
    name: String,
    thumbnail: Bitmap?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .padding(4.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

// ============================================================================
// FILTER PROCESSOR
// ============================================================================

object FilterProcessor {
    fun process(original: Bitmap, filter: Filter): Bitmap {
        return GPUFilter.applyFilter(original, filter)
    }
}

// ============================================================================
// GPU BLEND RENDERER (Persistent Context for Fast Blending)
// ============================================================================

class GpuBlendRenderer {
    private val TAG = "GpuBlendRenderer"

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    private var program = 0
    private var posHandle = 0
    private var texHandle = 0
    private var filteredTexHandle = 0
    private var originalTexHandle = 0
    private var maskTexHandle = 0
    private val texIds = IntArray(3)
    private var quadBuf: FloatBuffer? = null

    private var width = 0
    private var height = 0

    fun init(filtered: Bitmap, original: Bitmap) {
        this.width = filtered.width
        this.height = filtered.height

        initEgl()
        initGles()
        loadInitialTextures(filtered, original)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY).also {
            if (it == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0).also {
            if (it == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGles() {
        val vertexShader = GPUFilter.loadShader(GLES20.GL_VERTEX_SHADER, GPUFilter.VERTEX_SHADER)
        val fragShader = GPUFilter.loadShader(GLES20.GL_FRAGMENT_SHADER, GPUFilter.ERASE_BLEND_SHADER)
        program = GPUFilter.createProgram(vertexShader, fragShader)

        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        filteredTexHandle = GLES20.glGetUniformLocation(program, "uFiltered")
        originalTexHandle = GLES20.glGetUniformLocation(program, "uOriginal")
        maskTexHandle = GLES20.glGetUniformLocation(program, "uMask")

        quadBuf = GPUFilter.QUAD.toBuffer()

        GLES20.glGenTextures(3, texIds, 0)
    }

    private fun loadInitialTextures(filtered: Bitmap, original: Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GPUFilter.setupTextureParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, filtered, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[1])
        GPUFilter.setupTextureParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, original, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[2])
        GPUFilter.setupTextureParams()
    }

    fun applyBlend(mask: Bitmap): Bitmap {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        GLES20.glUseProgram(program)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[2])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mask, 0)

        GLES20.glUniform1i(filteredTexHandle, 0)
        GLES20.glUniform1i(originalTexHandle, 1)
        GLES20.glUniform1i(maskTexHandle, 2)

        quadBuf?.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, quadBuf)

        quadBuf?.position(2)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, quadBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        outBmp.copyPixelsFromBuffer(buffer)

        return outBmp
    }

    fun release() {
        Log.d(TAG, "Releasing GpuBlendRenderer...")

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        GLES20.glDeleteTextures(3, texIds, 0)
        GLES20.glDeleteProgram(program)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)

        eglDisplay = null
        eglContext = null
        eglSurface = null
        program = 0
        Log.d(TAG, "Released.")
    }
}

// ============================================================================
// GPU FILTER (One-shot Filter Application)
// ============================================================================

object GPUFilter {
    internal const val TAG = "GPUFilter"

    internal const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private const val FRAGMENT_FALLBACK = """
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D uTexture;
    void main() {
        vec2 uv = vTexCoord;
        uv.y = 1.0 - uv.y;
        gl_FragColor = texture2D(uTexture, uv);
    }
    """

    internal const val ERASE_BLEND_SHADER = """
    #ifdef GL_ES
    precision mediump float;
    #endif

    uniform sampler2D uFiltered;
    uniform sampler2D uOriginal;
    uniform sampler2D uMask;
    varying vec2 vTexCoord;

    void main() {
        vec2 uv = vTexCoord;
        uv.y = 1.0 - uv.y;
        
        vec4 filtered = texture2D(uFiltered, uv);
        vec4 original = texture2D(uOriginal, uv);
        float mask = texture2D(uMask, uv).r;
        
        gl_FragColor = mix(filtered, original, mask);
    }
    """

    internal val QUAD = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )

    internal fun setupTextureParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    internal fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun loadSafeShader(type: Int, code: String): Int {
        return try {
            loadShader(type, code)
        } catch (e: Exception) {
            Log.w(TAG, "Bad shader, fallback used")
            loadShader(type, FRAGMENT_FALLBACK)
        }
    }

    internal fun createProgram(vertexShader: Int, fragShader: Int): Int {
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragShader)
            GLES20.glLinkProgram(it)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(it)
                GLES20.glDeleteProgram(it)
                throw RuntimeException("Program link failed: $log")
            }
        }
    }

    fun applyFilter(input: Bitmap, filter: Filter): Bitmap {
        val fragCode = filter.shaderCode.ifBlank { FRAGMENT_FALLBACK }
        return try {
            renderShader(input, fragCode)
        } catch (e: Exception) {
            Log.e(TAG, "Render failed: ${e.message}")
            input.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    private fun renderShader(bitmap: Bitmap, fragCode: String): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        // EGL Setup
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0)
        val eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglCtx = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)

        // Shader Program Setup
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragShader = loadSafeShader(GLES20.GL_FRAGMENT_SHADER, fragCode)
        val program = createProgram(vertexShader, fragShader)

        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        val resolutionHandle = GLES20.glGetUniformLocation(program, "resolution")
        val intensityHandle = GLES20.glGetUniformLocation(program, "intensity")

        val quadBuf = QUAD.toBuffer()
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quadBuf.position(2))

        // Texture Setup
        val texId = IntArray(1)
        GLES20.glGenTextures(1, texId, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
        setupTextureParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glViewport(0, 0, w, h)

        // Set Uniforms
        GLES20.glUniform1i(textureHandle, 0)
        if (resolutionHandle >= 0) {
            GLES20.glUniform2f(resolutionHandle, w.toFloat(), h.toFloat())
        }
        if (intensityHandle >= 0) {
            GLES20.glUniform1f(intensityHandle, 1.0f)
        }

        // Render
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Read Pixels
        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        outBmp.copyPixelsFromBuffer(buffer)

        // Cleanup
        GLES20.glDeleteTextures(1, texId, 0)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragShader)

        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglCtx)
        EGL14.eglTerminate(eglDisplay)

        return outBmp
    }
}