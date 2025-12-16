package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*
import com.dlab.myaipiceditor.data.TextLayer
import com.dlab.myaipiceditor.data.TextPosition
import com.dlab.myaipiceditor.data.TextStyle

// =========================================
// MAIN SCREEN
// =========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextStylingScreen(
    text: String,
    currentStyle: TextStyle,
    bitmap: Bitmap?,
    textLayers: List<TextLayer> = emptyList(),
    activeLayerId: String? = null,
    onLayerSelected: (String) -> Unit = {},
    onStyleChange: (TextStyle) -> Unit,
    onPositionChange: (TextPosition) -> Unit,
    onRotationChange: (Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onAddText: () -> Unit,
    onDeleteText: (String) -> Unit,
    onEditText: (String, String) -> Unit = { _, _ -> },
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isZooming by remember { mutableStateOf(false) }

    var selectedCategory by remember { mutableStateOf<StylingCategory?>(null) }

    val currentCategory by remember {
        derivedStateOf { selectedCategory }
    }

    var showLayersSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TextStylingTopBar(
                canUndo, canRedo,
                onUndo, onRedo,
                onCancel, onConfirm,
                onAddText,
                onShowLayers = { showLayersSheet = true },
                layerCount = textLayers.size
            )
        }
    ) { pad ->

        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(pad)
        ) {

            ImagePreviewArea(
                bitmap = bitmap,
                textLayers = textLayers,
                activeLayerId = activeLayerId,
                onLayerSelected = onLayerSelected,
                scale = scale,
                offset = offset,
                showPositionGuide = isZooming,
                onPositionChanged = onPositionChange,
                onZoomChanged = { s, o, z ->
                    scale = s
                    offset = o
                    isZooming = z
                },
                onStyleChanged = onStyleChange,
                onRotationChanged = onRotationChange,
                onDeleteText = onDeleteText,
                onEditText = onEditText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 140.dp)
            )

            AnimatedVisibility(
                visible = isZooming,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Zoom ${(scale * 100).toInt()}%",
                        color = Color.White,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }

            TextStylingBottomPanel(
                selectedCategory = currentCategory,
                onCategorySelected = { category ->
                    selectedCategory =
                        if (selectedCategory == category) null else category
                },
                textStyle = currentStyle,
                onStyleChange = onStyleChange,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showLayersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLayersSheet = false },
            containerColor = Color(0xFF1E1E1E)
        ) {
            LayersPanel(
                textLayers = textLayers,
                activeLayerId = activeLayerId,
                onLayerSelected = { id ->
                    onLayerSelected(id)
                    showLayersSheet = false
                },
                onDeleteLayer = onDeleteText
            )
        }
    }
}

// =========================================
// IMAGE PREVIEW AREA
// =========================================

