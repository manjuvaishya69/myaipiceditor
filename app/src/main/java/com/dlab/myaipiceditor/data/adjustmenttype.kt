package com.dlab.myaipiceditor.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class AdjustmentType(
    val displayName: String,
    val icon: ImageVector,
    val minValue: Float,
    val maxValue: Float,
    val defaultValue: Float
) {
    BRIGHTNESS("Brightness", Icons.Default.WbSunny, -100f, 100f, 0f),
    CONTRAST("Contrast", Icons.Default.Contrast, -100f, 100f, 0f),
    SATURATION("Saturation", Icons.Default.Colorize, -100f, 100f, 0f),
    SHARPNESS("Sharpness", Icons.Default.Brightness4, 0f, 100f, 0f),
    WARMTH("Warmth", Icons.Default.Thermostat, -100f, 100f, 0f),
    HIGHLIGHTS("Highlights", Icons.Default.LightMode, -100f, 100f, 0f),
    SHADOWS("Shadows", Icons.Default.Nightlight, -100f, 100f, 0f),
    TINT("Tint", Icons.Default.Palette, -100f, 100f, 0f)
}

data class AdjustmentValues(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val sharpness: Float = 0f,
    val warmth: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val tint: Float = 0f
) {
    fun getValue(type: AdjustmentType): Float {
        return when (type) {
            AdjustmentType.BRIGHTNESS -> brightness
            AdjustmentType.CONTRAST -> contrast
            AdjustmentType.SATURATION -> saturation
            AdjustmentType.SHARPNESS -> sharpness
            AdjustmentType.WARMTH -> warmth
            AdjustmentType.HIGHLIGHTS -> highlights
            AdjustmentType.SHADOWS -> shadows
            AdjustmentType.TINT -> tint
        }
    }

    fun setValue(type: AdjustmentType, value: Float): AdjustmentValues {
        return when (type) {
            AdjustmentType.BRIGHTNESS -> copy(brightness = value)
            AdjustmentType.CONTRAST -> copy(contrast = value)
            AdjustmentType.SATURATION -> copy(saturation = value)
            AdjustmentType.SHARPNESS -> copy(sharpness = value)
            AdjustmentType.WARMTH -> copy(warmth = value)
            AdjustmentType.HIGHLIGHTS -> copy(highlights = value)
            AdjustmentType.SHADOWS -> copy(shadows = value)
            AdjustmentType.TINT -> copy(tint = value)
        }
    }
}
