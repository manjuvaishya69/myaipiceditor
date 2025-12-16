package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

// Data class from CropOverlay.kt
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    bitmap: Bitmap,
    onCropConfirm: (CropRect) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var cropRect by remember { mutableStateOf<CropRect?>(null) }
    var resetKey by remember { mutableStateOf(0) } // Key for forced reset

    val imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Crop Image",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            "Cancel",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    IconButton(
                        onClick = {
                            cropRect?.let { onCropConfirm(it) }
                        },
                        enabled = cropRect != null
                    ) {
                        Icon(
                            Icons.Default.Check,
                            "Confirm",
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
            CropBottomBar(
                onReset = {
                    // Trigger re-creation of CropOverlay to reset its internal state
                    resetKey++
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        containerSize = Size(size.width.toFloat(), size.height.toFloat())
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Image to crop",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                // Crop overlay
                if (containerSize != Size.Zero) {
                    val scale = minOf(
                        containerSize.width / imageSize.width,
                        containerSize.height / imageSize.height
                    )
                    val scaledImageWidth = imageSize.width * scale
                    val scaledImageHeight = imageSize.height * scale
                    val imageOffsetX = (containerSize.width - scaledImageWidth) / 2f
                    val imageOffsetY = (containerSize.height - scaledImageHeight) / 2f

                    val initialCropRect = calculateDefaultCropRect(
                        containerSize,
                        imageSize
                    )

                    CropOverlay(
                        imageSize = imageSize,
                        containerSize = containerSize,
                        imageScale = scale,
                        imageOffset = Offset(imageOffsetX, imageOffsetY),
                        initialCropRect = initialCropRect,
                        onCropRectChange = { newCropRect ->
                            cropRect = newCropRect
                        },
                        modifier = Modifier.fillMaxSize(),
                        key = resetKey // Key for re-composition on reset
                    )
                }
            }

            // Instructions
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Drag the corners and edges to adjust the crop area",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// Function to calculate the default 80% crop rect in screen coordinates
fun calculateDefaultCropRect(
    containerSize: Size,
    imageSize: Size
): CropRect {
    val scale = minOf(
        containerSize.width / imageSize.width,
        containerSize.height / imageSize.height
    )

    val scaledImageWidth = imageSize.width * scale
    val scaledImageHeight = imageSize.height * scale

    val imageOffsetX = (containerSize.width - scaledImageWidth) / 2f
    val imageOffsetY = (containerSize.height - scaledImageHeight) / 2f

    return CropRect(
        left = imageOffsetX + scaledImageWidth * 0.1f,
        top = imageOffsetY + scaledImageHeight * 0.1f,
        right = imageOffsetX + scaledImageWidth * 0.9f,
        bottom = imageOffsetY + scaledImageHeight * 0.9f
    )
}

@Composable
fun CropBottomBar(
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Reset")
            }
        }
    }
}

