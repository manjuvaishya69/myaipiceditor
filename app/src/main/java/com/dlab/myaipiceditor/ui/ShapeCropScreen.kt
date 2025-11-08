package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.pow
import kotlin.math.sqrt

enum class CropShape {
    RECTANGLE,
    CIRCLE,
    TRIANGLE,
    HEART,
    HEXAGON,
    STAR,
    CUSTOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShapeCropScreen(
    bitmap: Bitmap?,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedShape by remember { mutableStateOf(CropShape.CIRCLE) }
    var cropRegion by remember { mutableStateOf(CropRegion()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var showPreview by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Initialize crop region when canvas size is known
    LaunchedEffect(canvasSize) {
        if (canvasSize != Size.Zero && cropRegion.center == Offset.Zero) {
            cropRegion = CropRegion(
                center = Offset(canvasSize.width / 2, canvasSize.height / 2),
                size = minOf(canvasSize.width, canvasSize.height) * 0.6f
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shape Crop", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Reset to default size
                        cropRegion = CropRegion(
                            center = Offset(canvasSize.width / 2, canvasSize.height / 2),
                            size = minOf(canvasSize.width, canvasSize.height) * 0.6f,
                            rotation = 0f
                        )
                    }) {
                        Icon(Icons.Default.Refresh, "Reset")
                    }

                    // Preview button
                    IconButton(
                        onClick = {
                            if (bitmap != null) {
                                previewBitmap?.recycle()
                                previewBitmap = cropBitmapToShape(bitmap, cropRegion, selectedShape, canvasSize)
                                showPreview = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Visibility, "Preview")
                    }

                    TextButton(
                        onClick = {
                            if (bitmap != null) {
                                val result = cropBitmapToShape(bitmap, cropRegion, selectedShape, canvasSize)
                                if (result != null) {
                                    onConfirm(result)
                                }
                            }
                        }
                    ) {
                        Text("Apply")
                    }
                }
            )
        },
        bottomBar = {
            ShapeSelectionBar(
                selectedShape = selectedShape,
                onShapeSelected = { selectedShape = it }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            if (bitmap != null) {
                ShapeCropCanvas(
                    bitmap = bitmap,
                    cropRegion = cropRegion,
                    onCropRegionChange = { cropRegion = it },
                    selectedShape = selectedShape,
                    onCanvasSizeChanged = { canvasSize = it }
                )
            }

            // Control buttons overlay
            CropControlButtons(
                cropRegion = cropRegion,
                onRotate = {
                    cropRegion = cropRegion.copy(rotation = (cropRegion.rotation + 45f) % 360f)
                },
                onFlip = {
                    cropRegion = cropRegion.copy(isFlipped = !cropRegion.isFlipped)
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }

    // Preview Dialog
    if (showPreview && previewBitmap != null) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("Preview") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreview = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ShapeCropCanvas(
    bitmap: Bitmap,
    cropRegion: CropRegion,
    onCropRegionChange: (CropRegion) -> Unit,
    selectedShape: CropShape,
    onCanvasSizeChanged: (Size) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggedHandle by remember { mutableStateOf<DragHandle?>(null) }
    var initialDragPosition by remember { mutableStateOf(Offset.Zero) }
    var initialCropRegion by remember { mutableStateOf(CropRegion()) }
    var initialDistance by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                onCanvasSizeChanged(coordinates.size.toSize())
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        draggedHandle = detectHandle(offset, cropRegion)
                        initialDragPosition = offset
                        initialCropRegion = cropRegion

                        // Calculate initial distance for resize
                        if (draggedHandle == DragHandle.CORNER) {
                            initialDistance = sqrt(
                                (offset.x - cropRegion.center.x).pow(2) +
                                        (offset.y - cropRegion.center.y).pow(2)
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val currentPos = change.position

                        when (draggedHandle) {
                            DragHandle.CENTER -> {
                                // Simple position update
                                val newCenter = Offset(
                                    currentPos.x + (initialCropRegion.center.x - initialDragPosition.x),
                                    currentPos.y + (initialCropRegion.center.y - initialDragPosition.y)
                                )
                                onCropRegionChange(cropRegion.copy(center = newCenter))
                            }
                            DragHandle.CORNER -> {
                                // Calculate new size based on current distance from center
                                val currentDistance = sqrt(
                                    (currentPos.x - initialCropRegion.center.x).pow(2) +
                                            (currentPos.y - initialCropRegion.center.y).pow(2)
                                )
                                val newSize = ((currentDistance / initialDistance) * initialCropRegion.size)
                                    .coerceIn(80f, 1200f)
                                onCropRegionChange(cropRegion.copy(size = newSize))
                            }
                            DragHandle.ROTATE -> {
                                // Calculate rotation angle from center
                                val angle = kotlin.math.atan2(
                                    currentPos.y - initialCropRegion.center.y,
                                    currentPos.x - initialCropRegion.center.x
                                ) * (180f / Math.PI.toFloat())
                                onCropRegionChange(cropRegion.copy(rotation = angle))
                            }
                            null -> {}
                        }
                    },
                    onDragEnd = {
                        draggedHandle = null
                    }
                )
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate bitmap drawing area (center and fit)
        val scale = minOf(
            canvasWidth / bitmap.width,
            canvasHeight / bitmap.height
        )
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = (canvasWidth - scaledWidth) / 2
        val top = (canvasHeight - scaledHeight) / 2

        // Draw bitmap
        drawContext.canvas.nativeCanvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight),
            null
        )

        // Create layer for overlay effect
        val layerPaint = Paint()
        val layerId = drawContext.canvas.nativeCanvas.saveLayer(
            0f, 0f, canvasWidth, canvasHeight, layerPaint
        )

        // Draw semi-transparent dark overlay
        drawContext.canvas.nativeCanvas.drawRect(
            0f, 0f, canvasWidth, canvasHeight,
            Paint().apply {
                color = android.graphics.Color.argb(150, 0, 0, 0) // 60% black
                style = Paint.Style.FILL
            }
        )

        // Clear the shape area to show image through
        val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }

        drawContext.canvas.nativeCanvas.apply {
            when (selectedShape) {
                CropShape.CIRCLE -> {
                    drawCircle(
                        cropRegion.center.x,
                        cropRegion.center.y,
                        cropRegion.size / 2,
                        clearPaint
                    )
                }
                CropShape.RECTANGLE -> {
                    val halfSize = cropRegion.size / 2
                    drawRect(
                        cropRegion.center.x - halfSize,
                        cropRegion.center.y - halfSize,
                        cropRegion.center.x + halfSize,
                        cropRegion.center.y + halfSize,
                        clearPaint
                    )
                }
                CropShape.TRIANGLE -> {
                    val path = createTrianglePath(cropRegion)
                    drawPath(path, clearPaint)
                }
                CropShape.HEART -> {
                    val path = createHeartPath(cropRegion)
                    drawPath(path, clearPaint)
                }
                CropShape.HEXAGON -> {
                    val path = createHexagonPath(cropRegion)
                    drawPath(path, clearPaint)
                }
                CropShape.STAR -> {
                    val path = createStarPath(cropRegion)
                    drawPath(path, clearPaint)
                }
                CropShape.CUSTOM -> {
                    drawCircle(
                        cropRegion.center.x,
                        cropRegion.center.y,
                        cropRegion.size / 2,
                        clearPaint
                    )
                }
            }
        }

        // Restore the layer
        drawContext.canvas.nativeCanvas.restoreToCount(layerId)

        // Draw shape outline
        val outlineColor = Color.White
        val outlinePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        when (selectedShape) {
            CropShape.CIRCLE -> {
                drawCircle(
                    color = outlineColor,
                    radius = cropRegion.size / 2,
                    center = cropRegion.center,
                    style = Stroke(width = 4f)
                )
            }
            CropShape.RECTANGLE -> {
                val halfSize = cropRegion.size / 2
                drawRect(
                    color = outlineColor,
                    topLeft = Offset(
                        cropRegion.center.x - halfSize,
                        cropRegion.center.y - halfSize
                    ),
                    size = Size(cropRegion.size, cropRegion.size),
                    style = Stroke(width = 4f)
                )
            }
            else -> {
                val path = when (selectedShape) {
                    CropShape.TRIANGLE -> createTrianglePath(cropRegion)
                    CropShape.HEART -> createHeartPath(cropRegion)
                    CropShape.HEXAGON -> createHexagonPath(cropRegion)
                    CropShape.STAR -> createStarPath(cropRegion)
                    else -> createTrianglePath(cropRegion)
                }
                drawContext.canvas.nativeCanvas.drawPath(path, outlinePaint)
            }
        }

        // Draw control handles with better visibility
        val handleRadius = 24f
        val halfSize = cropRegion.size / 2

        // Corner resize handles (4 corners)
        val corners = listOf(
            Offset(cropRegion.center.x - halfSize, cropRegion.center.y - halfSize), // Top-left
            Offset(cropRegion.center.x + halfSize, cropRegion.center.y - halfSize), // Top-right
            Offset(cropRegion.center.x - halfSize, cropRegion.center.y + halfSize), // Bottom-left
            Offset(cropRegion.center.x + halfSize, cropRegion.center.y + halfSize)  // Bottom-right
        )

        corners.forEach { corner ->
            // Outer circle (white border)
            drawCircle(
                color = Color.White,
                radius = handleRadius,
                center = corner
            )
            // Inner circle (cyan)
            drawCircle(
                color = Color(0xFF00BCD4),
                radius = handleRadius - 5f,
                center = corner
            )
        }

        // Center drag handle
        drawCircle(
            color = Color.White.copy(alpha = 0.3f),
            radius = 50f,
            center = cropRegion.center
        )
        drawCircle(
            color = Color.White,
            radius = 8f,
            center = cropRegion.center
        )

        // Rotate handle (to the right of the shape)
        val rotateHandleOffset = Offset(
            cropRegion.center.x + halfSize + 60f,
            cropRegion.center.y
        )
        // Outer circle (white border)
        drawCircle(
            color = Color.White,
            radius = handleRadius,
            center = rotateHandleOffset
        )
        // Inner circle (green)
        drawCircle(
            color = Color(0xFF4CAF50),
            radius = handleRadius - 5f,
            center = rotateHandleOffset
        )
    }
}

@Composable
fun ShapeSelectionBar(
    selectedShape: CropShape,
    onShapeSelected: (CropShape) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ShapeButton(
                shape = CropShape.RECTANGLE,
                isSelected = selectedShape == CropShape.RECTANGLE,
                onClick = { onShapeSelected(CropShape.RECTANGLE) }
            )
            ShapeButton(
                shape = CropShape.CIRCLE,
                isSelected = selectedShape == CropShape.CIRCLE,
                onClick = { onShapeSelected(CropShape.CIRCLE) }
            )
            ShapeButton(
                shape = CropShape.TRIANGLE,
                isSelected = selectedShape == CropShape.TRIANGLE,
                onClick = { onShapeSelected(CropShape.TRIANGLE) }
            )
            ShapeButton(
                shape = CropShape.HEART,
                isSelected = selectedShape == CropShape.HEART,
                onClick = { onShapeSelected(CropShape.HEART) }
            )
            ShapeButton(
                shape = CropShape.HEXAGON,
                isSelected = selectedShape == CropShape.HEXAGON,
                onClick = { onShapeSelected(CropShape.HEXAGON) }
            )
            ShapeButton(
                shape = CropShape.STAR,
                isSelected = selectedShape == CropShape.STAR,
                onClick = { onShapeSelected(CropShape.STAR) }
            )
        }
    }
}

