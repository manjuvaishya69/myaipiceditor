package com.dlab.myaipiceditor.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Enhanced Retouch Tool Descriptions and Settings
 */
object RetouchToolInfo {
    fun getDescription(tool: RetouchTool): String = when (tool) {
        RetouchTool.BLEMISH -> "Remove blemishes, spots, and imperfections with smart healing"
        RetouchTool.SMOOTH -> "Smooth skin texture while preserving natural details"
        RetouchTool.SKIN_TONE -> "Even out skin tone and add warmth"
        RetouchTool.WRINKLE -> "Reduce wrinkles and fine lines naturally"
        RetouchTool.TEETH_WHITENING -> "Brighten and whiten teeth"
        RetouchTool.AUTO -> "Automatic face retouching with AI - detects and enhances skin automatically"
        RetouchTool.NONE -> ""
    }

    fun getOptimalSettings(tool: RetouchTool): RetouchBrush = when (tool) {
        RetouchTool.BLEMISH -> RetouchBrush(size = 40f, strength = 0.8f, hardness = 0.7f)
        RetouchTool.SMOOTH -> RetouchBrush(size = 60f, strength = 0.5f, hardness = 0.3f)
        RetouchTool.SKIN_TONE -> RetouchBrush(size = 70f, strength = 0.4f, hardness = 0.2f)
        RetouchTool.WRINKLE -> RetouchBrush(size = 50f, strength = 0.6f, hardness = 0.4f)
        RetouchTool.TEETH_WHITENING -> RetouchBrush(size = 35f, strength = 0.7f, hardness = 0.6f)
        else -> RetouchBrush()
    }

    fun getToolColor(tool: RetouchTool): Color = when (tool) {
        RetouchTool.BLEMISH -> Color(0xFFFF6B6B)
        RetouchTool.SMOOTH -> Color(0xFF4ECDC4)
        RetouchTool.SKIN_TONE -> Color(0xFFFFBE76)
        RetouchTool.WRINKLE -> Color(0xFF95E1D3)
        RetouchTool.TEETH_WHITENING -> Color(0xFFF8F9FA)
        RetouchTool.AUTO -> Color(0xFFFFD93D)
        RetouchTool.NONE -> Color.White
    }

    fun getToolTip(tool: RetouchTool): String = when (tool) {
        RetouchTool.BLEMISH -> "Tap or drag over blemishes to remove them"
        RetouchTool.SMOOTH -> "Paint over skin areas to smooth texture"
        RetouchTool.SKIN_TONE -> "Apply to skin to even out tone and add warmth"
        RetouchTool.WRINKLE -> "Brush over wrinkles and fine lines to reduce them"
        RetouchTool.TEETH_WHITENING -> "Paint carefully on teeth to whiten"
        RetouchTool.AUTO -> "Automatically detects and enhances faces"
        RetouchTool.NONE -> ""
    }
}

/**
 * Quick Settings Card for each tool with presets
 */
