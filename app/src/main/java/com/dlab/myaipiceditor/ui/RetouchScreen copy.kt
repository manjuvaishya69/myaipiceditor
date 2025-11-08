package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import com.dlab.myaipiceditor.gl.RetouchRenderer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetouchScreen(
    bitmap: Bitmap?,
    onConfirm: (Bitmap) -> Unit,  // ✅ Changed to accept Bitmap parameter
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTool by remember { mutableStateOf(RetouchTool.NONE) }
    var brushSettings by remember { mutableStateOf(RetouchBrush()) }
    var showBrushSettings by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val scope = rememberCoroutineScope()
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val renderer = remember { mutableStateOf<RetouchRenderer?>(null) }
    val isRendererReady = remember { mutableStateOf(false) }

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    // Undo/Redo for retouching
    val retouchHistory = remember { mutableStateListOf<Bitmap>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    val canUndo = historyIndex > 0
    val canRedo = historyIndex < retouchHistory.size - 1

    fun addToHistory(bitmap: Bitmap) {
        while (retouchHistory.size > historyIndex + 1) {
            retouchHistory.removeAt(retouchHistory.size - 1).recycle()
        }

        retouchHistory.add(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
        historyIndex = retouchHistory.size - 1

        if (retouchHistory.size > 20) {
            retouchHistory.removeAt(0).recycle()
            historyIndex--
        }
    }

    fun performUndo() {
        if (canUndo) {
            historyIndex--
            val bitmapToRestore = retouchHistory[historyIndex]
            glSurfaceView?.queueEvent {
                renderer.value?.setBitmap(bitmapToRestore)
                glSurfaceView?.requestRender()
            }
        }
    }

    fun performRedo() {
        if (canRedo) {
            historyIndex++
            val bitmapToRestore = retouchHistory[historyIndex]
            glSurfaceView?.queueEvent {
                renderer.value?.setBitmap(bitmapToRestore)
                glSurfaceView?.requestRender()
            }
        }
    }

    fun clearRetouch() {
        glSurfaceView?.queueEvent {
            bitmap?.let { renderer.value?.setBitmap(it) }
            glSurfaceView?.requestRender()
        }
        bitmap?.let { addToHistory(it) }
    }

    BackHandler(enabled = showBrushSettings || selectedTool != RetouchTool.NONE) {
        when {
            showBrushSettings -> showBrushSettings = false
            selectedTool != RetouchTool.NONE -> {
                selectedTool = RetouchTool.NONE
                showBrushSettings = false
            }
            else -> onCancel()
        }
    }

    LaunchedEffect(isRendererReady.value) {
        if (isRendererReady.value && bitmap != null) {
            addToHistory(bitmap)
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
                IconButton(
                    onClick = {
                        when {
                            showBrushSettings -> showBrushSettings = false
                            selectedTool != RetouchTool.NONE -> {
                                selectedTool = RetouchTool.NONE
                                showBrushSettings = false
                            }
                            else -> onCancel()
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = Color.White)
                }

                Text(
                    "Retouch",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTool != RetouchTool.NONE && selectedTool != RetouchTool.AUTO) {
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

                        IconButton(
                            onClick = { clearRetouch() },
                            enabled = !isProcessing
                        ) {
                            Icon(Icons.Filled.Clear, "Clear", tint = Color.White)
                        }

                        IconButton(
                            onClick = { showBrushSettings = !showBrushSettings },
                            enabled = !isProcessing
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                "Brush Settings",
                                tint = if (showBrushSettings) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (selectedTool != RetouchTool.NONE) {
                                selectedTool = RetouchTool.NONE
                                showBrushSettings = false
                            } else {
                                // ✅ Get the current bitmap and pass it back
                                scope.launch {
                                    val finalBitmap = renderer.value?.getCurrentBitmap()
                                    if (finalBitmap != null) {
                                        onConfirm(finalBitmap)
                                    } else {
                                        onCancel()
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Filled.Check, "Apply", tint = Color.White)
                    }
                }
            }

            // Image canvas
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

                                        val r = RetouchRenderer(bitmap)
                                        renderer.value = r
                                        setRenderer(r)
                                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                                        glSurfaceView = this

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

                            // Drawing overlay for brush-based tools
                            if (selectedTool != RetouchTool.NONE && selectedTool != RetouchTool.AUTO) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offsetX,
                                            translationY = offsetY
                                        )
                                        .pointerInput(brushSettings, selectedTool) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown()

                                                if (currentEvent.changes.size == 1) {
                                                    down.consume()
                                                    val normalizedX = down.position.x / size.width
                                                    val normalizedY = down.position.y / size.height
                                                    currentStroke = listOf(Offset(normalizedX, normalizedY))

                                                    glSurfaceView?.queueEvent {
                                                        renderer.value?.applyRetouchStroke(
                                                            currentStroke,
                                                            brushSettings,
                                                            selectedTool
                                                        )
                                                    }
                                                    glSurfaceView?.requestRender()

                                                    while (true) {
                                                        val event = awaitPointerEvent()

                                                        if (event.changes.size > 1) {
                                                            renderer.value?.getCurrentBitmap()?.let { bmp ->
                                                                addToHistory(bmp)
                                                            }
                                                            currentStroke = emptyList()
                                                            break
                                                        }

                                                        val change = event.changes.firstOrNull() ?: break

                                                        if (change.pressed) {
                                                            change.consume()
                                                            val normalizedX = change.position.x / size.width
                                                            val normalizedY = change.position.y / size.height

                                                            val lastPoint = currentStroke.lastOrNull() ?: return@awaitEachGesture
                                                            val newPoint = Offset(normalizedX, normalizedY)
                                                            currentStroke = currentStroke + newPoint

                                                            val segment = listOf(lastPoint, newPoint)

                                                            glSurfaceView?.queueEvent {
                                                                renderer.value?.applyRetouchStroke(
                                                                    segment,
                                                                    brushSettings,
                                                                    selectedTool
                                                                )
                                                            }
                                                            glSurfaceView?.requestRender()
                                                        } else {
                                                            renderer.value?.getCurrentBitmap()?.let { bmp ->
                                                                addToHistory(bmp)
                                                            }
                                                            currentStroke = emptyList()
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    // Draw brush cursor
                                    if (currentStroke.isNotEmpty()) {
                                        val lastPoint = currentStroke.last()
                                        val centerX = lastPoint.x * size.width
                                        val centerY = lastPoint.y * size.height
                                        val brushRadius = brushSettings.size / 2f

                                        val toolColor = when (selectedTool) {
                                            RetouchTool.BLEMISH -> Color(0xFFFF6B6B)
                                            RetouchTool.SMOOTH -> Color(0xFF4ECDC4)
                                            RetouchTool.SKIN_TONE -> Color(0xFFFFBE76)
                                            RetouchTool.WRINKLE -> Color(0xFF95E1D3)
                                            RetouchTool.TEETH_WHITENING -> Color(0xFFF8F9FA)
                                            else -> Color.White
                                        }

                                        if (brushSettings.hardness >= 0.9f) {
                                            // Square brush
                                            val rectSize = brushSettings.size
                                            drawRect(
                                                color = toolColor.copy(alpha = 0.3f),
                                                topLeft = Offset(centerX - rectSize/2, centerY - rectSize/2),
                                                size = androidx.compose.ui.geometry.Size(rectSize, rectSize)
                                            )
                                            drawLine(
                                                color = toolColor.copy(alpha = 0.8f),
                                                start = Offset(centerX - 8f, centerY),
                                                end = Offset(centerX + 8f, centerY),
                                                strokeWidth = 2f
                                            )
                                            drawLine(
                                                color = toolColor.copy(alpha = 0.8f),
                                                start = Offset(centerX, centerY - 8f),
                                                end = Offset(centerX, centerY + 8f),
                                                strokeWidth = 2f
                                            )
                                        } else {
                                            // Round brush
                                            drawCircle(
                                                color = toolColor.copy(alpha = 0.1f),
                                                radius = brushRadius * 1.3f,
                                                center = Offset(centerX, centerY)
                                            )
                                            drawCircle(
                                                color = toolColor.copy(alpha = 0.4f),
                                                radius = brushRadius,
                                                center = Offset(centerX, centerY)
                                            )
                                            drawCircle(
                                                color = toolColor.copy(alpha = 0.8f),
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

        // Brush Settings Panel
        if (selectedTool != RetouchTool.NONE && selectedTool != RetouchTool.AUTO) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                BrushSettingsPanel(
                    visible = showBrushSettings,
                    settings = brushSettings,
                    onSettingsChange = { newSettings ->
                        brushSettings = newSettings
                    },
                    onDismiss = {
                        showBrushSettings = false
                    }
                )
            }
        }

        // Bottom Tools Panel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            RetouchToolsPanel(
                selectedTool = selectedTool,
                onToolSelected = { tool ->
                    selectedTool = tool
                    if (tool != RetouchTool.NONE && tool != RetouchTool.AUTO) {
                        showBrushSettings = false
                    }
                },
                brushSettings = brushSettings,
                isProcessing = isProcessing
            )
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
fun RetouchToolsPanel(
    selectedTool: RetouchTool,
    onToolSelected: (RetouchTool) -> Unit,
    brushSettings: RetouchBrush,
    isProcessing: Boolean
) {
    AnimatedVisibility(
        visible = selectedTool == RetouchTool.NONE,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(300)
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(300)
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.85f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Select Retouch Tool",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // First row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RetouchToolButton(
                        tool = RetouchTool.AUTO,
                        label = "Auto",
                        icon = Icons.Default.AutoFixHigh,
                        isSelected = selectedTool == RetouchTool.AUTO,
                        onClick = { onToolSelected(RetouchTool.AUTO) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    )

                    RetouchToolButton(
                        tool = RetouchTool.BLEMISH,
                        label = "Blemish",
                        icon = Icons.Default.Circle,
                        isSelected = selectedTool == RetouchTool.BLEMISH,
                        onClick = { onToolSelected(RetouchTool.BLEMISH) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    )

                    RetouchToolButton(
                        tool = RetouchTool.SMOOTH,
                        label = "Smooth",
                        icon = Icons.Default.Spa,
                        isSelected = selectedTool == RetouchTool.SMOOTH,
                        onClick = { onToolSelected(RetouchTool.SMOOTH) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Second row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RetouchToolButton(
                        tool = RetouchTool.SKIN_TONE,
                        label = "Skin Tone",
                        icon = Icons.Default.Palette,
                        isSelected = selectedTool == RetouchTool.SKIN_TONE,
                        onClick = { onToolSelected(RetouchTool.SKIN_TONE) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    )

                    RetouchToolButton(
                        tool = RetouchTool.WRINKLE,
                        label = "Wrinkle",
                        icon = Icons.Default.Face,
                        isSelected = selectedTool == RetouchTool.WRINKLE,
                        onClick = { onToolSelected(RetouchTool.WRINKLE) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    )

                    RetouchToolButton(
                        tool = RetouchTool.TEETH_WHITENING,
                        label = "Teeth",
                        icon = Icons.Default.SentimentSatisfied,
                        isSelected = selectedTool == RetouchTool.TEETH_WHITENING,
                        onClick = { onToolSelected(RetouchTool.TEETH_WHITENING) },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // Active tool indicator
    AnimatedVisibility(
        visible = selectedTool != RetouchTool.NONE,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(300)
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(300)
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.85f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = when (selectedTool) {
                            RetouchTool.AUTO -> Icons.Default.AutoFixHigh
                            RetouchTool.BLEMISH -> Icons.Default.Circle
                            RetouchTool.SMOOTH -> Icons.Default.Spa
                            RetouchTool.SKIN_TONE -> Icons.Default.Palette
                            RetouchTool.WRINKLE -> Icons.Default.Face
                            RetouchTool.TEETH_WHITENING -> Icons.Default.SentimentSatisfied
                            else -> Icons.Default.Brush
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )

                    Column {
                        Text(
                            text = when (selectedTool) {
                                RetouchTool.AUTO -> "Auto Retouch"
                                RetouchTool.BLEMISH -> "Blemish Remover"
                                RetouchTool.SMOOTH -> "Skin Smoother"
                                RetouchTool.SKIN_TONE -> "Skin Tone"
                                RetouchTool.WRINKLE -> "Wrinkle Reducer"
                                RetouchTool.TEETH_WHITENING -> "Teeth Whitening"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        if (selectedTool != RetouchTool.NONE && selectedTool != RetouchTool.AUTO) {
                            Text(
                                text = "Size: ${brushSettings.size.toInt()}px | Strength: ${(brushSettings.strength * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { onToolSelected(RetouchTool.NONE) }
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun RetouchToolButton(
    tool: RetouchTool,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White.copy(alpha = 0.1f)
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                Color.White
            }
        ),
        modifier = modifier.height(80.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun BrushSettingsPanel(
    visible: Boolean,
    settings: RetouchBrush,
    onSettingsChange: (RetouchBrush) -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(300)
        ),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(300)
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding(),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Brush Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Size slider
                Text(
                    text = "Size: ${settings.size.toInt()}px",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Slider(
                    value = settings.size,
                    onValueChange = { onSettingsChange(settings.copy(size = it)) },
                    valueRange = 10f..150f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Strength slider
                Text(
                    text = "Strength: ${(settings.strength * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Slider(
                    value = settings.strength,
                    onValueChange = { onSettingsChange(settings.copy(strength = it)) },
                    valueRange = 0.1f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Hardness slider
                Text(
                    text = "Hardness: ${(settings.hardness * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Slider(
                    value = settings.hardness,
                    onValueChange = { onSettingsChange(settings.copy(hardness = it)) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reset button
                OutlinedButton(
                    onClick = {
                        onSettingsChange(RetouchBrush())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Refresh, "Reset", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset to Default")
                }
            }
        }
    }
}