@Composable
private fun ImagePreviewArea(
    bitmap: Bitmap?,
    textLayers: List<TextLayer>,
    activeLayerId: String?,
    onLayerSelected: (String) -> Unit,
    scale: Float,
    offset: Offset,
    showPositionGuide: Boolean,
    onPositionChanged: (TextPosition) -> Unit,
    onZoomChanged: (Float, Offset, Boolean) -> Unit,
    onStyleChanged: (TextStyle) -> Unit,
    onRotationChanged: (Float) -> Unit,
    onDeleteText: (String) -> Unit,
    onEditText: (String, String) -> Unit,
    modifier: Modifier
) {
    var imageRect by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size

                        if (pointerCount >= 2) {
                            val changes = event.changes

                            if (changes.size >= 2) {
                                val (first, second) = changes.take(2)
                                val currentDistance = (first.position - second.position).getDistance()
                                val previousDistance = (first.previousPosition - second.previousPosition).getDistance()

                                if (previousDistance > 0f && currentDistance > 0f) {
                                    val zoom = currentDistance / previousDistance
                                    val newScale = (scale * zoom).coerceIn(0.5f, 4f)

                                    val pan = changes.fold(Offset.Zero) { acc, change ->
                                        acc + (change.position - change.previousPosition)
                                    } / changes.size.toFloat()

                                    onZoomChanged(newScale, offset + pan, true)
                                    changes.forEach { it.consume() }
                                }
                            }
                        } else {
                            if (showPositionGuide) {
                                onZoomChanged(scale, offset, false)
                            }
                        }
                    }
                }
            }
    ) {
        if (bitmap != null) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { layout ->
                        val width = layout.size.width.toFloat()
                        val height = layout.size.height.toFloat()

                        val canvasRatio = width / height
                        val imgRatio = bitmap.width.toFloat() / bitmap.height

                        val (w, h) = if (imgRatio > canvasRatio) {
                            width to width / imgRatio
                        } else {
                            height * imgRatio to height
                        }

                        val left = (width - w) / 2f
                        val top = (height - h) / 2f

                        imageRect = Rect(Offset(left, top), Size(w, h))
                    }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                if (imageRect.width > 0f) {
                    textLayers.forEach { layer ->
                        key(layer.id) {
                            TextLayerOverlay(
                                layer = layer,
                                imageRect = imageRect,
                                isActive = layer.id == activeLayerId,
                                scale = scale,
                                onTap = { onLayerSelected(layer.id) },
                                onDoubleTap = {
                                    onEditText(layer.id, layer.text)
                                },
                                onPositionChanged = { pos ->
                                    if (layer.id == activeLayerId) onPositionChanged(pos)
                                },
                                onStyleChanged = { st ->
                                    if (layer.id == activeLayerId) onStyleChanged(st)
                                },
                                onRotationChanged = { rot ->
                                    if (layer.id == activeLayerId) onRotationChanged(rot)
                                },
                                onDeleteText = { onDeleteText(layer.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// =========================================
// TEXT OVERLAY WITH HANDLES
// =========================================

@Composable
private fun TextLayerOverlay(
    layer: TextLayer,
    imageRect: Rect,
    isActive: Boolean,
    scale: Float,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onPositionChanged: (TextPosition) -> Unit,
    onStyleChanged: (TextStyle) -> Unit,
    onRotationChanged: (Float) -> Unit,
    onDeleteText: () -> Unit
) {
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current

    var localScaleX by remember(layer.id) { mutableFloatStateOf(layer.style.scaleX) }
    var localScaleY by remember(layer.id) { mutableFloatStateOf(layer.style.scaleY) }
    var rotation by remember(layer.id) { mutableFloatStateOf(layer.rotation) }
    var isInteractingWithHandle by remember { mutableStateOf(false) }

    val pxX = imageRect.left + (layer.position.x * imageRect.width)
    val pxY = imageRect.top + (layer.position.y * imageRect.height)

    val xOffset = with(density) { pxX.toDp() }
    val yOffset = with(density) { pxY.toDp() }

    val textBounds = remember(
        layer.text,
        layer.style.fontSize,
        layer.style.fontFamily,
        layer.style.isBold,
        layer.style.letterSpacing
    ) {
        measureTextBounds(
            text = layer.text,
            fontSize = layer.style.fontSize * density.density,
            fontFamily = layer.style.fontFamily,
            isBold = layer.style.isBold,
            strokeWidth = 0f,
            letterSpacing = layer.style.letterSpacing
        )
    }

    val baseTextWidth = with(density) { textBounds.width().toDp() }
    val baseTextHeight = with(density) { textBounds.height().toDp() }

    val padding = 10.dp

    // ✅ FIX: Calculate scaled dimensions including constant padding
    val scaledTextWidth = baseTextWidth * localScaleX
    val scaledTextHeight = baseTextHeight * localScaleY
    val totalWidth = scaledTextWidth + padding * 2
    val totalHeight = scaledTextHeight + padding * 2

    Box(
        modifier = Modifier
            .offset(xOffset, yOffset)
            .wrapContentSize()
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    rotationZ = rotation
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                }
                .wrapContentSize()
        ) {
            // ✅ FIX: Remove scale from graphicsLayer, apply size with scaled dimensions
            Box(
                modifier = Modifier
                    .size(
                        width = totalWidth,
                        height = totalHeight
                    )
                    .background(layer.style.highlightColor, RoundedCornerShape(6.dp))
                    .pointerInput(layer.id, isActive) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { onDoubleTap() }
                        )
                    }
                    .then(
                        if (isActive && !isInteractingWithHandle) {
                            Modifier.pointerInput(layer.id, scale) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val adjustedDragX = dragAmount.x / scale
                                        val adjustedDragY = dragAmount.y / scale
                                        val newX = (layer.position.x + (adjustedDragX / imageRect.width)).coerceIn(0f, 1f)
                                        val newY = (layer.position.y + (adjustedDragY / imageRect.height)).coerceIn(0f, 1f)
                                        onPositionChanged(TextPosition(newX, newY))
                                    }
                                )
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                val strokePx = layer.style.strokeWidth * density.density
                val innerPaddingPx = strokePx + with(density) { padding.toPx() }

                // ✅ FIX: Canvas size now includes scaled text + padding
                Canvas(
                    modifier = Modifier.size(totalWidth, totalHeight)
                ) {
                    drawIntoCanvas { canvas ->
                        PathTextRenderer.renderText(
                            canvas = canvas.nativeCanvas,
                            text = layer.text,
                            x = size.width / 2f,
                            y = size.height / 2f,
                            fontSize = layer.style.fontSize * density.density,
                            fontFamily = layer.style.fontFamily,
                            isBold = layer.style.isBold,
                            textColor = layer.style.color,
                            opacity = layer.style.opacity,
                            strokeWidth = layer.style.strokeWidth,
                            strokeColor = layer.style.strokeColor,
                            scaleX = localScaleX,  // ✅ Apply scale in renderer
                            scaleY = localScaleY,  // ✅ Apply scale in renderer
                            rotation = 0f,
                            highlightColor = Color.Transparent,
                            highlightPadding = 0f,
                            letterSpacing = layer.style.letterSpacing,
                            shadowRadius = 0f,
                            shadowColor = Color.Transparent,
                            shadowOffset = Offset.Zero,
                            density = density.density,
                            context = context
                        )
                    }
                }
            }

            if (isActive) {
                // ✅ Border uses the correct scaled dimensions
                Box(
                    modifier = Modifier
                        .size(totalWidth, totalHeight)
                        .align(Alignment.Center)
                        .border(1.dp, Color(0xFF5D4037), RoundedCornerShape(6.dp))
                )

                val handleOffset = 9.dp
                val halfWidth = totalWidth / 2
                val halfHeight = totalHeight / 2

                // Top handle (vertical scaling)
                ResizeHandle(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = -(halfHeight + handleOffset)),
                    size = 10.dp,
                    onDragStart = { isInteractingWithHandle = true },
                    onDragEnd = {
                        isInteractingWithHandle = false
                        onStyleChanged(layer.style.copy(scaleY = localScaleY))
                    },
                    onDrag = { drag ->
                        localScaleY = (localScaleY - drag.y * 0.005f).coerceIn(0.3f, 3f)
                    }
                )

                // Left handle (horizontal scaling)
                ResizeHandle(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = -(halfWidth + handleOffset)),
                    size = 10.dp,
                    onDragStart = { isInteractingWithHandle = true },
                    onDragEnd = {
                        isInteractingWithHandle = false
                        onStyleChanged(layer.style.copy(scaleX = localScaleX))
                    },
                    onDrag = { drag ->
                        localScaleX = (localScaleX - drag.x * 0.005f).coerceIn(0.3f, 3f)
                    }
                )

                // Bottom handle (vertical scaling)
                ResizeHandle(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (halfHeight + handleOffset)),
                    size = 10.dp,
                    onDragStart = { isInteractingWithHandle = true },
                    onDragEnd = {
                        isInteractingWithHandle = false
                        onStyleChanged(layer.style.copy(scaleY = localScaleY))
                    },
                    onDrag = { drag ->
                        localScaleY = (localScaleY + drag.y * 0.005f).coerceIn(0.3f, 3f)
                    }
                )

                // Delete button (top-left)
                ControlButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(
                            x = -(halfWidth + 14.dp),
                            y = -(halfHeight + 14.dp)
                        ),
                    icon = Icons.Default.Close,
                    size = 20.dp,
                    onInteractionChange = { isInteractingWithHandle = it }
                ) {
                    onDeleteText()
                }

                // Rotation button (top-right)
                ControlDragButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(
                            x = (halfWidth + 14.dp),
                            y = -(halfHeight + 14.dp)
                        ),
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    size = 20.dp,
                    onDragStart = { isInteractingWithHandle = true },
                    onDragEnd = {
                        isInteractingWithHandle = false
                        onRotationChanged(rotation)
                    },
                    onDrag = { drag ->
                        rotation += drag.x * 0.5f
                    }
                )

                // Reset scale button (middle-right)
                ControlButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = (halfWidth + 14.dp)),
                    icon = Icons.Default.Edit,
                    size = 20.dp,
                    onInteractionChange = { isInteractingWithHandle = it }
                ) {
                    localScaleX = 1f
                    localScaleY = 1f
                    onStyleChanged(layer.style.copy(scaleX = 1f, scaleY = 1f))
                }

                // Uniform scale button (bottom-right)
                ControlDragButton(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(
                            x = (halfWidth + 14.dp),
                            y = (halfHeight + 14.dp)
                        ),
                    icon = Icons.Filled.OpenInFull,
                    size = 20.dp,
                    onDragStart = { isInteractingWithHandle = true },
                    onDragEnd = {
                        isInteractingWithHandle = false
                        onStyleChanged(layer.style.copy(scaleX = localScaleX, scaleY = localScaleY))
                    },
                    onDrag = { drag ->
                        val delta = (drag.x + drag.y) * 0.003f
                        val currentAvg = (localScaleX + localScaleY) / 2f
                        val newScale = (currentAvg + delta).coerceIn(0.3f, 3f)
                        localScaleX = newScale
                        localScaleY = newScale
                    }
                )
            }
        }
    }
}