@Composable
fun ShapeButton(
    shape: CropShape,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = if (isSelected) Color(0xFF00BCD4) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = if (isSelected) Color(0xFF00BCD4) else Color.Gray
        ),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (shape) {
                CropShape.RECTANGLE -> Icon(
                    Icons.Default.CropSquare,
                    contentDescription = "Rectangle",
                    tint = if (isSelected) Color.White else Color.Gray
                )
                CropShape.CIRCLE -> Icon(
                    Icons.Default.Circle,
                    contentDescription = "Circle",
                    tint = if (isSelected) Color.White else Color.Gray
                )
                CropShape.TRIANGLE -> Icon(
                    Icons.Default.ChangeHistory,
                    contentDescription = "Triangle",
                    tint = if (isSelected) Color.White else Color.Gray
                )
                CropShape.HEART -> Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Heart",
                    tint = if (isSelected) Color.White else Color.Gray
                )
                CropShape.HEXAGON -> Icon(
                    Icons.Default.Hexagon,
                    contentDescription = "Hexagon",
                    tint = if (isSelected) Color.White else Color.Gray
                )
                CropShape.STAR -> Icon(
                    Icons.Default.Star,
                    contentDescription = "Star",
                    tint = if (isSelected) Color.White else Color.Gray
                )
                CropShape.CUSTOM -> Icon(
                    Icons.Default.Gesture,
                    contentDescription = "Custom",
                    tint = if (isSelected) Color.White else Color.Gray
                )
            }
        }
    }
}

