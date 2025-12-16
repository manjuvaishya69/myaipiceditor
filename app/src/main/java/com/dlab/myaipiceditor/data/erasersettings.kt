package com.dlab.myaipiceditor.data

import androidx.compose.runtime.Stable

@Stable
data class EraserSettings(
    val size: Float = 30f,
    val opacity: Float = 100f,
    val hardness: Float = 100f
) {
    companion object {
        val DEFAULT = EraserSettings()
    }
}