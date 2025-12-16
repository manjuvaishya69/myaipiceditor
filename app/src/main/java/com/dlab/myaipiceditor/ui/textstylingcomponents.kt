package com.dlab.myaipiceditor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatPaint
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dlab.myaipiceditor.data.TextLayer
import com.dlab.myaipiceditor.data.TextStyle

// ----------------------------
// CATEGORY ENUM
// ----------------------------
enum class StylingCategory(val title: String, val icon: ImageVector) {
    FONT("Font", Icons.Outlined.FontDownload),
    COLOR("Color", Icons.Outlined.Palette),
    HIGHLIGHT("Highlight", Icons.Outlined.FormatPaint),
    STYLE("Style", Icons.Outlined.Tune),
    STROKE("Stroke", Icons.Outlined.BorderColor),
    SHADOW("Shadow", Icons.Outlined.Layers),
    SPACING("Spacing", Icons.Outlined.SpaceBar)
}

// ----------------------------
// TOP BAR
// ----------------------------
@Composable
fun TextStylingTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onAddText: () -> Unit,
    onShowLayers: () -> Unit,
    layerCount: Int
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCancel,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.White,
                containerColor = Color.Black.copy(alpha = 0.4f)
            )
        ) { Icon(Icons.Default.Close, null) }

        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo, null,
                    tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo, null,
                    tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.3f)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Layers button with badge
            Box {
                IconButton(
                    onClick = onShowLayers,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_sort_by_size),
                        contentDescription = "Layers"
                    )
                }

                if (layerCount > 0) {
                    Badge(
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(layerCount.toString())
                    }
                }
            }

            IconButton(
                onClick = onAddText,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Text")
            }

            IconButton(
                onClick = onConfirm,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Icon(Icons.Default.Check, null) }
        }
    }
}