@Composable
fun CropControlButtons(
    cropRegion: CropRegion,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FloatingActionButton(
            onClick = onRotate,
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Rotate90DegreesCcw, "Rotate")
        }

        FloatingActionButton(
            onClick = onFlip,
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Flip, "Flip")
        }
    }
}

data class CropRegion(
    val center: Offset = Offset.Zero,
    val size: Float = 300f,
    val rotation: Float = 0f,
    val isFlipped: Boolean = false
)

enum class DragHandle {
    CENTER,
    CORNER,
    ROTATE
}

fun detectHandle(offset: Offset, cropRegion: CropRegion): DragHandle? {
    val touchRadius = 60f // Large touch area

    // Check rotate handle first
    val halfSize = cropRegion.size / 2
    val rotateHandlePos = Offset(
        cropRegion.center.x + halfSize + 60f,
        cropRegion.center.y
    )
    val distanceToRotate = sqrt(
        (offset.x - rotateHandlePos.x).pow(2) +
                (offset.y - rotateHandlePos.y).pow(2)
    )
    if (distanceToRotate < touchRadius) {
        return DragHandle.ROTATE
    }

    // Check corners
    val corners = listOf(
        Offset(cropRegion.center.x - halfSize, cropRegion.center.y - halfSize),
        Offset(cropRegion.center.x + halfSize, cropRegion.center.y - halfSize),
        Offset(cropRegion.center.x - halfSize, cropRegion.center.y + halfSize),
        Offset(cropRegion.center.x + halfSize, cropRegion.center.y + halfSize)
    )

    for (corner in corners) {
        val distance = sqrt(
            (offset.x - corner.x).pow(2) +
                    (offset.y - corner.y).pow(2)
        )
        if (distance < touchRadius) {
            return DragHandle.CORNER
        }
    }

    // Check if inside crop region for center drag
    val distanceToCenter = sqrt(
        (offset.x - cropRegion.center.x).pow(2) +
                (offset.y - cropRegion.center.y).pow(2)
    )
    if (distanceToCenter < halfSize - 40f) { // Leave margin for corner handles
        return DragHandle.CENTER
    }

    return null
}