// CropOverlay composable
@Composable
fun CropOverlay(
    imageSize: Size,
    containerSize: Size,
    imageScale: Float,
    imageOffset: Offset,
    initialCropRect: CropRect,
    onCropRectChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
    key: Any? = null
) {
    val density = LocalDensity.current

    val scale = imageScale
    val imageOffsetX = imageOffset.x
    val imageOffsetY = imageOffset.y
    val scaledImageWidth = imageSize.width * scale
    val scaledImageHeight = imageSize.height * scale

    // Use `initialCropRect` as the initial value, keyed by `key` for reset.
    var cropRect by remember(key) {
        mutableStateOf(initialCropRect)
    }

    // Update parent when crop rect changes
    LaunchedEffect(cropRect) {
        // Convert screen coordinates to image coordinates
        val imageLeft = ((cropRect.left - imageOffsetX) / scale).coerceIn(0f, imageSize.width)
        val imageTop = ((cropRect.top - imageOffsetY) / scale).coerceIn(0f, imageSize.height)
        val imageRight = ((cropRect.right - imageOffsetX) / scale).coerceIn(0f, imageSize.width)
        val imageBottom = ((cropRect.bottom - imageOffsetY) / scale).coerceIn(0f, imageSize.height)

        onCropRectChange(CropRect(imageLeft, imageTop, imageRight, imageBottom))
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    val touchPoint = change.position
                    val handleSize = with(density) { 20.dp.toPx() }
                    val minCropSize = 50f // Minimum size for crop box

                    // Check which handle or area is being dragged
                    when {
                        // Top-left handle
                        abs(touchPoint.x - cropRect.left) < handleSize &&
                                abs(touchPoint.y - cropRect.top) < handleSize -> {
                            cropRect = cropRect.copy(
                                left = (cropRect.left + dragAmount.x).coerceIn(
                                    imageOffsetX,
                                    cropRect.right - minCropSize
                                ),
                                top = (cropRect.top + dragAmount.y).coerceIn(
                                    imageOffsetY,
                                    cropRect.bottom - minCropSize
                                )
                            )
                        }

                        // Top-right handle
                        abs(touchPoint.x - cropRect.right) < handleSize &&
                                abs(touchPoint.y - cropRect.top) < handleSize -> {
                            cropRect = cropRect.copy(
                                right = (cropRect.right + dragAmount.x).coerceIn(
                                    cropRect.left + minCropSize,
                                    imageOffsetX + scaledImageWidth
                                ),
                                top = (cropRect.top + dragAmount.y).coerceIn(
                                    imageOffsetY,
                                    cropRect.bottom - minCropSize
                                )
                            )
                        }

                        // Bottom-left handle
                        abs(touchPoint.x - cropRect.left) < handleSize &&
                                abs(touchPoint.y - cropRect.bottom) < handleSize -> {
                            cropRect = cropRect.copy(
                                left = (cropRect.left + dragAmount.x).coerceIn(
                                    imageOffsetX,
                                    cropRect.right - minCropSize
                                ),
                                bottom = (cropRect.bottom + dragAmount.y).coerceIn(
                                    cropRect.top + minCropSize,
                                    imageOffsetY + scaledImageHeight
                                )
                            )
                        }

                        // Bottom-right handle
                        abs(touchPoint.x - cropRect.right) < handleSize &&
                                abs(touchPoint.y - cropRect.bottom) < handleSize -> {
                            cropRect = cropRect.copy(
                                right = (cropRect.right + dragAmount.x).coerceIn(
                                    cropRect.left + minCropSize,
                                    imageOffsetX + scaledImageWidth
                                ),
                                bottom = (cropRect.bottom + dragAmount.y).coerceIn(
                                    cropRect.top + minCropSize,
                                    imageOffsetY + scaledImageHeight
                                )
                            )
                        }

                        // Left edge
                        abs(touchPoint.x - cropRect.left) < handleSize &&
                                touchPoint.y > cropRect.top && touchPoint.y < cropRect.bottom -> {
                            cropRect = cropRect.copy(
                                left = (cropRect.left + dragAmount.x).coerceIn(
                                    imageOffsetX,
                                    cropRect.right - minCropSize
                                )
                            )
                        }

                        // Right edge
                        abs(touchPoint.x - cropRect.right) < handleSize &&
                                touchPoint.y > cropRect.top && touchPoint.y < cropRect.bottom -> {
                            cropRect = cropRect.copy(
                                right = (cropRect.right + dragAmount.x).coerceIn(
                                    cropRect.left + minCropSize,
                                    imageOffsetX + scaledImageWidth
                                )
                            )
                        }

                        // Top edge
                        abs(touchPoint.y - cropRect.top) < handleSize &&
                                touchPoint.x > cropRect.left && touchPoint.x < cropRect.right -> {
                            cropRect = cropRect.copy(
                                top = (cropRect.top + dragAmount.y).coerceIn(
                                    imageOffsetY,
                                    cropRect.bottom - minCropSize
                                )
                            )
                        }

                        // Bottom edge
                        abs(touchPoint.y - cropRect.bottom) < handleSize &&
                                touchPoint.x > cropRect.left && touchPoint.x < cropRect.right -> {
                            cropRect = cropRect.copy(
                                bottom = (cropRect.bottom + dragAmount.y).coerceIn(
                                    cropRect.top + minCropSize,
                                    imageOffsetY + scaledImageHeight
                                )
                            )
                        }

                        // Inside crop area - move entire rect
                        touchPoint.x > cropRect.left && touchPoint.x < cropRect.right &&
                                touchPoint.y > cropRect.top && touchPoint.y < cropRect.bottom -> {
                            val newLeft = cropRect.left + dragAmount.x
                            val newTop = cropRect.top + dragAmount.y
                            val newRight = cropRect.right + dragAmount.x
                            val newBottom = cropRect.bottom + dragAmount.y

                            // Ensure the entire rect stays within image bounds
                            if (newLeft >= imageOffsetX && newRight <= imageOffsetX + scaledImageWidth &&
                                newTop >= imageOffsetY && newBottom <= imageOffsetY + scaledImageHeight) {
                                cropRect = CropRect(newLeft, newTop, newRight, newBottom)
                            }
                        }
                    }
                }
            }
    ) {
        drawCropOverlay(
            cropRect = cropRect,
            containerSize = Size(size.width, size.height),
            imageOffset = Offset(imageOffsetX, imageOffsetY),
            imageSize = Size(scaledImageWidth, scaledImageHeight)
        )
    }
}

