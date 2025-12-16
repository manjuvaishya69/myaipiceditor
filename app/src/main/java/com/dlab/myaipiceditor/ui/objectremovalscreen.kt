package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    canUndo: Boolean = false,        // ✅ Main app undo state
    canRedo: Boolean = false,        // ✅ Main app redo state
    modifier: Modifier = Modifier
) {
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
                    // ✅ Simple: Just main app undo/redo for removal operations
                    IconButton(
                        onClick = onUndo,
                        enabled = canUndo && !removalState.isProcessing
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            "Undo",
                            tint = if (canUndo && !removalState.isProcessing) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }

                    IconButton(
                        onClick = onRedo,
                        enabled = canRedo && !removalState.isProcessing
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            "Redo",
                            tint = if (canRedo && !removalState.isProcessing) {
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
                            "Reset Strokes",
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
                    onBrushSizeChange = onBrushSizeChange,
                    onToggleEraser = onToggleEraser
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
                    // 'strokes' is for past strokes, we don't need it for live drawing
                    brushSize = removalState.brushSize,
                    isEraserMode = removalState.isEraserMode, // Use the state value
                    onStrokeAdded = onStrokeAdded,
                    overlayMask = removalState.livePreviewOverlay,
                    isRefining = removalState.isRefiningMask,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (removalState.strokes.isEmpty() && !removalState.isProcessing && removalState.livePreviewOverlay == null) {
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

            // This message is now handled by your ViewModel's auto-apply logic,
            // but we can keep it as a visual confirmation.
            if (removalState.strokes.isNotEmpty() && removalState.livePreviewOverlay != null && !removalState.isRefiningMask && !removalState.isProcessing) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp, start = 16.dp, end = 16.dp), // Moved up
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
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Processing in 2s...", // Changed text
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
    brushSize: Float,
    isEraserMode: Boolean,
    onStrokeAdded: (BrushStroke) -> Unit,
    overlayMask: Bitmap? = null,
    isRefining: Boolean = false,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // ⬇️ *** FIX 1: Change from MutableList to immutable List *** ⬇️
    var currentPath by remember { mutableStateOf<List<Offset>?>(null) }
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

                                                val canvasSize = androidx.compose.ui.geometry.Size(
                                                    size.width.toFloat(),
                                                    size.height.toFloat()
                                                )
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
                                                    // ⬇️ *** FIX 2: Create a new list with one point *** ⬇️
                                                    currentPath = listOf(normalizedOffset)
                                                }
                                                change.consume()
                                            } else {
                                                val canvasSize = androidx.compose.ui.geometry.Size(
                                                    size.width.toFloat(),
                                                    size.height.toFloat()
                                                )
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
                                                    // ⬇️ *** FIX 3: Create a *new* list by adding the point *** ⬇️
                                                    currentPath = currentPath?.plus(normalizedOffset)
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
                                                                // ⬇️ *** FIX 4: Use the isEraserMode parameter *** ⬇️
                                                                isEraser = isEraserMode
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
                                    currentPath = null // Cancel drawing if multi-touch starts

                                    val firstChange = event.changes[0]
                                    val secondChange = event.changes[1]

                                    val oldDistance = (firstChange.previousPosition - secondChange.previousPosition).getDistance()
                                    val newDistance = (firstChange.position - secondChange.position).getDistance()

                                    if (oldDistance > 0) {
                                        val zoomChange = newDistance / oldDistance
                                        scale = (scale * zoomChange).coerceIn(0.5f, 5f)

                                        val panX = (firstChange.position.x - firstChange.previousPosition.x +
                                                secondChange.position.x - secondChange.previousPosition.x) / 2
                                        val panY = (firstChange.position.y - firstChange.previousPosition.y +
                                                secondChange.position.y - secondChange.previousPosition.y) / 2

                                        offset = Offset(
                                            offset.x + panX,
                                            offset.y + panY
                                        )

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

            // 1. Draw base image
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

            // 2. Draw live preview overlay (from ViewModel)
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
                    alpha = 0.8f // This is the red overlay from completed strokes
                )
            }

            // 3. Draw current path being drawn (LIVE)
            currentPath?.let { path ->
                if (path.isNotEmpty()) {
                    val pathToDraw = Path()
                    val firstPoint = path.first()
                    val startX = imageRect.left + firstPoint.x * imageRect.width
                    val startY = imageRect.top + firstPoint.y * imageRect.height
                    pathToDraw.moveTo(startX, startY)

                    for (i in 1 until path.size) {
                        val point = path[i]
                        val x = imageRect.left + point.x * imageRect.width
                        val y = imageRect.top + point.y * imageRect.height
                        pathToDraw.lineTo(x, y)
                    }

                    // ⬇️ *** FIX 5: Use isEraserMode for live color *** ⬇️
                    val drawColor = if (isEraserMode) Color.White else Color(0xFFFFDE00).copy(alpha = 0.5f)
                    val blendMode = if (isEraserMode) BlendMode.Clear else BlendMode.SrcOver

                    // Draw a circle for single point, path for multiple points
                    if (path.size == 1) {
                        drawCircle(
                            color = drawColor,
                            radius = brushSize, // This is correct, radius, not width
                            center = androidx.compose.ui.geometry.Offset(startX, startY),
                            blendMode = blendMode
                        )
                    } else {
                        drawPath(
                            path = pathToDraw,
                            color = drawColor,
                            style = Stroke(
                                width = brushSize * 2, // This is correct, width
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            ),
                            blendMode = blendMode
                        )
                    }
                }
            }
        }

        // Brush size indicator
        if (!isRefining && overlayMask == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(brushSize.dp * 2) // Show diameter
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.4f))
            )
        }
    }
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
    onToggleEraser: () -> Unit, // Added this parameter
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

                // Eraser Toggle Button
                FilledIconButton(
                    onClick = onToggleEraser,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (removalState.isEraserMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (removalState.isEraserMode) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Toggle Eraser")
                }
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