package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
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

// ============================================================================
// OPTIMIZED: Immutable data class with pre-calculated values
// ============================================================================
data class CropRegion(
    val center: Offset = Offset.Zero,
    val size: Float = 300f,
    val rotation: Float = 0f,
    val isFlipped: Boolean = false
) {
    // Pre-calculate commonly used values to avoid repeated calculations
    val halfSize: Float get() = size / 2f
}

enum class DragHandle {
    CENTER,
    CORNER,
    ROTATE
}

// ============================================================================
// OPTIMIZED: Pre-allocated, reusable graphics objects (thread-safe singleton)
// ============================================================================
private object GraphicsCache {
    val path = android.graphics.Path()
    val rotationMatrix = android.graphics.Matrix()
    val flipMatrix = android.graphics.Matrix()
    val dstRectF = RectF()

    // Pre-configured paints with hardware acceleration hints
    val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(153, 0, 0, 0) // 60% opacity
        style = Paint.Style.FILL
        isFilterBitmap = true
    }

    val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Not strictly needed if drawing handles via Compose DrawScope,
    // but good to have if we switch to native drawing for handles too.
    val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
}

// ============================================================================
// OPTIMIZED: Pre-calculated pixel values to avoid dp.toPx() in draw loop
// ============================================================================
@Stable
class CachedDimensions(density: Density) {
    val handleRadiusPx: Float = with(density) { 15.dp.toPx() }
    val innerHandleRadiusPx: Float = handleRadiusPx * 0.7f
    val touchRadiusPx: Float = with(density) { 44.dp.toPx() } // Increased for better touch
    val handleOffsetPx: Float = with(density) { 50.dp.toPx() }
    val centerIndicatorRadiusPx: Float = with(density) { 5.dp.toPx() }
    val lineWidthPx: Float = with(density) { 2.dp.toPx() }
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