// ----------------------------
// BOTTOM PANEL
// ----------------------------
@Composable
fun TextStylingBottomPanel(
    selectedCategory: StylingCategory?,
    onCategorySelected: (StylingCategory) -> Unit,
    textStyle: TextStyle,
    onStyleChange: (TextStyle) -> Unit,
    text: String = "",
    modifier: Modifier = Modifier
) {
    val colors = listOf(
        Color.White, Color.Black, Color.Red,
        Color(0xFFD81B60), Color(0xFF8E24AA),
        Color(0xFF3949AB), Color(0xFF1E88E5),
        Color(0xFF43A047), Color(0xFFFFC107),
        Color(0xFFFF9800), Color(0xFFFF5722)
    )
    val highlightColors = listOf(Color.Transparent) + colors

    // LEVEL 1 – CACHE FONT LIST
    val fontList = remember { FontCollections.fonts }

    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(bottom = 16.dp)
    ) {

        if (selectedCategory != null) {
            when (selectedCategory) {

                StylingCategory.FONT -> {
                    LazyRow(
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(fontList, key = { it.id }) { font ->
                            FontPreviewCard(
                                fontItem = font,
                                isSelected = textStyle.fontFamily == font.family,
                                onClick = { onStyleChange(textStyle.copy(fontFamily = font.family)) }
                            )
                        }
                    }
                }

                StylingCategory.COLOR -> {
                    LazyRow(
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(colors) { c ->
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                                    .clickable {
                                        onStyleChange(textStyle.copy(color = c))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                ColorCircle(
                                    color = c,
                                    isSelected = c == textStyle.color,
                                    onClick = { onStyleChange(textStyle.copy(color = c)) }
                                )
                            }
                        }
                    }
                }

                StylingCategory.HIGHLIGHT -> {
                    LazyRow(
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(highlightColors) { c ->
                            ColorCircle(
                                color = c,
                                isSelected = c == textStyle.highlightColor,
                                isHighlight = true,
                                onClick = { onStyleChange(textStyle.copy(highlightColor = c)) }
                            )
                        }
                    }
                }

                StylingCategory.STYLE -> {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Size", color = Color.White, modifier = Modifier.width(40.dp))
                            Slider(
                                value = textStyle.fontSize,
                                onValueChange = { onStyleChange(textStyle.copy(fontSize = it)) },
                                valueRange = 12f..120f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Alpha", color = Color.White, modifier = Modifier.width(40.dp))
                            Slider(
                                value = textStyle.opacity,
                                onValueChange = { onStyleChange(textStyle.copy(opacity = it)) },
                                valueRange = 0.1f..1f,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        FilterChip(
                            selected = textStyle.isBold,
                            onClick = { onStyleChange(textStyle.copy(isBold = !textStyle.isBold)) },
                            label = { Text("Bold") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }

                StylingCategory.STROKE -> {
                    // ✅ Stroke functionality - matches screenshot layout
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Amount slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text(
                                "Amount",
                                color = Color.White,
                                modifier = Modifier.width(70.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Slider(
                                value = textStyle.strokeWidth,
                                onValueChange = { onStyleChange(textStyle.copy(strokeWidth = it)) },
                                valueRange = 0f..4f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                "${textStyle.strokeWidth.toInt()}",
                                color = Color.White,
                                modifier = Modifier.width(40.dp),
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        // Color circles
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            items(colors) { c ->
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black)
                                        .clickable {
                                            onStyleChange(textStyle.copy(strokeColor = c))
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    ColorCircle(
                                        color = c,
                                        isSelected = c == textStyle.strokeColor,
                                        onClick = { onStyleChange(textStyle.copy(strokeColor = c)) }
                                    )
                                }
                            }
                        }

                        // None and Color options
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // None option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        onStyleChange(textStyle.copy(strokeWidth = 0f))
                                    }
                                    .padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.DarkGray)
                                        .then(
                                            if (textStyle.strokeWidth == 0f)
                                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.Block,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "None",
                                    color = if (textStyle.strokeWidth == 0f)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        Color.Gray,
                                    fontSize = 12.sp
                                )
                            }

                            // Color option (uses color picker)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        // Already showing colors above, this just highlights it's active
                                        if (textStyle.strokeWidth == 0f) {
                                            onStyleChange(textStyle.copy(strokeWidth = 4f))
                                        }
                                    }
                                    .padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.sweepGradient(
                                                listOf(
                                                    Color.Red,
                                                    Color.Yellow,
                                                    Color.Green,
                                                    Color.Cyan,
                                                    Color.Blue,
                                                    Color.Magenta,
                                                    Color.Red
                                                )
                                            )
                                        )
                                        .then(
                                            if (textStyle.strokeWidth > 0f)
                                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            else Modifier
                                        )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Color",
                                    color = if (textStyle.strokeWidth > 0f)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                StylingCategory.SHADOW -> {
                    // ✅ Placeholder for Shadow functionality
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Shadow options coming soon...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // ✅ Replace the SPACING case in TextStylingBottomPanel with this:

                StylingCategory.SPACING -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Character Spacing slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                "Character",
                                color = Color.White,
                                modifier = Modifier.width(90.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Slider(
                                value = textStyle.letterSpacing,
                                onValueChange = { onStyleChange(textStyle.copy(letterSpacing = it)) },
                                valueRange = 0f..50f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                "${textStyle.letterSpacing.toInt()}",
                                color = Color.White,
                                modifier = Modifier.width(40.dp),
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        // Info text
                        Text(
                            text = "Adjust horizontal space between characters",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.1f),
                thickness = 1.dp
            )
        }

        // ✅ Make category buttons scrollable horizontally
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StylingCategory.entries.forEach { cat ->
                CategoryButton(
                    category = cat,
                    isSelected = cat == selectedCategory,
                    onClick = { onCategorySelected(cat) }
                )
            }
        }
    }
}

// ----------------------------
// CATEGORY BUTTON
// ----------------------------
@Composable
private fun CategoryButton(
    category: StylingCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .padding(horizontal = 8.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            category.icon,
            contentDescription = category.title,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(22.dp)
        )
        Text(
            category.title,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
            fontSize = 12.sp
        )
    }
}

// ----------------------------
// FONT PREVIEW CARD
// ----------------------------
@Composable
private fun FontPreviewCard(
    fontItem: FontCollections.FontItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    key(fontItem.id) {
        val scale = if (isSelected) 1.13f else 1f

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = if (isSelected) Color(0xFF3A5AFF) else Color.Black.copy(alpha = 0.20f),
            modifier = Modifier
                .width(110.dp)
                .height(80.dp)
                .scale(scale)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text(
                    text = fontItem.id,
                    fontFamily = fontItem.family,
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.35f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = fontItem.id,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ----------------------------
// COLOR CIRCLE
// ----------------------------
@Composable
private fun ColorCircle(
    color: Color,
    isSelected: Boolean,
    isHighlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (color == Color.Transparent && isHighlight) Color.DarkGray else color)
            .then(
                if (isSelected)
                    Modifier.border(2.dp, Color.White, CircleShape)
                else
                    Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (color == Color.Transparent && isHighlight) {
            Icon(
                Icons.Outlined.Block,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ----------------------------
// HANDLE BUTTON
// ----------------------------
@Composable
fun HandleButton(
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .then(
                if (onDrag != null) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            onDrag(dragAmount)
                        }
                    }
                } else Modifier
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.padding(4.dp))
    }
}

// ----------------------------
// LAYERS PANEL
// ----------------------------
@Composable
fun LayersPanel(
    textLayers: List<TextLayer>,
    activeLayerId: String?,
    onLayerSelected: (String) -> Unit,
    onDeleteLayer: (String) -> Unit
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
                text = "Text Layers",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "${textLayers.size} layers",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (textLayers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No text layers yet",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            // Show layers in reverse order (newest first)
            textLayers.reversed().forEach { layer ->
                LayerItem(
                    layer = layer,
                    isActive = layer.id == activeLayerId,
                    onSelect = { onLayerSelected(layer.id) },
                    onDelete = { onDeleteLayer(layer.id) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ----------------------------
// LAYER ITEM
// ----------------------------
@Composable
private fun LayerItem(
    layer: TextLayer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            } else {
                Color(0xFF2A2A2A)
            }
        ),
        border = if (isActive) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text preview with style
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = layer.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = layer.style.fontFamily,
                    fontWeight = if (layer.style.isBold) FontWeight.Bold else FontWeight.Normal,
                    color = layer.style.color.copy(alpha = layer.style.opacity),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Size: ${layer.style.fontSize.toInt()}sp",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Eye icon to show it's visible
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_view),
                    contentDescription = "Visible",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )

                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.Red,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}