package com.dlab.myaipiceditor.ui

// Enum to define which tool is currently active
enum class RetouchTool {
    NONE,
    AUTO,
    BLEMISH,
    SMOOTH,
    SKIN_TONE,
    WRINKLE,
    TEETH_WHITENING
}

// Data class to hold brush settings, passed from UI to Renderer
data class RetouchBrush(
    val size: Float = 50f,
    val strength: Float = 0.5f, // 0.0 to 1.0
    val hardness: Float = 0.5f  // 0.0 to 1.0
)