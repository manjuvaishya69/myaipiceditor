package com.dlab.myaipiceditor.data

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import com.dlab.myaipiceditor.ui.CropRect

@Stable
data class EditorState(
    val originalImage: Bitmap? = null,
    val currentImage: Bitmap? = null,
    val isCropping: Boolean = false,
    val isProcessing: Boolean = false,
    val processingMessage: String = "",
    val error: String? = null,
    val history: List<Bitmap> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isAddingText: Boolean = false,
    val isStylingText: Boolean = false,
    val currentText: String = "",
    val currentTextStyle: TextStyle = TextStyle(),
    val textPosition: TextPosition = TextPosition(),
    val density: Float = 2f,
    val isAdjusting: Boolean = false,
    val adjustmentValues: AdjustmentValues = AdjustmentValues(),
    val isRemovingObject: Boolean = false,
    val objectRemovalState: ObjectRemovalState = ObjectRemovalState(),
    val showingSaveDialog: Boolean = false,
    val isEnhancingPhoto: Boolean = false,
    val photoEnhancementState: PhotoEnhancementState = PhotoEnhancementState(),
    val showingToolsSheet: Boolean = false,
    val isFilteringImage: Boolean = false,
    val isRetouchingImage: Boolean = false,
    val isDrawing: Boolean = false,
    val isAddingPhoto: Boolean = false,
    val isAddingLensFlare: Boolean = false,
    val showingCollageScreen: Boolean = false,
    val showingImageToTextScreen: Boolean = false
)

data class TextPosition(
    val x: Float = 0.5f, // Normalized position (0-1)
    val y: Float = 0.5f  // Normalized position (0-1)
)

sealed class EditorAction {
    object LoadImage : EditorAction()
    object TakePhoto : EditorAction()
    object StartCrop : EditorAction()
    object CancelCrop : EditorAction()
    data class ConfirmCrop(val cropRect: CropRect) : EditorAction()
    object StartObjectRemoval : EditorAction()
    object CancelObjectRemoval : EditorAction()
    object ConfirmObjectRemoval : EditorAction()
    data class AddRemovalStroke(val stroke: BrushStroke) : EditorAction()
    object UndoRemovalStroke : EditorAction()
    object RedoRemovalStroke : EditorAction()
    object ResetRemovalStrokes : EditorAction()
    data class UpdateBrushSize(val size: Float) : EditorAction()
    object ApplyObjectRemoval : EditorAction()
    object RefineAndPreviewMask : EditorAction()
    object AcceptRefinedMask : EditorAction()
    object RejectRefinedMask : EditorAction()
    object RestoreFace : EditorAction()
    object UpscaleImage : EditorAction()
    object StartPhotoEnhancement : EditorAction()
    object CancelPhotoEnhancement : EditorAction()
    object ConfirmPhotoEnhancement : EditorAction()
    object RunPhotoEnhancement : EditorAction()
    object ToggleEnhancementBeforeAfter : EditorAction()
    object UndoPhotoEnhancement : EditorAction()
    object ClearEnhancementError : EditorAction()
    data class ResizeImage(val width: Int, val height: Int) : EditorAction()
    data class RotateImage(val degrees: Float) : EditorAction()
    object StartAddText : EditorAction()
    object CancelAddText : EditorAction()
    data class ConfirmText(val text: String) : EditorAction()
    object StartTextStyling : EditorAction()
    object CancelTextStyling : EditorAction()
    data class UpdateTextStyle(val style: TextStyle) : EditorAction()
    data class UpdateTextPosition(val position: TextPosition) : EditorAction()
    object ConfirmTextStyling : EditorAction()
    object StartAdjust : EditorAction()
    object CancelAdjust : EditorAction()
    data class UpdateAdjustment(val type: AdjustmentType, val value: Float) : EditorAction()
    object ConfirmAdjust : EditorAction()
    object Undo : EditorAction()
    object Redo : EditorAction()
    object SaveImage : EditorAction()
    object ShareImage : EditorAction()
    object ClearError : EditorAction()
    object ToggleEraserMode : EditorAction()
    object BackToStart : EditorAction()
    object ShowSaveDialog : EditorAction()
    object HideSaveDialog : EditorAction()
    object ShowToolsSheet : EditorAction()
    object HideToolsSheet : EditorAction()
    object StartFilters : EditorAction()
    object CancelFilters : EditorAction()
    object ConfirmFilters : EditorAction()
    object StartRetouch : EditorAction()
    object CancelRetouch : EditorAction()
    object ConfirmRetouch : EditorAction()
    object StartDraw : EditorAction()
    object CancelDraw : EditorAction()
    object ConfirmDraw : EditorAction()
    object StartAddPhoto : EditorAction()
    object CancelAddPhoto : EditorAction()
    object ConfirmAddPhoto : EditorAction()
    object StartLensFlare : EditorAction()
    object CancelLensFlare : EditorAction()
    object ConfirmLensFlare : EditorAction()
    object ShowMakeCollage : EditorAction()
    object HideMakeCollage : EditorAction()
    object ShowImageToText : EditorAction()
    object HideImageToText : EditorAction()
}