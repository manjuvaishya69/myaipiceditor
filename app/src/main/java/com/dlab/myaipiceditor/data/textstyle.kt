package com.dlab.myaipiceditor.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

data class TextStyle(
    val fontSize: Float = 24f,
    val fontFamily: FontFamily = FontFamily.Default,
    val color: Color = Color.Black,
    val highlightColor: Color = Color.Transparent,
    val opacity: Float = 1f,
    val isBold: Boolean = false,
    val scaleX: Float = 1f,  // Horizontal scale
    val scaleY: Float = 1f,  // Vertical scale
    val strokeWidth: Float = 0f,  // ✅ Stroke width (0 = no stroke)
    val strokeColor: Color = Color.White,  // ✅ Stroke color
    val letterSpacing: Float = 0f
)