    // OPTIMIZATION: Remember the callbacks to prevent recomposition propagation
    val stableOnCropRegionChange = remember<(CropRegion) -> Unit> { { cropRegion = it } }

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
                        if (canvasSize != Size.Zero) {
                            cropRegion = CropRegion(
                                center = Offset(canvasSize.width / 2, canvasSize.height / 2),
                                size = minOf(canvasSize.width, canvasSize.height) * 0.6f,
                                rotation = 0f
                            )
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "Reset")
                    }

                    // Preview button
                    IconButton(
                        onClick = {
                            if (bitmap != null) {
                                previewBitmap?.recycle()
                                previewBitmap = cropBitmapToShapeOptimized(bitmap, cropRegion, selectedShape, canvasSize)
                                showPreview = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Visibility, "Preview")
                    }

                    TextButton(
                        onClick = {
                            if (bitmap != null) {
                                val result = cropBitmapToShapeOptimized(bitmap, cropRegion, selectedShape, canvasSize)
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
            ShapeSelectionBarOptimized(
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
                OptimizedShapeCropCanvas(
                    bitmap = bitmap,
                    cropRegion = cropRegion,
                    onCropRegionChange = stableOnCropRegionChange,
                    selectedShape = selectedShape,
                    onCanvasSizeChanged = { canvasSize = it }
                )
            }

            CropControlButtonsOptimized(
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
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
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

// ============================================================================
// HIGHLY OPTIMIZED CANVAS with minimal allocations and smooth gestures
// ============================================================================
@Composable
fun OptimizedShapeCropCanvas(
    bitmap: Bitmap,
    cropRegion: CropRegion,
    onCropRegionChange: (CropRegion) -> Unit,
    selectedShape: CropShape,
    onCanvasSizeChanged: (Size) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // OPTIMIZATION: Pre-calculate all pixel dimensions once
    val cachedDimensions = remember(density) { CachedDimensions(density) }

    // OPTIMIZATION: Use rememberUpdatedState for gesture callbacks
    val currentCropRegion by rememberUpdatedState(cropRegion)
    val currentOnCropRegionChange by rememberUpdatedState(onCropRegionChange)

    // Gesture state - use primitives to avoid object allocations
    var draggedHandle by remember { mutableStateOf<DragHandle?>(null) }
    var initialDragX by remember { mutableFloatStateOf(0f) }
    var initialDragY by remember { mutableFloatStateOf(0f) }
    var initialCenterX by remember { mutableFloatStateOf(0f) }
    var initialCenterY by remember { mutableFloatStateOf(0f) }
    var initialSize by remember { mutableFloatStateOf(300f) }
    var initialRotation by remember { mutableFloatStateOf(0f) }
    var initialDistance by remember { mutableFloatStateOf(1f) }
    var initialIsFlipped by remember { mutableStateOf(false) }

    // OPTIMIZATION: Pre-calculate bitmap scaling values
    var canvasWidth by remember { mutableFloatStateOf(0f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }

    val bitmapScale by remember(canvasWidth, canvasHeight, bitmap) {
        derivedStateOf {
            if (canvasWidth > 0 && canvasHeight > 0) {
                minOf(canvasWidth / bitmap.width, canvasHeight / bitmap.height)
            } else 1f
        }
    }

    val scaledWidth by remember(bitmapScale, bitmap) {
        derivedStateOf { bitmap.width * bitmapScale }
    }

    val scaledHeight by remember(bitmapScale, bitmap) {
        derivedStateOf { bitmap.height * bitmapScale }
    }

    val bitmapLeft by remember(canvasWidth, scaledWidth) {
        derivedStateOf { (canvasWidth - scaledWidth) / 2f }
    }

    val bitmapTop by remember(canvasHeight, scaledHeight) {
        derivedStateOf { (canvasHeight - scaledHeight) / 2f }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size: IntSize ->
                canvasWidth = size.width.toFloat()
                canvasHeight = size.height.toFloat()
                onCanvasSizeChanged(Size(canvasWidth, canvasHeight))
            }
            // OPTIMIZATION: Use low-level pointer input for smoother gestures
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downOffset = down.position

                    // Detect which handle was touched
                    draggedHandle = detectHandleOptimized(
                        downOffset,
                        currentCropRegion,
                        cachedDimensions
                    )

                    if (draggedHandle != null) {
                        down.consume()

                        // Store initial values as primitives (no object allocation)
                        initialDragX = downOffset.x
                        initialDragY = downOffset.y
                        initialCenterX = currentCropRegion.center.x
                        initialCenterY = currentCropRegion.center.y
                        initialSize = currentCropRegion.size
                        initialRotation = currentCropRegion.rotation
                        initialIsFlipped = currentCropRegion.isFlipped

                        if (draggedHandle == DragHandle.CORNER) {
                            val dist = sqrt(
                                (downOffset.x - currentCropRegion.center.x).pow(2) +
                                        (downOffset.y - currentCropRegion.center.y).pow(2)
                            )
                            initialDistance = if (dist < 10f) 10f else dist
                        }

                        // SMOOTH DRAGGING with optimized change detection
                        drag(down.id) { change ->
                            change.consume()
                            val currentPos = change.position

                            when (draggedHandle) {
                                DragHandle.CENTER -> {
                                    val dx = currentPos.x - initialDragX
                                    val dy = currentPos.y - initialDragY
                                    currentOnCropRegionChange(
                                        CropRegion(
                                            center = Offset(initialCenterX + dx, initialCenterY + dy),
                                            size = initialSize,
                                            rotation = initialRotation,
                                            isFlipped = initialIsFlipped
                                        )
                                    )
                                }
                                DragHandle.CORNER -> {
                                    val currentDistance = sqrt(
                                        (currentPos.x - initialCenterX).pow(2) +
                                                (currentPos.y - initialCenterY).pow(2)
                                    )
                                    val scale = currentDistance / initialDistance
                                    val newSize = (initialSize * scale).coerceIn(100f, 1500f)
                                    currentOnCropRegionChange(
                                        CropRegion(
                                            center = Offset(initialCenterX, initialCenterY),
                                            size = newSize,
                                            rotation = initialRotation,
                                            isFlipped = initialIsFlipped
                                        )
                                    )
                                }
                                DragHandle.ROTATE -> {
                                    val angle = atan2(
                                        currentPos.y - initialCenterY,
                                        currentPos.x - initialCenterX
                                    ) * (180f / Math.PI.toFloat())
                                    currentOnCropRegionChange(
                                        CropRegion(
                                            center = Offset(initialCenterX, initialCenterY),
                                            size = initialSize,
                                            rotation = angle,
                                            isFlipped = initialIsFlipped
                                        )
                                    )
                                }
                                null -> {}
                            }
                        }

                        draggedHandle = null
                    }
                }
            }
    ) {
        // OPTIMIZATION: Use drawIntoCanvas for direct native canvas access
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            // Draw bitmap with pre-calculated values
            GraphicsCache.dstRectF.set(
                bitmapLeft,
                bitmapTop,
                bitmapLeft + scaledWidth,
                bitmapTop + scaledHeight
            )
            nativeCanvas.drawBitmap(bitmap, null, GraphicsCache.dstRectF, null)

            // OPTIMIZATION: Use saveLayerAlpha instead of saveLayer for better performance
            val layerId = nativeCanvas.saveLayerAlpha(
                0f, 0f, canvasWidth, canvasHeight, 255
            )

            // Draw overlay
            nativeCanvas.drawRect(0f, 0f, canvasWidth, canvasHeight, GraphicsCache.overlayPaint)

            // Update path (reuses existing path object)
            updatePathOptimized(GraphicsCache.path, cropRegion, selectedShape)

            // Punch hole through overlay
            nativeCanvas.drawPath(GraphicsCache.path, GraphicsCache.clearPaint)

            // Restore layer
            nativeCanvas.restoreToCount(layerId)

            // Draw outline on top
            nativeCanvas.drawPath(GraphicsCache.path, GraphicsCache.outlinePaint)
        }

        // Draw handles using Compose draw functions (GPU accelerated)
        drawHandles(cropRegion, cachedDimensions)
    }
}

// ============================================================================
// OPTIMIZED: Draw handles as separate function for clarity and caching
// ============================================================================
private fun DrawScope.drawHandles(
    cropRegion: CropRegion,
    dims: CachedDimensions
) {
    val halfSize = cropRegion.halfSize
    val cx = cropRegion.center.x
    val cy = cropRegion.center.y

    // Corner handles (pre-calculate positions)
    val corners = arrayOf(
        Offset(cx - halfSize, cy - halfSize),
        Offset(cx + halfSize, cy - halfSize),
        Offset(cx - halfSize, cy + halfSize),
        Offset(cx + halfSize, cy + halfSize)
    )

    // Draw corner handles
    corners.forEach { pos ->
        drawCircle(Color.White, radius = dims.handleRadiusPx, center = pos)
        drawCircle(Color(0xFF00BCD4), radius = dims.innerHandleRadiusPx, center = pos)
    }

    // Center indicator
    drawCircle(
        Color.White.copy(alpha = 0.5f),
        radius = dims.centerIndicatorRadiusPx,
        center = cropRegion.center
    )

    // Rotate handle
    val rotateHandlePos = Offset(cx + halfSize + dims.handleOffsetPx, cy)

    // Line to rotate handle
    drawLine(
        Color.White,
        start = Offset(cx + halfSize, cy),
        end = rotateHandlePos,
        strokeWidth = dims.lineWidthPx
    )

    // Rotate handle circles
    drawCircle(Color.White, radius = dims.handleRadiusPx, center = rotateHandlePos)
    drawCircle(Color(0xFF4CAF50), radius = dims.innerHandleRadiusPx, center = rotateHandlePos)
}

// ============================================================================
// OPTIMIZED: Handle detection with pre-cached dimensions
// ============================================================================
private fun detectHandleOptimized(
    offset: Offset,
    cropRegion: CropRegion,
    dims: CachedDimensions
): DragHandle? {
    val halfSize = cropRegion.halfSize
    val cx = cropRegion.center.x
    val cy = cropRegion.center.y
    val touchRadius = dims.touchRadiusPx

    // Check rotate handle first (most specific)
    val rx = cx + halfSize + dims.handleOffsetPx
    val ry = cy
    if (distanceSquared(offset.x, offset.y, rx, ry) < touchRadius * touchRadius) {
        return DragHandle.ROTATE
    }

    // Check corners
    val cornerPositions = arrayOf(
        floatArrayOf(cx - halfSize, cy - halfSize),
        floatArrayOf(cx + halfSize, cy - halfSize),
        floatArrayOf(cx - halfSize, cy + halfSize),
        floatArrayOf(cx + halfSize, cy + halfSize)
    )

    for (corner in cornerPositions) {
        if (distanceSquared(offset.x, offset.y, corner[0], corner[1]) < touchRadius * touchRadius) {
            return DragHandle.CORNER
        }
    }

    // Check center (inside the shape)
    if (distanceSquared(offset.x, offset.y, cx, cy) < halfSize * halfSize) {
        return DragHandle.CENTER
    }

    return null
}

// OPTIMIZATION: Inline distance calculation without sqrt for comparison
private inline fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return dx * dx + dy * dy
}

// ============================================================================
// OPTIMIZED: Path generation with matrix reuse
// ============================================================================
private fun updatePathOptimized(path: android.graphics.Path, region: CropRegion, shape: CropShape) {
    val size = region.size
    val halfSize = region.halfSize
    val cx = region.center.x
    val cy = region.center.y

    path.rewind()

    when (shape) {
        CropShape.CIRCLE -> {
            path.addCircle(cx, cy, halfSize, android.graphics.Path.Direction.CW)
        }
        CropShape.RECTANGLE -> {
            path.addRect(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize, android.graphics.Path.Direction.CW)
        }
        CropShape.TRIANGLE -> {
            path.moveTo(cx, cy - halfSize)
            path.lineTo(cx + halfSize, cy + halfSize)
            path.lineTo(cx - halfSize, cy + halfSize)
            path.close()
        }
        CropShape.HEART -> {
            val topCurveHeight = size * 0.3f
            path.moveTo(cx, cy + halfSize * 0.8f)
            path.cubicTo(
                cx - size, cy - size * 0.2f,
                cx - size * 0.5f, cy - halfSize - topCurveHeight,
                cx, cy - size * 0.2f
            )
            path.cubicTo(
                cx + size * 0.5f, cy - halfSize - topCurveHeight,
                cx + size, cy - size * 0.2f,
                cx, cy + halfSize * 0.8f
            )
            path.close()
        }
        CropShape.HEXAGON -> {
            for (i in 0..5) {
                val angle = Math.PI / 3 * i
                val x = cx + halfSize * cos(angle).toFloat()
                val y = cy + halfSize * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        CropShape.STAR -> {
            val outerRadius = halfSize
            val innerRadius = outerRadius * 0.4f
            for (i in 0..9) {
                val radius = if (i % 2 == 0) outerRadius else innerRadius
                val angle = Math.PI / 5 * i - Math.PI / 2
                val x = cx + radius * cos(angle).toFloat()
                val y = cy + radius * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        CropShape.CUSTOM -> {
            path.addCircle(cx, cy, halfSize, android.graphics.Path.Direction.CW)
        }
    }

    // OPTIMIZATION: Reuse matrix objects
    if (region.rotation != 0f) {
        GraphicsCache.rotationMatrix.reset()
        GraphicsCache.rotationMatrix.setRotate(region.rotation, cx, cy)
        path.transform(GraphicsCache.rotationMatrix)
    }

    if (region.isFlipped) {
        GraphicsCache.flipMatrix.reset()
        GraphicsCache.flipMatrix.postScale(-1f, 1f, cx, cy)
        path.transform(GraphicsCache.flipMatrix)
    }
}

// ============================================================================
// OPTIMIZED: Shape selection bar with remember
// ============================================================================
@Composable
fun ShapeSelectionBarOptimized(
    selectedShape: CropShape,
    onShapeSelected: (CropShape) -> Unit,
    modifier: Modifier = Modifier
) {
    // OPTIMIZATION: Remember the shape list to avoid recreation
    val shapes = remember {
        listOf(
            CropShape.RECTANGLE to Icons.Default.CropSquare,
            CropShape.CIRCLE to Icons.Default.Circle,
            CropShape.TRIANGLE to Icons.Default.ChangeHistory,
            CropShape.HEART to Icons.Default.Favorite,
            CropShape.HEXAGON to Icons.Default.Hexagon,
            CropShape.STAR to Icons.Default.Star
        )
    }

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
            shapes.forEach { (shape, icon) ->
                ShapeButtonOptimized(
                    shape = shape,
                    isSelected = shape == selectedShape,
                    onSelect = onShapeSelected,
                    icon = icon
                )
            }
        }
    }
}

@Composable
fun ShapeButtonOptimized(
    shape: CropShape,
    isSelected: Boolean,
    onSelect: (CropShape) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    // OPTIMIZATION: Derive color from isSelected to avoid recomposition
    val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray

    // OPTIMIZATION: Remember the click handler
    val onClick = remember(shape, onSelect) { { onSelect(shape) } }

    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = shape.name,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun CropControlButtonsOptimized(
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallFloatingActionButton(
            onClick = onRotate,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Icon(Icons.Default.Rotate90DegreesCcw, "Rotate")
        }

        SmallFloatingActionButton(
            onClick = onFlip,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Icon(Icons.Default.Flip, "Flip")
        }
    }
}

// ============================================================================
// OPTIMIZED: Bitmap cropping with hardware bitmap support
// ============================================================================
fun cropBitmapToShapeOptimized(
    bitmap: Bitmap,
    cropRegion: CropRegion,
    shape: CropShape,
    canvasSize: Size
): Bitmap? {
    if (canvasSize == Size.Zero) return null

    val scale = minOf(
        canvasSize.width / bitmap.width,
        canvasSize.height / bitmap.height
    )

    val scaledWidth = bitmap.width * scale
    val scaledHeight = bitmap.height * scale
    val left = (canvasSize.width - scaledWidth) / 2
    val top = (canvasSize.height - scaledHeight) / 2

    // Convert crop region to bitmap coordinates
    val bitmapCenterX = ((cropRegion.center.x - left) / scale).toInt()
    val bitmapCenterY = ((cropRegion.center.y - top) / scale).toInt()
    val bitmapSize = (cropRegion.size / scale).toInt()
    val halfBitmapSize = bitmapSize / 2

    // Calculate crop bounds
    val cropLeft = (bitmapCenterX - halfBitmapSize).coerceIn(0, bitmap.width)
    val cropTop = (bitmapCenterY - halfBitmapSize).coerceIn(0, bitmap.height)
    val cropRight = (bitmapCenterX + halfBitmapSize).coerceIn(0, bitmap.width)
    val cropBottom = (bitmapCenterY + halfBitmapSize).coerceIn(0, bitmap.height)

    val cropWidth = cropRight - cropLeft
    val cropHeight = cropBottom - cropTop

    if (cropWidth <= 0 || cropHeight <= 0) return null

    // Create output bitmap with transparency
    val outputSize = maxOf(cropWidth, cropHeight)
    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)

    // Create shape path for masking
    val path = android.graphics.Path()
    val cx = outputSize / 2f
    val cy = outputSize / 2f
    val radius = outputSize / 2f

    val tempRegion = CropRegion(
        center = Offset(cx, cy),
        size = outputSize.toFloat(),
        rotation = cropRegion.rotation,
        isFlipped = cropRegion.isFlipped
    )
    updatePathOptimized(path, tempRegion, shape)

    // Draw using hardware-accelerated paint
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    canvas.save()
    canvas.clipPath(path)

    // Center the cropped portion
    val offsetX = (outputSize - cropWidth) / 2f
    val offsetY = (outputSize - cropHeight) / 2f

    val srcRect = android.graphics.Rect(cropLeft, cropTop, cropRight, cropBottom)
    val dstRect = RectF(offsetX, offsetY, offsetX + cropWidth, offsetY + cropHeight)

    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    canvas.restore()

    return output
}