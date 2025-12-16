@file:OptIn(ExperimentalMaterial3Api::class)

package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dlab.myaipiceditor.data.AdjustmentType
import com.dlab.myaipiceditor.data.AdjustmentValues
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

// ============================================================================
// PERFORMANCE: Pre-calculated color matrix with caching
// ============================================================================

private object ColorMatrixCache {
    private var cachedValues: AdjustmentValues? = null
    private var cachedMatrix: ColorMatrix? = null

    fun getOrCreate(adjustments: AdjustmentValues): ColorMatrix {
        if (cachedValues == adjustments && cachedMatrix != null) {
            return cachedMatrix!!
        }

        val matrix = createOptimizedColorMatrix(adjustments)
        cachedValues = adjustments
        cachedMatrix = matrix
        return matrix
    }

    private fun createOptimizedColorMatrix(adjustments: AdjustmentValues): ColorMatrix {
        val matrix = ColorMatrix()

        // Brightness
        if (adjustments.brightness != 0f) {
            val value = adjustments.brightness * 2.55f
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, value,
                        0f, 1f, 0f, 0f, value,
                        0f, 0f, 1f, 0f, value,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // Contrast
        if (adjustments.contrast != 0f) {
            val contrastValue = (adjustments.contrast + 100f) / 100f
            val offset = (1f - contrastValue) * 128f
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        contrastValue, 0f, 0f, 0f, offset,
                        0f, contrastValue, 0f, 0f, offset,
                        0f, 0f, contrastValue, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // Saturation
        if (adjustments.saturation != 0f) {
            val satValue = (adjustments.saturation + 100f) / 100f
            val saturationMatrix = ColorMatrix()
            saturationMatrix.setToSaturation(satValue)
            matrix.timesAssign(saturationMatrix)
        }

        // Warmth
        if (adjustments.warmth != 0f) {
            val warmthValue = adjustments.warmth / 100f
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        1f + warmthValue * 0.2f, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f - warmthValue * 0.2f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // Tint
        if (adjustments.tint != 0f) {
            val tintValue = adjustments.tint / 100f
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 0f,
                        0f, 1f + tintValue * 0.2f, 0f, 0f, 0f,
                        0f, 0f, 1f - tintValue * 0.2f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // Highlights (affects bright areas - increases/decreases brightness of high values)
        if (adjustments.highlights != 0f) {
            val highlightValue = adjustments.highlights / 100f
            // Apply a curve that affects brighter pixels more
            val factor = 1f + highlightValue * 0.3f
            val offset = highlightValue * 25f
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        factor, 0f, 0f, 0f, offset,
                        0f, factor, 0f, 0f, offset,
                        0f, 0f, factor, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // Shadows (affects dark areas - lightens/darkens shadow regions)
        if (adjustments.shadows != 0f) {
            val shadowValue = adjustments.shadows / 100f
            // Adjust shadows by modifying the offset (lifts or crushes blacks)
            val offset = shadowValue * 30f
            matrix.timesAssign(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, offset,
                        0f, 1f, 0f, 0f, offset,
                        0f, 0f, 1f, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // Note: Sharpness cannot be implemented via ColorMatrix
        // It requires convolution kernel processing which is done separately
        // The sharpness value is stored but applied via RenderScript or similar

        return matrix
    }
}

// ============================================================================
// MAIN SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustScreen(
    bitmap: Bitmap?,
    adjustmentValues: AdjustmentValues,
    onAdjustmentChange: (AdjustmentType, Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAdjustment by remember { mutableStateOf<AdjustmentType?>(null) }
    var showBeforeAfter by remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    // Pre-calculate dimensions
    val density = LocalDensity.current
    val bottomPanelHeight = remember { 200.dp }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            ModernAdjustTopBar(
                canUndo = canUndo,
                canRedo = canRedo,
                onBackClick = onCancel,
                onUndoClick = onUndo,
                onRedoClick = onRedo,
                onConfirmClick = onConfirm,
                showBeforeAfter = showBeforeAfter,
                onToggleBeforeAfter = { showBeforeAfter = !showBeforeAfter }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Image Preview Area
                ImagePreviewSection(
                    bitmap = bitmap,
                    adjustmentValues = adjustmentValues,
                    showOriginal = showBeforeAfter,
                    selectedAdjustment = selectedAdjustment,
                    isDragging = isDraggingSlider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // Bottom Control Panel
                ModernBottomPanel(
                    selectedAdjustment = selectedAdjustment,
                    adjustmentValues = adjustmentValues,
                    onAdjustmentSelect = { adjustment ->
                        selectedAdjustment = if (selectedAdjustment == adjustment) null else adjustment
                    },
                    onAdjustmentChange = onAdjustmentChange,
                    onDraggingChanged = { isDraggingSlider = it },
                    modifier = Modifier.height(bottomPanelHeight)
                )
            }
        }
    }
}

// ============================================================================
// TOP BAR
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernAdjustTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onBackClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onConfirmClick: () -> Unit,
    showBeforeAfter: Boolean,
    onToggleBeforeAfter: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left section
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Undo/Redo group
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = onUndoClick,
                            enabled = canUndo,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                modifier = Modifier.size(20.dp),
                                tint = if (canUndo) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        IconButton(
                            onClick = onRedoClick,
                            enabled = canRedo,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                modifier = Modifier.size(20.dp),
                                tint = if (canRedo) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }

            // Title
            Text(
                text = "Adjust",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Right section
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Before/After toggle
                FilledIconToggleButton(
                    checked = showBeforeAfter,
                    onCheckedChange = { onToggleBeforeAfter() },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        Icons.Outlined.Compare,
                        contentDescription = "Compare",
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Confirm button
                FilledTonalButton(
                    onClick = onConfirmClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply")
                }
            }
        }
    }
}

// ============================================================================
// IMAGE PREVIEW SECTION
// ============================================================================

@Composable
private fun ImagePreviewSection(
    bitmap: Bitmap?,
    adjustmentValues: AdjustmentValues,
    showOriginal: Boolean,
    selectedAdjustment: AdjustmentType?,
    isDragging: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            // Use cached color matrix for performance
            val colorMatrix = remember(adjustmentValues) {
                ColorMatrixCache.getOrCreate(adjustmentValues)
            }

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Image Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                colorFilter = if (showOriginal) null else ColorFilter.colorMatrix(colorMatrix)
            )

            // Before/After indicator
            AnimatedVisibility(
                visible = showOriginal,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "Original",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }

            // Current adjustment indicator while dragging
            AnimatedVisibility(
                visible = isDragging && selectedAdjustment != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                selectedAdjustment?.let { adjustment ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                adjustment.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${adjustment.displayName}: ${adjustmentValues.getValue(adjustment).roundToInt()}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

        } else {
            // No image placeholder
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.ImageNotSupported,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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

// ============================================================================
// BOTTOM PANEL
// ============================================================================

@Composable
private fun ModernBottomPanel(
    selectedAdjustment: AdjustmentType?,
    adjustmentValues: AdjustmentValues,
    onAdjustmentSelect: (AdjustmentType) -> Unit,
    onAdjustmentChange: (AdjustmentType, Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Handle bar
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(2.dp)
                    )
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Slider section (animated)
            AnimatedContent(
                targetState = selectedAdjustment,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith
                            fadeOut(animationSpec = tween(200))
                },
                label = "sliderContent",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 20.dp)
            ) { adjustment ->
                if (adjustment != null) {
                    ModernSliderSection(
                        adjustmentType = adjustment,
                        value = adjustmentValues.getValue(adjustment),
                        onValueChange = { onAdjustmentChange(adjustment, it) },
                        onDraggingChanged = onDraggingChanged
                    )
                } else {
                    // Placeholder when no adjustment selected
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select an adjustment below",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Adjustment chips row
            AdjustmentChipsRow(
                selectedAdjustment = selectedAdjustment,
                adjustmentValues = adjustmentValues,
                onAdjustmentSelect = onAdjustmentSelect,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// SLIDER SECTION
// ============================================================================

@Composable
private fun ModernSliderSection(
    adjustmentType: AdjustmentType,
    value: Float,
    onValueChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    var sliderValue by remember(adjustmentType) { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }

    // Sync with external value changes
    LaunchedEffect(value) {
        if (!isDragging) {
            sliderValue = value
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon with animated background
                val iconBgColor by animateColorAsState(
                    targetValue = if (sliderValue != adjustmentType.defaultValue) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = tween(200),
                    label = "iconBgColor"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(iconBgColor)
                ) {
                    Icon(
                        adjustmentType.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (sliderValue != adjustmentType.defaultValue) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = adjustmentType.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Value display with animation
            val animatedValue by animateFloatAsState(
                targetValue = sliderValue,
                animationSpec = tween(50),
                label = "valueAnimation"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Current value
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (sliderValue != adjustmentType.defaultValue) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = String.format("%+.0f", animatedValue),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (sliderValue != adjustmentType.defaultValue) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // Reset button
                AnimatedVisibility(
                    visible = sliderValue != adjustmentType.defaultValue,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    IconButton(
                        onClick = {
                            sliderValue = adjustmentType.defaultValue
                            onValueChange(adjustmentType.defaultValue)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Reset",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Modern slider with custom track
        ModernSlider(
            value = sliderValue,
            onValueChange = { newValue ->
                sliderValue = newValue
                onValueChange(newValue)
            },
            valueRange = adjustmentType.minValue..adjustmentType.maxValue,
            defaultValue = adjustmentType.defaultValue,
            onDraggingChanged = { dragging ->
                isDragging = dragging
                onDraggingChanged(dragging)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModernSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    onDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isDragged) {
        onDraggingChanged(isDragged)
    }

    // Calculate center position for zero marker
    val range = valueRange.endInclusive - valueRange.start
    val centerPosition = (defaultValue - valueRange.start) / range

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val centerMarkerColor = MaterialTheme.colorScheme.outline

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        interactionSource = interactionSource,
        modifier = modifier
            .height(28.dp)
            .drawBehind {
                // Draw center marker for zero/default position
                val centerX = size.width * centerPosition
                drawLine(
                    color = centerMarkerColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            },
        colors = SliderDefaults.colors(
            thumbColor = primaryColor,
            activeTrackColor = primaryColor,
            inactiveTrackColor = trackColor
        ),
        thumb = {
            // Custom animated thumb
            val scale by animateFloatAsState(
                targetValue = if (isDragged) 1.2f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "thumbScale"
            )

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .scale(scale)
                    .background(primaryColor, CircleShape)
                    .border(3.dp, Color.White, CircleShape)
            )
        }
    )
}

// ============================================================================
// ADJUSTMENT CHIPS ROW
// ============================================================================

@Composable
private fun AdjustmentChipsRow(
    selectedAdjustment: AdjustmentType?,
    adjustmentValues: AdjustmentValues,
    onAdjustmentSelect: (AdjustmentType) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to selected item
    LaunchedEffect(selectedAdjustment) {
        selectedAdjustment?.let { adjustment ->
            val index = AdjustmentType.entries.indexOf(adjustment)
            if (index >= 0) {
                coroutineScope.launch {
                    listState.animateScrollToItem(
                        index = index,
                        scrollOffset = -100
                    )
                }
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        items(AdjustmentType.entries) { adjustment ->
            ModernAdjustmentChip(
                adjustmentType = adjustment,
                isSelected = selectedAdjustment == adjustment,
                hasModification = adjustmentValues.getValue(adjustment) != adjustment.defaultValue,
                value = adjustmentValues.getValue(adjustment),
                onClick = { onAdjustmentSelect(adjustment) }
            )
        }
    }
}

@Composable
private fun ModernAdjustmentChip(
    adjustmentType: AdjustmentType,
    isSelected: Boolean,
    hasModification: Boolean,
    value: Float,
    onClick: () -> Unit
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "chipScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            hasModification -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(200),
        label = "chipBgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            hasModification -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "chipContentColor"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(animatedScale)
            .height(64.dp)
            .widthIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary
            )
        } else null
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                adjustmentType.icon,
                contentDescription = adjustmentType.displayName,
                modifier = Modifier.size(22.dp),
                tint = contentColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = adjustmentType.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Value indicator for modified adjustments
            AnimatedVisibility(
                visible = hasModification,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = String.format("%+.0f", value),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }
        }
    }
}