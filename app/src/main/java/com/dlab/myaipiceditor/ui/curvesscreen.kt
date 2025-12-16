package com.dlab.myaipiceditor.ui

import com.dlab.myaipiceditor.R
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import com.dlab.myaipiceditor.gl.CurvesRenderer
import com.dlab.myaipiceditor.gl.LUTGenerator
import com.dlab.myaipiceditor.data.EraserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ✅ NEW: Data class to store complete curves state
data class CurvesState(
    val master: List<Offset>,
    val red: List<Offset>,
    val green: List<Offset>,
    val blue: List<Offset>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurvesScreen(
    bitmap: Bitmap?,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var isEraseMode by remember { mutableStateOf(false) }
    var currentEraseStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var isRestoring by remember { mutableStateOf(false) }

    // ✅ FIX #2: Store mask snapshot at START of erase mode
    var maskBeforeErase by remember { mutableStateOf<Bitmap?>(null) }

    // ✅ FIX #2: Store curves state before erase mode
    var curvesStateBeforeErase by remember { mutableStateOf<CurvesState?>(null) }

    var eraserSettings by remember { mutableStateOf(EraserSettings.DEFAULT) }
    var showEraserSettings by remember { mutableStateOf(false) }

    val identityPoints = remember { listOf(Offset(0f, 0f), Offset(1f, 1f)) }
    val scope = rememberCoroutineScope()
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { mutableStateOf<CurvesRenderer?>(null) }

    var selectedChannel by remember { mutableStateOf("Master") }
    var channelMenuExpanded by remember { mutableStateOf(false) }
    val channels = listOf("Master", "Red", "Green", "Blue")
    var isProcessing by remember { mutableStateOf(false) }

    // ✅ FIX #1: Track if user is actively dragging
    var isDragging by remember { mutableStateOf(false) }

    var isControlsVisible by remember { mutableStateOf(false) }
    var controlsPosition by remember { mutableStateOf("left") }
    var buttonOffsetY by remember { mutableFloatStateOf(0f) }

    var pointsMaster by remember { mutableStateOf(identityPoints) }
    var pointsRed by remember { mutableStateOf(identityPoints) }
    var pointsGreen by remember { mutableStateOf(identityPoints) }
    var pointsBlue by remember { mutableStateOf(identityPoints) }

    val currentLUT = remember { mutableStateOf<FloatArray?>(null) }
    val isRendererReady = remember { mutableStateOf(false) }

    val lutUpdateChannel = remember { Channel<Unit>(Channel.CONFLATED) }

    // Erase undo/redo
    val maskHistory = remember { mutableStateListOf<Bitmap>() }
    var maskHistoryIndex by remember { mutableIntStateOf(-1) }

    val canUndoErase = maskHistoryIndex > 0
    val canRedoErase = maskHistoryIndex < maskHistory.size - 1

    fun addMaskToHistory(mask: Bitmap) {
        while (maskHistory.size > maskHistoryIndex + 1) {
            maskHistory.removeAt(maskHistory.size - 1).recycle()
        }

        maskHistory.add(mask.copy(mask.config ?: Bitmap.Config.ARGB_8888, false))
        maskHistoryIndex = maskHistory.size - 1

        if (maskHistory.size > 20) {
            maskHistory.removeAt(0).recycle()
            maskHistoryIndex--
        }
    }

    // ✅ FIX #3: Force immediate render after undo/redo
    fun undoEraseMask() {
        if (canUndoErase) {
            maskHistoryIndex--
            val maskToApply = maskHistory[maskHistoryIndex]

            // ✅ *** CRITICAL THREADING FIX ***
            // All OpenGL calls MUST run on the GLThread.
            glSurfaceView?.queueEvent {
                renderer.value?.setMaskBitmap(maskToApply)
                glSurfaceView?.requestRender() // Request render *after* setting
            }
        }
    }

    fun redoEraseMask() {
        if (canRedoErase) {
            maskHistoryIndex++
            val maskToApply = maskHistory[maskHistoryIndex]

            // ✅ *** CRITICAL THREADING FIX ***
            // All OpenGL calls MUST run on the GLThread.
            glSurfaceView?.queueEvent {
                renderer.value?.setMaskBitmap(maskToApply)
                glSurfaceView?.requestRender() // Request render *after* setting
            }
        }
    }

    // ✅ FIX #3: Force immediate render after reset
    fun clearEraseMask() {
        // ✅ *** CRITICAL THREADING FIX ***
        glSurfaceView?.queueEvent {
            renderer.value?.clearMask() // Runs on GL Thread

            // Get the bitmap *after* it's cleared
            val clearedMask = renderer.value?.getMaskBitmap()

            // Request render
            glSurfaceView?.requestRender()

            // Post back to Main thread to update Composable state
            scope.launch(Dispatchers.Main) {
                clearedMask?.let { addMaskToHistory(it) }
            }
        }
    }

    // ✅ FIX #2: Improved back handler - restore EVERYTHING
    BackHandler(enabled = isEraseMode || showEraserSettings) {
        when {
            showEraserSettings -> showEraserSettings = false
            isEraseMode -> {
                // Restore mask to state before erase mode
                maskBeforeErase?.let { backupMask ->
                    // ✅ *** CRITICAL THREADING FIX ***
                    // All OpenGL calls MUST run on the GLThread.
                    // We queue this event to restore the mask safely.
                    glSurfaceView?.queueEvent {
                        renderer.value?.restoreMaskAndUpdate(backupMask)
                        backupMask.recycle() // Recycle bitmap *after* it's used by GL
                    }
                    glSurfaceView?.requestRender() // This is safe to call from the UI thread
                }
                maskBeforeErase = null // ✅ *** CRITICAL FIX: Clean up reference ***

                // Restore curves state
                curvesStateBeforeErase?.let { state ->
                    pointsMaster = state.master
                    pointsRed = state.red
                    pointsGreen = state.green
                    pointsBlue = state.blue
                    lutUpdateChannel.trySend(Unit)
                }
                curvesStateBeforeErase = null // ✅ *** CRITICAL FIX: Clean up reference ***

                glSurfaceView?.queueEvent {
                    glSurfaceView?.requestRender()
                }

                // Exit erase mode
                isEraseMode = false
                currentEraseStroke = emptyList()
                isRestoring = false
            }
        }
    }

    // Curves undo/redo
    data class CurvesHistoryState(
        val master: List<Offset>,
        val red: List<Offset>,
        val green: List<Offset>,
        val blue: List<Offset>
    )



    val curvesHistory = remember { mutableStateListOf<CurvesHistoryState>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    val canUndo = historyIndex > 0
    val canRedo = historyIndex < curvesHistory.size - 1

    // ✅ FIX #1: Only add to history when drag ENDS, not during drag
    fun addToHistory() {
        // Don't add during active dragging
        if (isDragging) return

        while (curvesHistory.size > historyIndex + 1) {
            curvesHistory.removeAt(curvesHistory.size - 1)
        }

        curvesHistory.add(
            CurvesHistoryState(
                master = pointsMaster.toList(),
                red = pointsRed.toList(),
                green = pointsGreen.toList(),
                blue = pointsBlue.toList()
            )
        )
        historyIndex = curvesHistory.size - 1

        // ✅ FIX #4A: Increase limit to 50 to reduce overflow
        if (curvesHistory.size > 50) {
            curvesHistory.removeAt(0)
            historyIndex--
        }
    }

    fun performUndo() {
        if (canUndo) {
            historyIndex--
            val state = curvesHistory[historyIndex]
            pointsMaster = state.master.toList()
            pointsRed = state.red.toList()
            pointsGreen = state.green.toList()
            pointsBlue = state.blue.toList()
            lutUpdateChannel.trySend(Unit)
        }
    }

    fun performRedo() {
        if (canRedo) {
            historyIndex++
            val state = curvesHistory[historyIndex]
            pointsMaster = state.master.toList()
            pointsRed = state.red.toList()
            pointsGreen = state.green.toList()
            pointsBlue = state.blue.toList()
            lutUpdateChannel.trySend(Unit)
        }
    }

    LaunchedEffect(Unit) {
        if (curvesHistory.isEmpty()) {
            addToHistory()
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    fun getPoints(): List<Offset> = when (selectedChannel) {
        "Red" -> pointsRed
        "Green" -> pointsGreen
        "Blue" -> pointsBlue
        else -> pointsMaster
    }

    fun setPoints(newPoints: List<Offset>) {
        when (selectedChannel) {
            "Red" -> pointsRed = newPoints.toList()
            "Green" -> pointsGreen = newPoints.toList()
            "Blue" -> pointsBlue = newPoints.toList()
            else -> pointsMaster = newPoints.toList()
        }
        // ✅ FIX #1: Don't add to history here - only when drag ends
        lutUpdateChannel.trySend(Unit)
    }

    fun updateRenderer() {
        if (!isRendererReady.value) return

        scope.launch(Dispatchers.Default) {
            try {
                val lut = LUTGenerator.createRGBLUTNormalized(
                    pointsMaster, pointsRed, pointsGreen, pointsBlue
                )

                currentLUT.value = lut

                glSurfaceView?.queueEvent {
                    renderer.value?.setPendingLut(lut)
                }

                if (!isDragging) {
                    withContext(Dispatchers.Main) {
                        glSurfaceView?.requestRender()
                    }
                }
            } catch (e: Exception) {
                Log.e("CurvesScreen", "Error updating renderer", e)
            }
        }
    }

    LaunchedEffect(Unit) {
        lutUpdateChannel.consumeAsFlow()
            .debounce(8L)
            .collect {
                updateRenderer()
            }
    }

    suspend fun flattenResult(): Bitmap? = withContext(Dispatchers.Default) {
        val mask = renderer.value?.getMaskBitmap() ?: return@withContext null
        val lut = currentLUT.value ?: return@withContext null
        val srcBitmap = bitmap ?: return@withContext null

        val result = srcBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)

        val maskPixels = IntArray(mask.width * mask.height)
        mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)

        for (i in pixels.indices) {
            val originalPixel = pixels[i]
            val r = (originalPixel shr 16) and 0xFF
            val g = (originalPixel shr 8) and 0xFF
            val b = originalPixel and 0xFF
            val a = (originalPixel shr 24) and 0xFF

            val curvedR = (lut[r * 3] * 255f).toInt().coerceIn(0, 255)
            val curvedG = (lut[g * 3 + 1] * 255f).toInt().coerceIn(0, 255)
            val curvedB = (lut[b * 3 + 2] * 255f).toInt().coerceIn(0, 255)

            val maskAlpha = ((maskPixels[i] shr 16) and 0xFF) / 255f

            val finalR = (r * (1f - maskAlpha) + curvedR * maskAlpha).toInt()
            val finalG = (g * (1f - maskAlpha) + curvedG * maskAlpha).toInt()
            val finalB = (b * (1f - maskAlpha) + curvedB * maskAlpha).toInt()

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        result
    }

    fun applyCurvesToBitmap() {
        scope.launch {
            isProcessing = true
            try {
                val processedBitmap = flattenResult()
                if (processedBitmap != null) {
                    onConfirm(processedBitmap)

                    // ✅ *** FIX #2 (MEMORY LEAK) ***
                    // This cleanup must run REGARDLESS of erase mode
                    // to prevent memory leaks when confirming.
                    isEraseMode = false
                    currentEraseStroke = emptyList()
                    isRestoring = false
                    maskBeforeErase?.recycle()
                    maskBeforeErase = null
                    curvesStateBeforeErase = null

                } else {
                    onCancel()
                }
            } catch (e: Exception) {
                Log.e("CurvesScreen", "Error applying curves", e)
                onCancel()
            } finally {
                isProcessing = false
            }
        }
    }

    LaunchedEffect(isDragging) {
        glSurfaceView?.renderMode = if (isDragging) {
            GLSurfaceView.RENDERMODE_CONTINUOUSLY
        } else {
            GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    LaunchedEffect(isRendererReady.value) {
        if (isRendererReady.value) {
            updateRenderer()
            renderer.value?.getMaskBitmap()?.let { addMaskToHistory(it) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(0.5f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ✅ FIX #2: Back button restores everything
                IconButton(
                    onClick = {
                        when {
                            showEraserSettings -> showEraserSettings = false
                            isEraseMode -> {
                                maskBeforeErase?.let { backupMask ->
                                    // ✅ *** CRITICAL THREADING FIX ***
                                    // All OpenGL calls MUST run on the GLThread.
                                    glSurfaceView?.queueEvent {
                                        renderer.value?.restoreMaskAndUpdate(backupMask)
                                        backupMask.recycle() // Recycle bitmap *after* it's used by GL
                                    }
                                    glSurfaceView?.requestRender()
                                }
                                maskBeforeErase = null // ✅ *** CRITICAL FIX: Clean up reference ***

                                curvesStateBeforeErase?.let { state ->
                                    pointsMaster = state.master
                                    pointsRed = state.red
                                    pointsGreen = state.green
                                    pointsBlue = state.blue
                                    lutUpdateChannel.trySend(Unit)
                                }
                                curvesStateBeforeErase = null // ✅ *** CRITICAL FIX: Clean up reference ***

                                glSurfaceView?.queueEvent {
                                    glSurfaceView?.requestRender()
                                }
                                isEraseMode = false
                                currentEraseStroke = emptyList()
                                isRestoring = false
                            }
                            else -> onCancel()
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = Color.White)
                }

                Text("Curves", style = MaterialTheme.typography.titleMedium, color = Color.White)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEraseMode) {
                        IconButton(
                            onClick = { undoEraseMask() },
                            enabled = canUndoErase && !isProcessing
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                "Undo Erase",
                                tint = if (canUndoErase) Color.White else Color.White.copy(alpha = 0.38f)
                            )
                        }

                        IconButton(
                            onClick = { redoEraseMask() },
                            enabled = canRedoErase && !isProcessing
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                "Redo Erase",
                                tint = if (canRedoErase) Color.White else Color.White.copy(alpha = 0.38f)
                            )
                        }

                        IconButton(
                            onClick = { clearEraseMask() },
                            enabled = !isProcessing
                        ) {
                            Icon(Icons.Filled.Clear, "Clear Erase", tint = Color.White)
                        }

                        IconButton(
                            onClick = { showEraserSettings = !showEraserSettings },
                            enabled = !isProcessing
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                "Eraser Settings",
                                tint = if (showEraserSettings) Color.Red else Color.White
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { performUndo() },
                            enabled = canUndo && !isProcessing
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                "Undo",
                                tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.38f)
                            )
                        }

                        IconButton(
                            onClick = { performRedo() },
                            enabled = canRedo && !isProcessing
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                "Redo",
                                tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.38f)
                            )
                        }
                    }

                    // ✅ FIX #2: Capture state when entering erase mode
                    IconButton(
                        onClick = {
                            if (!isEraseMode) {
                                // ✅ *** FIX #1 (RACE CONDITION) ***
                                // Check if renderer is ready and mask was successfully captured
                                val capturedMask = renderer.value?.getMaskBitmap() // ✅ FIX: getMaskBitmap() already returns a copy

                                if (capturedMask == null) {
                                    Log.e("CurvesScreen", "Cannot enter erase mode: Renderer mask is not ready yet.")
                                    // Optionally show a Toast here to inform the user
                                    return@IconButton // Abort
                                }

                                // Capture current state before entering
                                maskBeforeErase = capturedMask
                                curvesStateBeforeErase = CurvesState(
                                    master = pointsMaster.toList(),
                                    red = pointsRed.toList(),
                                    green = pointsGreen.toList(),
                                    blue = pointsBlue.toList()
                                )
                                isEraseMode = true
                            }
                        },
                        // ✅ *** FIX #1 (RACE CONDITION) ***
                        // Disable button if processing or if renderer is not ready
                        enabled = !isProcessing && isRendererReady.value
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_eraser),
                            contentDescription = "Erase",
                            tint = if (isEraseMode) Color.Red else Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (isEraseMode) {
                                isEraseMode = false
                                currentEraseStroke = emptyList()
                                isRestoring = false
                                showEraserSettings = false
                                maskBeforeErase?.recycle()
                                maskBeforeErase = null
                                curvesStateBeforeErase = null
                            } else {
                                applyCurvesToBitmap()
                            }
                        },
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Filled.Check, "Apply", tint = Color.White)
                    }
                }
            }

            // Image canvas card
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspectRatio)
                                .transformable(state = transformableState)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    GLSurfaceView(ctx).apply {
                                        setEGLContextClientVersion(2)
                                        setZOrderOnTop(false)
                                        holder.setFormat(android.graphics.PixelFormat.OPAQUE)

                                        // ✅ *** FIX: Reverted to match your CurvesRenderer.kt constructor ***
                                        val r = CurvesRenderer(bitmap) // No callback
                                        renderer.value = r
                                        setRenderer(r)
                                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                                        glSurfaceView = this

                                        // ✅ *** FIX: Restore the original queueEvent ***
                                        // This will set isRendererReady after the GL context is created.
                                        queueEvent {
                                            isRendererReady.value = true
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY
                                    ),
                                update = { view ->
                                    view.requestRender()
                                }
                            )

                            // Erase drawing overlay
                            if (isEraseMode) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offsetX,
                                            translationY = offsetY
                                        )
                                        .pointerInput(eraserSettings, isRestoring) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown()

                                                if (currentEvent.changes.size == 1) {
                                                    down.consume()
                                                    val normalizedX = down.position.x / size.width
                                                    val normalizedY = down.position.y / size.height
                                                    currentEraseStroke = listOf(Offset(normalizedX, normalizedY))

                                                    glSurfaceView?.queueEvent {
                                                        renderer.value?.updateMask(
                                                            currentEraseStroke,
                                                            eraserSettings.size,
                                                            eraserSettings.opacity,
                                                            eraserSettings.hardness,
                                                            isErasing = !isRestoring
                                                        )
                                                    }
                                                    glSurfaceView?.requestRender()

                                                    while (true) {
                                                        val event = awaitPointerEvent()

                                                        if (event.changes.size > 1) {
                                                            renderer.value?.getMaskBitmap()?.let { mask ->
                                                                addMaskToHistory(mask)
                                                            }
                                                            currentEraseStroke = emptyList()
                                                            break
                                                        }

                                                        val change = event.changes.firstOrNull() ?: break

                                                        if (change.pressed) {
                                                            change.consume()
                                                            val normalizedX = change.position.x / size.width
                                                            val normalizedY = change.position.y / size.height

                                                            val lastPoint = currentEraseStroke.lastOrNull() ?: return@awaitEachGesture
                                                            val newPoint = Offset(normalizedX, normalizedY)
                                                            currentEraseStroke = currentEraseStroke + newPoint

                                                            val segment = listOf(lastPoint, newPoint)

                                                            glSurfaceView?.queueEvent {
                                                                renderer.value?.updateMask(
                                                                    segment,
                                                                    eraserSettings.size,
                                                                    eraserSettings.opacity,
                                                                    eraserSettings.hardness,
                                                                    isErasing = !isRestoring
                                                                )
                                                            }
                                                            glSurfaceView?.requestRender()
                                                        } else {
                                                            renderer.value?.getMaskBitmap()?.let { mask ->
                                                                addMaskToHistory(mask)
                                                            }
                                                            currentEraseStroke = emptyList()
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    if (currentEraseStroke.isNotEmpty()) {
                                        val lastPoint = currentEraseStroke.last()
                                        val centerX = lastPoint.x * size.width
                                        val centerY = lastPoint.y * size.height
                                        val brushRadius = eraserSettings.size / 2f
                                        val opacityAlpha = eraserSettings.opacity / 100f
                                        val cursorColor = if (isRestoring) Color.Green else Color.Red

                                        if (eraserSettings.hardness >= 100f) {
                                            val rectSize = eraserSettings.size
                                            drawRect(
                                                color = cursorColor.copy(alpha = 0.3f * opacityAlpha),
                                                topLeft = Offset(centerX - rectSize/2, centerY - rectSize/2),
                                                size = androidx.compose.ui.geometry.Size(rectSize, rectSize)
                                            )
                                            drawLine(
                                                color = cursorColor.copy(alpha = 0.8f * opacityAlpha),
                                                start = Offset(centerX - 8f, centerY),
                                                end = Offset(centerX + 8f, centerY),
                                                strokeWidth = 2f
                                            )
                                            drawLine(
                                                color = cursorColor.copy(alpha = 0.8f * opacityAlpha),
                                                start = Offset(centerX, centerY - 8f),
                                                end = Offset(centerX, centerY + 8f),
                                                strokeWidth = 2f
                                            )
                                        } else {
                                            drawCircle(
                                                color = cursorColor.copy(alpha = 0.1f * opacityAlpha),
                                                radius = brushRadius * 1.3f,
                                                center = Offset(centerX, centerY)
                                            )
                                            drawCircle(
                                                color = cursorColor.copy(alpha = 0.4f * opacityAlpha),
                                                radius = brushRadius,
                                                center = Offset(centerX, centerY)
                                            )
                                            drawCircle(
                                                color = cursorColor.copy(alpha = 0.8f * opacityAlpha),
                                                radius = 4f,
                                                center = Offset(centerX, centerY)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Photo,
                                contentDescription = "No Image",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No image available",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Eraser Settings Panel
        if (isEraseMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                EraserSettingsPanel(
                    visible = showEraserSettings,
                    settings = eraserSettings,
                    onSettingsChange = { newSettings ->
                        eraserSettings = newSettings
                    },
                    onDismiss = {
                        showEraserSettings = false
                    }
                )
            }
        }

        // Curve editor overlay
        if (!isEraseMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Transparent)
            ) {
                CurvesEditorCanvasNormalized(
                    points = getPoints(),
                    selectedChannel = selectedChannel,
                    onPointsChange = { newPoints ->
                        setPoints(newPoints)
                    },
                    onDragStateChange = { dragging ->
                        isDragging = dragging
                    },
                    // ✅ FIX #1: Add history when drag ends
                    onDragEnd = {
                        addToHistory()
                    },
                    modifier = Modifier.matchParentSize()
                )
            }
        }

        // Erase/Restore tool panel
        if (isEraseMode && !showEraserSettings) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .widthIn(min = 260.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { isRestoring = false },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isRestoring) Color.Red.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.3f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                painterResource(id = R.drawable.ic_eraser),
                                "Erase",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Erase")
                        }

                        Spacer(Modifier.width(8.dp))

                        Button(
                            onClick = { isRestoring = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRestoring) Color.Green.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.3f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Brush,
                                "Restore",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Restore")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Size: ${eraserSettings.size.toInt()}px | Opacity: ${eraserSettings.opacity.toInt()}% | Hardness: ${eraserSettings.hardness.toInt()}%",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        // Sliding Controls Panel
        if (!isEraseMode) {
            Box(
                modifier = Modifier
                    .align(
                        if (controlsPosition == "left") Alignment.CenterStart
                        else Alignment.CenterEnd
                    )
                    .padding(vertical = 100.dp)
                    .graphicsLayer {
                        translationY = buttonOffsetY
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (controlsPosition == "left") {
                        AnimatedVisibility(
                            visible = isControlsVisible,
                            enter = slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(300)
                            ),
                            exit = slideOutHorizontally(
                                targetOffsetX = { -it },
                                animationSpec = tween(300)
                            )
                        ) {
                            ControlsPanel(
                                selectedChannel = selectedChannel,
                                channels = channels,
                                channelMenuExpanded = channelMenuExpanded,
                                onChannelMenuExpandedChange = { channelMenuExpanded = it },
                                onChannelSelected = { channel ->
                                    selectedChannel = channel
                                    channelMenuExpanded = false
                                },
                                onResetCurve = {
                                    setPoints(identityPoints)
                                    addToHistory()
                                },
                                isProcessing = isProcessing
                            )
                        }

                        DraggableToggleButton(
                            isControlsVisible = isControlsVisible,
                            controlsPosition = controlsPosition,
                            onToggle = { isControlsVisible = !isControlsVisible },
                            onPositionChange = { newPosition ->
                                controlsPosition = newPosition
                            },
                            onVerticalDrag = { dragAmount ->
                                buttonOffsetY += dragAmount
                            }
                        )
                    }

                    if (controlsPosition == "right") {
                        DraggableToggleButton(
                            isControlsVisible = isControlsVisible,
                            controlsPosition = controlsPosition,
                            onToggle = { isControlsVisible = !isControlsVisible },
                            onPositionChange = { newPosition ->
                                controlsPosition = newPosition
                            },
                            onVerticalDrag = { dragAmount ->
                                buttonOffsetY += dragAmount
                            }
                        )

                        AnimatedVisibility(
                            visible = isControlsVisible,
                            enter = slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(300)
                            ),
                            exit = slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(300)
                            )
                        ) {
                            ControlsPanel(
                                selectedChannel = selectedChannel,
                                channels = channels,
                                channelMenuExpanded = channelMenuExpanded,
                                onChannelMenuExpandedChange = { channelMenuExpanded = it },
                                onChannelSelected = { channel ->
                                    selectedChannel = channel
                                    channelMenuExpanded = false
                                },
                                onResetCurve = {
                                    setPoints(identityPoints)
                                    addToHistory()
                                },
                                isProcessing = isProcessing
                            )
                        }
                    }
                }
            }
        }

        // Loading indicator
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
fun DraggableToggleButton(
    isControlsVisible: Boolean,
    controlsPosition: String,
    onToggle: () -> Unit,
    onPositionChange: (String) -> Unit,
    onVerticalDrag: (Float) -> Unit
) {
    val density = LocalDensity.current
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val threshold = with(density) { 100.dp.toPx() }

    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(0.6f))
            .graphicsLayer {
                translationX = dragOffsetX
            }
            .pointerInput(controlsPosition) {
                detectDragGestures(
                    onDragEnd = {
                        if (dragOffsetX > threshold && controlsPosition == "left") {
                            onPositionChange("right")
                        } else if (dragOffsetX < -threshold && controlsPosition == "right") {
                            onPositionChange("left")
                        }
                        dragOffsetX = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val (xDrag, yDrag) = dragAmount

                        if (abs(xDrag) > abs(yDrag)) {
                            if (controlsPosition == "left" && xDrag > 0) {
                                dragOffsetX = (dragOffsetX + xDrag).coerceIn(0f, threshold * 2)
                            } else if (controlsPosition == "right" && xDrag < 0) {
                                dragOffsetX = (dragOffsetX + xDrag).coerceIn(-threshold * 2, 0f)
                            }
                        } else {
                            onVerticalDrag(yDrag)
                        }
                    }
                )
            }
    ) {
        Icon(
            if (isControlsVisible) {
                if (controlsPosition == "left") Icons.Filled.KeyboardArrowLeft
                else Icons.Filled.KeyboardArrowRight
            } else {
                if (controlsPosition == "left") Icons.Filled.KeyboardArrowRight
                else Icons.Filled.KeyboardArrowLeft
            },
            contentDescription = "Toggle/Drag Controls",
            tint = Color.White
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsPanel(
    selectedChannel: String,
    channels: List<String>,
    channelMenuExpanded: Boolean,
    onChannelMenuExpandedChange: (Boolean) -> Unit,
    onChannelSelected: (String) -> Unit,
    onResetCurve: () -> Unit,
    isProcessing: Boolean
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(0.7f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box {
            OutlinedButton(
                onClick = { onChannelMenuExpandedChange(true) },
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedChannel, color = Color.White)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select", tint = Color.White)
                }
            }
            DropdownMenu(
                expanded = channelMenuExpanded,
                onDismissRequest = { onChannelMenuExpandedChange(false) }
            ) {
                channels.forEach { channel ->
                    DropdownMenuItem(
                        text = { Text(channel) },
                        onClick = { onChannelSelected(channel) }
                    )
                }
            }
        }

        Button(
            onClick = onResetCurve,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(0.2f)
            )
        ) {
            Text("Reset Curve", color = Color.White)
        }
    }
}