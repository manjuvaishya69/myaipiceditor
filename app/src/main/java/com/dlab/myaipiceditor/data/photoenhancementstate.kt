package com.dlab.myaipiceditor.data

import android.graphics.Bitmap

data class PhotoEnhancementState(
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val originalBitmap: Bitmap? = null,
    val enhancedBitmap: Bitmap? = null,
    val showBeforeAfter: Boolean = false,
    val error: String? = null
)
