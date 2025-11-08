package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dlab.myaipiceditor.data.BrushStroke
import com.dlab.myaipiceditor.data.ObjectRemovalState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectRemovalScreen(
    bitmap: Bitmap,
    removalState: ObjectRemovalState,
    onStrokeAdded: (BrushStroke) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onToggleEraser: () -> Unit,
    onApply: () -> Unit,
    onRefineAndPreview: () -> Unit,
    onAcceptRefinedMask: () -> Unit,
    onRejectRefinedMask: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(removalState.showLivePreview) {
        if (removalState.showLivePreview) {
            delay(3000)
            onAcceptRefinedMask()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Remove Objects",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onUndo,
                        enabled = removalState.canUndo && !removalState.isProcessing
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            "Undo",
                            tint = if (removalState.canUndo && !removalState.isProcessing) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                    IconButton(
                        onClick = onRedo,
                        enabled = removalState.canRedo && !removalState.isProcessing
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            "Redo",
                            tint = if (removalState.canRedo && !removalState.isProcessing) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                    IconButton(
                        onClick = onReset,
                        enabled = removalState.strokes.isNotEmpty() && !removalState.isProcessing
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            "Reset",
                            tint = if (removalState.strokes.isNotEmpty() && !removalState.isProcessing) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                    IconButton(
                        onClick = onConfirm,
                        enabled = !removalState.isProcessing
                    ) {
                        Icon(
                            Icons.Default.Check,
                            "Done",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (!removalState.showRefinedPreview) {
                ObjectRemovalBottomBar(
                    removalState = removalState,
                    onBrushSizeChange = onBrushSizeChange
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (removalState.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )
                        Text(
                            text = "Removing objects...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "AI is intelligently filling the background",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                DrawableMaskCanvas(
                    bitmap = bitmap,
                    strokes = removalState.strokes,
                    brushSize = removalState.brushSize,
                    isEraserMode = false,
                    showStrokes = true,
                    onStrokeAdded = onStrokeAdded,
                    overlayMask = removalState.livePreviewOverlay,
                    isRefining = removalState.isRefiningMask,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (removalState.strokes.isEmpty() && !removalState.isProcessing) {
                InstructionOverlay(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                )
            }

            if (removalState.isRefiningMask) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Detecting object...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (removalState.showLivePreview && removalState.livePreviewOverlay != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Removing in 3 seconds...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InstructionOverlay(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = "Paint over objects to remove",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "AI detects and removes automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun DrawableMaskCanvas(
    bitmap: Bitmap,
    strokes: List<BrushStroke>,
    brushSize: Float,
    isEraserMode: Boolean,
    showStrokes: Boolean,
    onStrokeAdded: (BrushStroke) -> Unit,
    overlayMask: Bitmap? = null,
    isRefining: Boolean = false,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var currentPath by remember { mutableStateOf<MutableList<Offset>?>(null) }
    var isDrawing by remember { mutableStateOf(false) }
    var wasMultiTouch by remember { mutableStateOf(false) }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val maskBitmap = remember(overlayMask) { overlayMask?.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coordinates ->
                canvasSize = coordinates.size
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(brushSize, isEraserMode, isRefining, scale, offset) {
                    if (isRefining) return@pointerInput

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()

                            when (event.changes.size) {
                                1 -> {
                                    val change = event.changes.first()

                                    when (change.pressed) {
                                        true -> {
                                            if (wasMultiTouch) {
                                                change.consume()
                                            } else if (!isDrawing) {
                                                isDrawing = true

                                                val canvasSize = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                                                val imageRect = getImageRect(canvasSize, bitmap)

                                                val canvasPos = transformTouchToCanvas(
                                                    change.position.x,
                                                    change.position.y,
                                                    size.width.toFloat(),
                                                    size.height.toFloat(),
                                                    scale,
                                                    offset
                                                )

                                                val normalizedOffset = Offset(
                                                    (canvasPos.x - imageRect.left) / imageRect.width,
                                                    (canvasPos.y - imageRect.top) / imageRect.height
                                                )

                                                if (normalizedOffset.x in 0f..1f && normalizedOffset.y in 0f..1f) {
                                                    currentPath = mutableListOf(normalizedOffset)
                                                }
                                                change.consume()
                                            } else {
                                                val canvasSize = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                                                val imageRect = getImageRect(canvasSize, bitmap)

                                                val canvasPos = transformTouchToCanvas(
                                                    change.position.x,
                                                    change.position.y,
                                                    size.width.toFloat(),
                                                    size.height.toFloat(),
                                                    scale,
                                                    offset
                                                )

                                                val normalizedOffset = Offset(
                                                    (canvasPos.x - imageRect.left) / imageRect.width,
                                                    (canvasPos.y - imageRect.top) / imageRect.height
                                                )

                                                if (normalizedOffset.x in 0f..1f && normalizedOffset.y in 0f..1f) {
                                                    currentPath?.add(normalizedOffset)
                                                }
                                                change.consume()
                                            }
                                        }
                                        false -> {
                                            if (wasMultiTouch) {
                                                wasMultiTouch = false
                                                change.consume()
                                            } else if (isDrawing) {
                                                isDrawing = false
                                                currentPath?.let { path ->
                                                    if (path.size > 1) {
                                                        onStrokeAdded(
                                                            BrushStroke(
                                                                points = path.toList(),
                                                                brushSize = brushSize,
                                                                isEraser = false
                                                            )
                                                        )
                                                    }
                                                }
                                                currentPath = null
                                                change.consume()
                                            } else {
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                                2 -> {
                                    wasMultiTouch = true
                                    isDrawing = false
                                    currentPath = null

                                    val firstChange = event.changes[0]
                                    val secondChange = event.changes[1]

                                    val oldDistance = (firstChange.previousPosition - secondChange.previousPosition).getDistance()
                                    val newDistance = (firstChange.position - secondChange.position).getDistance()

                                    if (oldDistance > 0) {
                                        val zoomChange = newDistance / oldDistance
                                        scale = (scale * zoomChange).coerceIn(0.5f, 5f)

                                        // Pan gesture
                                        val panX = (firstChange.position.x - firstChange.previousPosition.x +
                                                secondChange.position.x - secondChange.previousPosition.x) / 2
                                        val panY = (firstChange.position.y - firstChange.previousPosition.y +
                                                secondChange.position.y - secondChange.previousPosition.y) / 2

                                        // Update offset with pan
                                        offset = Offset(
                                            offset.x + panX,
                                            offset.y + panY
                                        )

                                        // Constrain offset to prevent excessive panning
                                        val maxOffsetX = (size.width * (scale - 1) / 2f).coerceAtLeast(0f)
                                        val maxOffsetY = (size.height * (scale - 1) / 2f).coerceAtLeast(0f)

                                        offset = Offset(
                                            offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                            offset.y.coerceIn(-maxOffsetY, maxOffsetY)
                                        )
                                    }

                                    firstChange.consume()
                                    secondChange.consume()
                                }
                            }
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        ) {
            val imageRect = getImageRect(size, bitmap)

            drawImage(
                image = imageBitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    imageRect.left.roundToInt(),
                    imageRect.top.roundToInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    imageRect.width.roundToInt(),
                    imageRect.height.roundToInt()
                )
            )

            if (overlayMask != null && maskBitmap != null) {
                drawImage(
                    image = maskBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        imageRect.left.roundToInt(),
                        imageRect.top.roundToInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        imageRect.width.roundToInt(),
                        imageRect.height.roundToInt()
                    ),
                    alpha = 0.5f,
                    colorFilter = ColorFilter.tint(Color(0xFFFF5722), BlendMode.Modulate)
                )
            }

            if (showStrokes) {
                strokes.forEach { stroke ->
                    drawStroke(stroke, imageRect)
                }

                currentPath?.let { path ->
                    drawStroke(
                        BrushStroke(path, brushSize, false),
                        imageRect
                    )
                }
            }
        }

        if (!isRefining && overlayMask == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(brushSize.dp * 2)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.4f))
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(
    stroke: BrushStroke,
    imageRect: androidx.compose.ui.geometry.Rect
) {
    if (stroke.points.size < 2) return

    val path = Path()
    val firstPoint = stroke.points.first()
    val startX = imageRect.left + firstPoint.x * imageRect.width
    val startY = imageRect.top + firstPoint.y * imageRect.height
    path.moveTo(startX, startY)

    for (i in 1 until stroke.points.size) {
        val point = stroke.points[i]
        val x = imageRect.left + point.x * imageRect.width
        val y = imageRect.top + point.y * imageRect.height
        path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = Color(0xFF4CAF50).copy(alpha = 0.3f),
        style = Stroke(width = stroke.brushSize * 2)
    )
}

private fun getImageRect(
    canvasSize: androidx.compose.ui.geometry.Size,
    bitmap: Bitmap
): androidx.compose.ui.geometry.Rect {
    val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val canvasAspect = canvasSize.width / canvasSize.height

    val (width, height) = if (imageAspect > canvasAspect) {
        canvasSize.width to canvasSize.width / imageAspect
    } else {
        canvasSize.height * imageAspect to canvasSize.height
    }

    val left = (canvasSize.width - width) / 2
    val top = (canvasSize.height - height) / 2

    return androidx.compose.ui.geometry.Rect(left, top, left + width, top + height)
}

private fun transformTouchToCanvas(
    touchX: Float,
    touchY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    scale: Float,
    offset: Offset
): Offset {
    val centerX = canvasWidth / 2f
    val centerY = canvasHeight / 2f

    val untranslatedX = touchX - offset.x
    val untranslatedY = touchY - offset.y

    val unscaledX = centerX + (untranslatedX - centerX) / scale
    val unscaledY = centerY + (untranslatedY - centerY) / scale

    return Offset(unscaledX, unscaledY)
}

@Composable
fun ObjectRemovalBottomBar(
    removalState: ObjectRemovalState,
    onBrushSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Brush Size",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${removalState.brushSize.toInt()}px",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = removalState.brushSize,
                onValueChange = onBrushSizeChange,
                valueRange = 10f..100f,
                enabled = !removalState.isProcessing,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
fun RefiningMaskAnimation(modifier: Modifier = Modifier) {
    var animationProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            animationProgress = (animationProgress + 0.02f) % 1f
            delay(16)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp)
        ) {
            CircularProgressIndicator(
                progress = animationProgress,
                modifier = Modifier.size(80.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 6.dp
            )
            Icon(
                imageVector = Icons.Default.AutoFixHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Refining mask...",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "AI is analyzing the region",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
