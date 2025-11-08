package com.dlab.myaipiceditor.ui

import androidx.compose.ui.graphics.ColorMatrix
import com.dlab.myaipiceditor.data.AdjustmentValues

object AdjustmentColorMatrix {

    fun createColorMatrix(adjustments: AdjustmentValues): ColorMatrix {
        val matrix = ColorMatrix()

        if (adjustments.brightness != 0f) {
            val value = adjustments.brightness * 2.55f
            val brightnessMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, value,
                    0f, 1f, 0f, 0f, value,
                    0f, 0f, 1f, 0f, value,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.timesAssign(brightnessMatrix)
        }

        if (adjustments.contrast != 0f) {
            val contrastValue = (adjustments.contrast + 100f) / 100f
            val offset = (1f - contrastValue) * 128f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrastValue, 0f, 0f, 0f, offset,
                    0f, contrastValue, 0f, 0f, offset,
                    0f, 0f, contrastValue, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.timesAssign(contrastMatrix)
        }

        if (adjustments.saturation != 0f) {
            val satValue = (adjustments.saturation + 100f) / 100f
            val saturationMatrix = ColorMatrix()
            saturationMatrix.setToSaturation(satValue)
            matrix.timesAssign(saturationMatrix)
        }

        if (adjustments.warmth != 0f) {
            val warmthValue = adjustments.warmth / 100f
            val warmthMatrix = ColorMatrix(
                floatArrayOf(
                    1f + warmthValue * 0.2f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f - warmthValue * 0.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.timesAssign(warmthMatrix)
        }

        if (adjustments.tint != 0f) {
            val tintValue = adjustments.tint / 100f
            val tintMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f + tintValue * 0.2f, 0f, 0f, 0f,
                    0f, 0f, 1f - tintValue * 0.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.timesAssign(tintMatrix)
        }

        return matrix
    }
}