// Path creation functions
fun createTrianglePath(cropRegion: CropRegion): Path {
    val path = Path()
    val size = cropRegion.size / 2
    val cx = cropRegion.center.x
    val cy = cropRegion.center.y

    path.moveTo(cx, cy - size)
    path.lineTo(cx + size, cy + size)
    path.lineTo(cx - size, cy + size)
    path.close()

    return path
}

fun createHeartPath(cropRegion: CropRegion): Path {
    val path = Path()
    val size = cropRegion.size / 2
    val cx = cropRegion.center.x
    val cy = cropRegion.center.y

    path.moveTo(cx, cy + size / 2)

    // Left curve
    path.cubicTo(
        cx - size, cy - size / 2,
        cx - size, cy - size,
        cx, cy - size / 4
    )

    // Right curve
    path.cubicTo(
        cx + size, cy - size,
        cx + size, cy - size / 2,
        cx, cy + size / 2
    )

    path.close()
    return path
}

fun createHexagonPath(cropRegion: CropRegion): Path {
    val path = Path()
    val size = cropRegion.size / 2
    val cx = cropRegion.center.x
    val cy = cropRegion.center.y

    for (i in 0..5) {
        val angle = Math.PI / 3 * i
        val x = cx + size * kotlin.math.cos(angle).toFloat()
        val y = cy + size * kotlin.math.sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

fun createStarPath(cropRegion: CropRegion): Path {
    val path = Path()
    val outerSize = cropRegion.size / 2
    val innerSize = outerSize * 0.4f
    val cx = cropRegion.center.x
    val cy = cropRegion.center.y

    for (i in 0..9) {
        val radius = if (i % 2 == 0) outerSize else innerSize
        val angle = Math.PI / 5 * i - Math.PI / 2
        val x = cx + radius * kotlin.math.cos(angle).toFloat()
        val y = cy + radius * kotlin.math.sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

fun cropBitmapToShape(
    bitmap: Bitmap,
    cropRegion: CropRegion,
    shape: CropShape,
    canvasSize: Size
): Bitmap {
    val size = cropRegion.size.toInt()
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    // Calculate bitmap scaling
    val scale = minOf(
        canvasSize.width / bitmap.width,
        canvasSize.height / bitmap.height
    )
    val scaledWidth = bitmap.width * scale
    val scaledHeight = bitmap.height * scale
    val left = (canvasSize.width - scaledWidth) / 2
    val top = (canvasSize.height - scaledHeight) / 2

    // Calculate crop area relative to original bitmap
    val cropLeft = (cropRegion.center.x - cropRegion.size / 2 - left) / scale
    val cropTop = (cropRegion.center.y - cropRegion.size / 2 - top) / scale
    val cropSize = cropRegion.size / scale

    // Create shape path
    val paint = Paint().apply {
        isAntiAlias = true
    }

    val path = when (shape) {
        CropShape.CIRCLE -> {
            Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            }
        }
        CropShape.RECTANGLE -> {
            Path().apply {
                addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
            }
        }
        CropShape.TRIANGLE -> createTrianglePath(
            CropRegion(center = Offset(size / 2f, size / 2f), size = size.toFloat())
        )
        CropShape.HEART -> createHeartPath(
            CropRegion(center = Offset(size / 2f, size / 2f), size = size.toFloat())
        )
        CropShape.HEXAGON -> createHexagonPath(
            CropRegion(center = Offset(size / 2f, size / 2f), size = size.toFloat())
        )
        CropShape.STAR -> createStarPath(
            CropRegion(center = Offset(size / 2f, size / 2f), size = size.toFloat())
        )
        CropShape.CUSTOM -> {
            Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            }
        }
    }

    canvas.clipPath(path)

    // Draw cropped bitmap
    val srcRect = Rect(
        cropLeft.toInt().coerceIn(0, bitmap.width),
        cropTop.toInt().coerceIn(0, bitmap.height),
        (cropLeft + cropSize).toInt().coerceIn(0, bitmap.width),
        (cropTop + cropSize).toInt().coerceIn(0, bitmap.height)
    )
    val dstRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

    return output
}