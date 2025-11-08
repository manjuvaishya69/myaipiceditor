package com.dlab.myaipiceditor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import com.dlab.myaipiceditor.data.TextStyle
import com.dlab.myaipiceditor.data.TextPosition
import android.graphics.Bitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch

data class FontOption(
    val name: String,
    val fontFamily: FontFamily
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextStylingScreen(
    text: String,
    currentStyle: TextStyle,
    bitmap: Bitmap? = null,
    onStyleChange: (TextStyle) -> Unit,
    onPositionChange: (TextPosition) -> Unit = {},
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var textStyle by remember { mutableStateOf(currentStyle) }
    var textPosition by remember { mutableStateOf(TextPosition(x = 0.5f, y = 0.5f)) } // Default to center
    var isDragging by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var imageRect by remember { mutableStateOf(Rect.Zero) }

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isZooming by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val sheetMaxHeight = screenHeightDp / 3

    val bottomSheetState = rememberBottomSheetScaffoldState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(textStyle) {
        onStyleChange(textStyle)
    }

    LaunchedEffect(textPosition) {
        onPositionChange(textPosition)
    }

    val fontOptions = remember {
        listOf(
            FontOption("Default", FontFamily.Default),
            FontOption("Serif", FontFamily.Serif),
            FontOption("Sans Serif", FontFamily.SansSerif),
            FontOption("Monospace", FontFamily.Monospace),
            FontOption("Cursive", FontFamily.Cursive)
        )
    }

    val colorOptions = remember {
        listOf(
            Color.Black, Color.White, Color.Red, Color.Green, Color.Blue,
            Color.Yellow, Color.Cyan, Color.Magenta, Color.Gray,
            Color(0xFF8E24AA), Color(0xFF1976D2), Color(0xFF388E3C),
            Color(0xFFE64A19), Color(0xFFF57C00), Color(0xFF5D4037)
        )
    }

    val highlightOptions = remember {
        listOf(
            Color.Transparent, Color.Yellow.copy(alpha = 0.3f), Color.Green.copy(alpha = 0.3f),
            Color.Blue.copy(alpha = 0.3f), Color.Red.copy(alpha = 0.3f), Color.Cyan.copy(alpha = 0.3f),
            Color.Magenta.copy(alpha = 0.3f), Color.Gray.copy(alpha = 0.3f)
        )
    }

    BottomSheetScaffold(
        scaffoldState = bottomSheetState,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Style Text",
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                        }

                        IconButton(
                            onClick = onUndo,
                            enabled = canUndo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                "Undo",
                                tint = if (canUndo) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                        }

                        IconButton(
                            onClick = onRedo,
                            enabled = canRedo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                "Redo",
                                tint = if (canRedo) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onConfirm) {
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
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
            ) {
                BottomSheetContent(
                    textStyle = textStyle,
                    onStyleChange = { textStyle = it },
                    fontOptions = fontOptions,
                    colorOptions = colorOptions,
                    highlightOptions = highlightOptions
                )
            }
        },
        sheetPeekHeight = 120.dp,
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onGloballyPositioned { coordinates ->
                            canvasSize = coordinates.size
                            // Calculate image rect based on canvas size
                            val canvasAspect = canvasSize.width.toFloat() / canvasSize.height
                            val imageAspect = bitmap.width.toFloat() / bitmap.height

                            val (width, height) = if (imageAspect > canvasAspect) {
                                canvasSize.width.toFloat() to canvasSize.width.toFloat() / imageAspect
                            } else {
                                canvasSize.height.toFloat() * imageAspect to canvasSize.height.toFloat()
                            }

                            val left = (canvasSize.width - width) / 2f
                            val top = (canvasSize.height - height) / 2f

                            imageRect = Rect(
                                offset = Offset(left, top),
                                size = Size(width, height)
                            )
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()

                                    when (event.changes.size) {
                                        1 -> {
                                            val change = event.changes.first()

                                            if (!isZooming) {
                                                if (change.pressed) {
                                                    // Handle single touch for text positioning
                                                    // Transform screen coordinates to image coordinates
                                                    val centerX = size.width / 2f
                                                    val centerY = size.height / 2f

                                                    // Reverse the transformations
                                                    val untranslatedX = change.position.x - offset.x
                                                    val untranslatedY = change.position.y - offset.y

                                                    val unscaledX = centerX + (untranslatedX - centerX) / scale
                                                    val unscaledY = centerY + (untranslatedY - centerY) / scale

                                                    // Check if the touch is within the image bounds
                                                    if (unscaledX >= imageRect.left && unscaledX <= imageRect.right &&
                                                        unscaledY >= imageRect.top && unscaledY <= imageRect.bottom) {

                                                        // Calculate normalized position (0-1)
                                                        val normalizedX = ((unscaledX - imageRect.left) / imageRect.width).coerceIn(0f, 1f)
                                                        val normalizedY = ((unscaledY - imageRect.top) / imageRect.height).coerceIn(0f, 1f)

                                                        textPosition = TextPosition(x = normalizedX, y = normalizedY)
                                                        isDragging = true
                                                    }

                                                    change.consume()
                                                } else {
                                                    isDragging = false
                                                }
                                            }
                                        }
                                        2 -> {
                                            // Handle pinch zoom
                                            isZooming = true
                                            isDragging = false

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

                                                offset = Offset(
                                                    offset.x + panX,
                                                    offset.y + panY
                                                )

                                                // Constrain offset
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
                                        else -> {
                                            isZooming = false
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    // Image with transformations
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image to edit",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Fit
                    )

                    // Text overlay - positioned based on normalized coordinates
                    if (imageRect != Rect.Zero) {
                        val density = LocalDensity.current

                        // Calculate actual text position on the transformed canvas
                        val textX = imageRect.left + (textPosition.x * imageRect.width)
                        val textY = imageRect.top + (textPosition.y * imageRect.height)

                        // Apply the same transformations as the image
                        val centerX = canvasSize.width / 2f
                        val centerY = canvasSize.height / 2f

                        val transformedX = centerX + (textX - centerX) * scale + offset.x
                        val transformedY = centerY + (textY - centerY) * scale + offset.y

                        val xOffset = with(density) { transformedX.toDp() }
                        val yOffset = with(density) { transformedY.toDp() }

                        Box(
                            modifier = Modifier
                                .offset(x = xOffset, y = yOffset)
                                .zIndex(2f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = textStyle.highlightColor,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = text,
                                    fontSize = (textStyle.fontSize * scale).sp, // Scale text with zoom
                                    fontFamily = textStyle.fontFamily,
                                    fontWeight = if (textStyle.isBold) FontWeight.Bold else FontWeight.Normal,
                                    color = textStyle.color.copy(alpha = textStyle.opacity),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            } else {
                // Fallback when no image is provided
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No image available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Instruction overlay
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .zIndex(3f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isZooming) "Pinch to zoom" else "Tap to position text • Pinch to zoom",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (scale != 1f) {
                        Text(
                            text = "Zoom: ${(scale * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Reset zoom button
            if (scale != 1f || offset != Offset.Zero) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .padding(bottom = 140.dp), // Above bottom sheet
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Text("Reset Zoom")
                }
            }
        }
    }
}

// Extension function for calculating distance
private fun Offset.getDistance(): Float {
    return kotlin.math.sqrt(x * x + y * y)
}

@Composable
private fun BottomSheetContent(
    textStyle: TextStyle,
    onStyleChange: (TextStyle) -> Unit,
    fontOptions: List<FontOption>,
    colorOptions: List<Color>,
    highlightOptions: List<Color>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(2.dp)
                )
                .align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Font Selection
        StylingSection(title = "Font") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(fontOptions) { fontOption ->
                    FontOptionCard(
                        fontOption = fontOption,
                        isSelected = textStyle.fontFamily == fontOption.fontFamily,
                        onClick = {
                            onStyleChange(textStyle.copy(fontFamily = fontOption.fontFamily))
                        }
                    )
                }
            }
        }

        // Font Size
        StylingSection(title = "Size") {
            Column {
                Text(
                    text = "${textStyle.fontSize.toInt()}sp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = textStyle.fontSize,
                    onValueChange = { onStyleChange(textStyle.copy(fontSize = it)) },
                    valueRange = 12f..72f,
                    steps = 59,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Bold Toggle
        StylingSection(title = "Style") {
            FilterChip(
                onClick = { onStyleChange(textStyle.copy(isBold = !textStyle.isBold)) },
                label = { Text("Bold") },
                selected = textStyle.isBold,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }

        // Text Color
        StylingSection(title = "Text Color") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(colorOptions) { color ->
                    ColorOption(
                        color = color,
                        isSelected = textStyle.color == color,
                        onClick = {
                            onStyleChange(textStyle.copy(color = color))
                        }
                    )
                }
            }
        }

        // Highlight Color
        StylingSection(title = "Highlight") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(highlightOptions) { color ->
                    ColorOption(
                        color = color,
                        isSelected = textStyle.highlightColor == color,
                        onClick = {
                            onStyleChange(textStyle.copy(highlightColor = color))
                        },
                        showBorder = color == Color.Transparent
                    )
                }
            }
        }

        // Opacity
        StylingSection(title = "Opacity") {
            Column {
                Text(
                    text = "${(textStyle.opacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = textStyle.opacity,
                    onValueChange = { onStyleChange(textStyle.copy(opacity = it)) },
                    valueRange = 0.1f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }
}

@Composable
private fun StylingSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        content()
    }
}

@Composable
private fun FontOptionCard(
    fontOption: FontOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = fontOption.name,
            fontFamily = fontOption.fontFamily,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun ColorOption(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    showBorder: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (showBorder || color == Color.White) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                } else Modifier
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                } else Modifier
            )
            .clickable { onClick() }
    )
}