// FIXED: Uses Path.computeBounds instead of paint.getTextBounds
private fun measureTextBounds(
    text: String,
    fontSize: Float,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    isBold: Boolean,
    strokeWidth: Float,
    letterSpacing: Float = 0f
): android.graphics.RectF {
    val baseTypeface = when (fontFamily) {
        androidx.compose.ui.text.font.FontFamily.Serif -> android.graphics.Typeface.SERIF
        androidx.compose.ui.text.font.FontFamily.SansSerif -> android.graphics.Typeface.SANS_SERIF
        androidx.compose.ui.text.font.FontFamily.Monospace -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.DEFAULT
    }

    val typeface = if (isBold) {
        android.graphics.Typeface.create(baseTypeface, android.graphics.Typeface.BOLD)
    } else {
        baseTypeface
    }

    val paint = android.graphics.Paint().apply {
        this.textSize = fontSize
        this.typeface = typeface
        isAntiAlias = true
    }



    // Use Path to measure accurate bounds including ascenders/descenders
    val path = android.graphics.Path()

    if (letterSpacing == 0f) {
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
    } else {
        // ✅ ADD THIS BLOCK for letter spacing
        var cursorX = 0f
        text.forEach { ch ->
            val s = ch.toString()
            val glyphPath = android.graphics.Path()
            paint.getTextPath(s, 0, 1, cursorX, 0f, glyphPath)
            path.addPath(glyphPath)
            cursorX += paint.measureText(s) + letterSpacing
        }
    }

    paint.getTextPath(text, 0, text.length, 0f, 0f, path)
    val bounds = android.graphics.RectF()
    path.computeBounds(bounds, true)

    // Handle empty text case
    if (bounds.isEmpty) {
        bounds.set(0f, 0f, 1f, fontSize)
    }

    return bounds
}

// =========================================
// HANDLE COMPONENTS
// =========================================

@Composable
private fun ResizeHandle(
    modifier: Modifier,
    size: Dp,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Offset) -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.White, CircleShape)
            .border(1.5.dp, Color(0xFFE0E0E0), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag)
                    }
                )
            }
    )
}

@Composable
private fun ControlButton(
    modifier: Modifier,
    icon: ImageVector,
    size: Dp,
    onInteractionChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.White, CircleShape)
            .border(1.5.dp, Color(0xFFE0E0E0), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onInteractionChange(true)
                        tryAwaitRelease()
                        onInteractionChange(false)
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF212121),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
private fun ControlDragButton(
    modifier: Modifier,
    icon: ImageVector,
    size: Dp,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Offset) -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.White, CircleShape)
            .border(1.5.dp, Color(0xFFE0E0E0), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF212121),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

private fun Offset.getDistance(): Float {
    return kotlin.math.sqrt(x * x + y * y)
}