// Fixed drawCropOverlay function
private fun DrawScope.drawCropOverlay(
    cropRect: CropRect,
    containerSize: Size,
    imageOffset: Offset,
    imageSize: Size
) {
    val overlayColor = Color.Black.copy(alpha = 0.5f)
    val cropBorderColor = Color.White
    val handleColor = Color.White
    val handleSize = 20f

    // Define the boundaries of the scaled image content
    val imageLeft = imageOffset.x
    val imageTop = imageOffset.y
    val imageRight = imageOffset.x + imageSize.width
    val imageBottom = imageOffset.y + imageSize.height

    // --- Draw dark overlay outside crop area, constrained by the scaled image boundaries ---

    // 1. Top section of the scaled image, above the crop rect
    if (cropRect.top > imageTop) {
        drawRect(
            color = overlayColor,
            topLeft = Offset(imageLeft, imageTop),
            size = Size(imageSize.width, cropRect.top - imageTop)
        )
    }

    // 2. Bottom section of the scaled image, below the crop rect
    if (cropRect.bottom < imageBottom) {
        drawRect(
            color = overlayColor,
            topLeft = Offset(imageLeft, cropRect.bottom),
            size = Size(imageSize.width, imageBottom - cropRect.bottom)
        )
    }

    // 3. Left section of the scaled image, between crop top and crop bottom
    if (cropRect.left > imageLeft) {
        drawRect(
            color = overlayColor,
            topLeft = Offset(imageLeft, cropRect.top),
            size = Size(cropRect.left - imageLeft, cropRect.height)
        )
    }

    // 4. Right section of the scaled image, between crop top and crop bottom
    if (cropRect.right < imageRight) {
        drawRect(
            color = overlayColor,
            topLeft = Offset(cropRect.right, cropRect.top),
            size = Size(imageRight - cropRect.right, cropRect.height)
        )
    }

    // --- Crop border and handles (No changes needed) ---

    // Draw crop border
    drawRect(
        color = cropBorderColor,
        topLeft = Offset(cropRect.left, cropRect.top),
        size = Size(cropRect.width, cropRect.height),
        style = Stroke(width = 2f)
    )

    // Draw grid lines (rule of thirds)
    val gridColor = cropBorderColor.copy(alpha = 0.5f)

    // Vertical lines
    val verticalLine1 = cropRect.left + cropRect.width / 3f
    val verticalLine2 = cropRect.left + (cropRect.width * 2f) / 3f

    drawLine(
        color = gridColor,
        start = Offset(verticalLine1, cropRect.top),
        end = Offset(verticalLine1, cropRect.bottom),
        strokeWidth = 1f
    )

    drawLine(
        color = gridColor,
        start = Offset(verticalLine2, cropRect.top),
        end = Offset(verticalLine2, cropRect.bottom),
        strokeWidth = 1f
    )

    // Horizontal lines
    val horizontalLine1 = cropRect.top + cropRect.height / 3f
    val horizontalLine2 = cropRect.top + (cropRect.height * 2f) / 3f

    drawLine(
        color = gridColor,
        start = Offset(cropRect.left, horizontalLine1),
        end = Offset(cropRect.right, horizontalLine1),
        strokeWidth = 1f
    )

    drawLine(
        color = gridColor,
        start = Offset(cropRect.left, horizontalLine2),
        end = Offset(cropRect.right, horizontalLine2),
        strokeWidth = 1f
    )

    // Draw corner handles
    val handles = listOf(
        Offset(cropRect.left, cropRect.top),      // Top-left
        Offset(cropRect.right, cropRect.top),     // Top-right
        Offset(cropRect.left, cropRect.bottom),   // Bottom-left
        Offset(cropRect.right, cropRect.bottom)   // Bottom-right
    )

    handles.forEach { handle ->
        drawCircle(
            color = handleColor,
            radius = handleSize / 2f,
            center = handle
        )
        drawCircle(
            color = Color.Black,
            radius = handleSize / 2f,
            center = handle,
            style = Stroke(width = 2f)
        )
    }

    // Draw edge handles (middle of each side)
    val edgeHandles = listOf(
        Offset(cropRect.left, cropRect.top + cropRect.height / 2f),      // Left
        Offset(cropRect.right, cropRect.top + cropRect.height / 2f),     // Right
        Offset(cropRect.left + cropRect.width / 2f, cropRect.top),       // Top
        Offset(cropRect.left + cropRect.width / 2f, cropRect.bottom)     // Bottom
    )

    edgeHandles.forEach { handle ->
        drawRect(
            color = handleColor,
            topLeft = Offset(handle.x - handleSize / 4f, handle.y - handleSize / 4f),
            size = Size(handleSize / 2f, handleSize / 2f)
        )
        drawRect(
            color = Color.Black,
            topLeft = Offset(handle.x - handleSize / 4f, handle.y - handleSize / 4f),
            size = Size(handleSize / 2f, handleSize / 2f),
            style = Stroke(width = 2f)
        )
    }
}