@Composable
fun QuickToolSettings(
    tool: RetouchTool,
    settings: RetouchBrush,
    onSettingsChange: (RetouchBrush) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .statusBarsPadding(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(RetouchToolInfo.getToolColor(tool).copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (tool) {
                            RetouchTool.BLEMISH -> Icons.Default.Circle
                            RetouchTool.SMOOTH -> Icons.Default.Spa
                            RetouchTool.SKIN_TONE -> Icons.Default.Palette
                            RetouchTool.WRINKLE -> Icons.Default.Face
                            RetouchTool.TEETH_WHITENING -> Icons.Default.SentimentSatisfied
                            else -> Icons.Default.Brush
                        },
                        contentDescription = null,
                        tint = RetouchToolInfo.getToolColor(tool),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Brush Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = RetouchToolInfo.getDescription(tool),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Size slider with preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(settings.size.coerceIn(20f, 60f).dp)
                        .clip(CircleShape)
                        .background(RetouchToolInfo.getToolColor(tool).copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size((settings.size * settings.hardness).coerceIn(10f, 50f).dp)
                            .clip(CircleShape)
                            .background(RetouchToolInfo.getToolColor(tool).copy(alpha = 0.6f))
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Brush Size",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${settings.size.toInt()}px",
                            style = MaterialTheme.typography.labelLarge,
                            color = RetouchToolInfo.getToolColor(tool),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.size,
                        onValueChange = { onSettingsChange(settings.copy(size = it)) },
                        valueRange = 10f..150f,
                        colors = SliderDefaults.colors(
                            thumbColor = RetouchToolInfo.getToolColor(tool),
                            activeTrackColor = RetouchToolInfo.getToolColor(tool),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Strength slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Opacity,
                    contentDescription = null,
                    tint = RetouchToolInfo.getToolColor(tool),
                    modifier = Modifier.size(32.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Strength",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(settings.strength * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = RetouchToolInfo.getToolColor(tool),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.strength,
                        onValueChange = { onSettingsChange(settings.copy(strength = it)) },
                        valueRange = 0.1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = RetouchToolInfo.getToolColor(tool),
                            activeTrackColor = RetouchToolInfo.getToolColor(tool),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardness slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Lens,
                    contentDescription = null,
                    tint = RetouchToolInfo.getToolColor(tool),
                    modifier = Modifier.size(32.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hardness",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(settings.hardness * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = RetouchToolInfo.getToolColor(tool),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.hardness,
                        onValueChange = { onSettingsChange(settings.copy(hardness = it)) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = RetouchToolInfo.getToolColor(tool),
                            activeTrackColor = RetouchToolInfo.getToolColor(tool),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quick preset buttons
            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onSettingsChange(settings.copy(strength = 0.3f)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.LightMode, null, modifier = Modifier.size(20.dp))
                        Text("Light", style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedButton(
                    onClick = { onSettingsChange(settings.copy(strength = 0.6f)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.WbSunny, null, modifier = Modifier.size(20.dp))
                        Text("Medium", style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedButton(
                    onClick = { onSettingsChange(settings.copy(strength = 0.9f)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Whatshot, null, modifier = Modifier.size(20.dp))
                        Text("Strong", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Reset button
            OutlinedButton(
                onClick = {
                    onSettingsChange(RetouchToolInfo.getOptimalSettings(tool))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = RetouchToolInfo.getToolColor(tool)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, RetouchToolInfo.getToolColor(tool))
            ) {
                Icon(Icons.Default.Refresh, "Reset", modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset to Optimal Settings", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Tool Tips Overlay
 */
@Composable
fun RetouchToolTip(
    tool: RetouchTool,
    modifier: Modifier = Modifier
) {
    if (tool != RetouchTool.NONE && tool != RetouchTool.AUTO) {
        Card(
            modifier = modifier
                .padding(16.dp)
                .widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(
                containerColor = RetouchToolInfo.getToolColor(tool).copy(alpha = 0.95f)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )

                Text(
                    text = RetouchToolInfo.getToolTip(tool),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Before/After Comparison Toggle
 */
@Composable
fun BeforeAfterToggle(
    isShowingBefore: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.8f)
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !isShowingBefore,
                onClick = { onToggle(false) },
                label = {
                    Text(
                        "After",
                        fontWeight = if (!isShowingBefore) FontWeight.Bold else FontWeight.Normal
                    )
                },
                leadingIcon = if (!isShowingBefore) {
                    { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                    labelColor = Color.White.copy(alpha = 0.7f)
                )
            )

            FilterChip(
                selected = isShowingBefore,
                onClick = { onToggle(true) },
                label = {
                    Text(
                        "Before",
                        fontWeight = if (isShowingBefore) FontWeight.Bold else FontWeight.Normal
                    )
                },
                leadingIcon = if (isShowingBefore) {
                    { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                    selectedLabelColor = Color.White,
                    labelColor = Color.White.copy(alpha = 0.7f)
                )
            )
        }
    }
}

/**
 * Processing Indicator with Tool Info and Animation
 */
@Composable
fun RetouchProcessingIndicator(
    tool: RetouchTool,
    message: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Animated icon
            Box(
                contentAlignment = Alignment.Center
            ) {
                // Outer pulsing circle
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(RetouchToolInfo.getToolColor(tool).copy(alpha = 0.2f))
                )

                // Inner circle
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(RetouchToolInfo.getToolColor(tool).copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (tool) {
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
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message.ifEmpty { "Processing..." },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Please wait while we enhance your photo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = RetouchToolInfo.getToolColor(tool),
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}