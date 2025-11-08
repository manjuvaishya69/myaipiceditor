package com.dlab.myaipiceditor.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlab.myaipiceditor.ai.ObjectRemoval
import com.dlab.myaipiceditor.ai.PhotoEnhancement
import com.dlab.myaipiceditor.ai.SmartMaskSnap
import com.dlab.myaipiceditor.data.AdjustmentType
import com.dlab.myaipiceditor.data.AdjustmentValues
import com.dlab.myaipiceditor.data.BrushStroke
import com.dlab.myaipiceditor.data.EditorAction
import com.dlab.myaipiceditor.data.EditorState
import com.dlab.myaipiceditor.data.ObjectRemovalState
import com.dlab.myaipiceditor.data.PhotoEnhancementState
import com.dlab.myaipiceditor.data.TextStyle
import com.dlab.myaipiceditor.data.TextPosition
import com.dlab.myaipiceditor.ui.CropRect
import com.dlab.myaipiceditor.PhotoEditorUtils
import com.dlab.myaipiceditor.ai.AiModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val history = mutableListOf<Bitmap>()
    private var historyIndex = -1

    private val removalStrokeHistory = mutableListOf<List<BrushStroke>>()
    private var removalStrokeIndex = -1

    fun handleAction(action: EditorAction) {
        when (action) {
            is EditorAction.LoadImage -> {
                // This will be handled by the UI when image is selected
            }
            is EditorAction.StartCrop -> startCrop()
            is EditorAction.CancelCrop -> cancelCrop()
            is EditorAction.ConfirmCrop -> confirmCrop(action.cropRect)
            is EditorAction.StartObjectRemoval -> startObjectRemoval()
            is EditorAction.CancelObjectRemoval -> cancelObjectRemoval()
            is EditorAction.ConfirmObjectRemoval -> confirmObjectRemoval()
            is EditorAction.AddRemovalStroke -> addRemovalStroke(action.stroke)
            is EditorAction.UndoRemovalStroke -> undoRemovalStroke()
            is EditorAction.RedoRemovalStroke -> redoRemovalStroke()
            is EditorAction.ResetRemovalStrokes -> resetRemovalStrokes()
            is EditorAction.UpdateBrushSize -> updateBrushSize(action.size)
            is EditorAction.ApplyObjectRemoval -> applyObjectRemoval()
            is EditorAction.RefineAndPreviewMask -> refineAndPreviewMask()
            is EditorAction.AcceptRefinedMask -> acceptRefinedMask()
            is EditorAction.RejectRefinedMask -> rejectRefinedMask()
            is EditorAction.StartPhotoEnhancement -> startPhotoEnhancement()
            is EditorAction.CancelPhotoEnhancement -> cancelPhotoEnhancement()
            is EditorAction.ConfirmPhotoEnhancement -> confirmPhotoEnhancement()
            is EditorAction.RunPhotoEnhancement -> runPhotoEnhancement()
            is EditorAction.ToggleEnhancementBeforeAfter -> toggleEnhancementBeforeAfter()
            is EditorAction.UndoPhotoEnhancement -> undoPhotoEnhancement()
            is EditorAction.ClearEnhancementError -> clearEnhancementError()
            is EditorAction.ResizeImage -> resizeImage(action.width, action.height)
            is EditorAction.RotateImage -> rotateImage(action.degrees)
            is EditorAction.StartAddText -> startAddText()
            is EditorAction.CancelAddText -> cancelAddText()
            is EditorAction.ConfirmText -> confirmText(action.text)
            is EditorAction.StartTextStyling -> startTextStyling()
            is EditorAction.CancelTextStyling -> cancelTextStyling()
            is EditorAction.UpdateTextStyle -> updateTextStyle(action.style)
            is EditorAction.UpdateTextPosition -> updateTextPosition(action.position)
            is EditorAction.ConfirmTextStyling -> confirmTextStyling()
            is EditorAction.StartAdjust -> startAdjust()
            is EditorAction.CancelAdjust -> cancelAdjust()
            is EditorAction.UpdateAdjustment -> updateAdjustment(action.type, action.value)
            is EditorAction.ConfirmAdjust -> confirmAdjust()
            is EditorAction.Undo -> undo()
            is EditorAction.Redo -> redo()
            is EditorAction.SaveImage -> saveImage()
            is EditorAction.ShareImage -> shareImage()
            is EditorAction.ClearError -> clearError()
            is EditorAction.ToggleEraserMode -> toggleEraserMode()
            is EditorAction.BackToStart -> backToStart()
            is EditorAction.ShowSaveDialog -> showSaveDialog()
            is EditorAction.HideSaveDialog -> hideSaveDialog()
            is EditorAction.ShowToolsSheet -> _state.value = _state.value.copy(showingToolsSheet = true)
            is EditorAction.HideToolsSheet -> _state.value = _state.value.copy(showingToolsSheet = false)
            is EditorAction.StartFilters -> _state.value = _state.value.copy(isFilteringImage = true)
            is EditorAction.CancelFilters -> _state.value = _state.value.copy(isFilteringImage = false)
            is EditorAction.ConfirmFilters -> {
                _state.value = _state.value.copy(
                    isFilteringImage = false,
                    currentImage = action.bitmap // Set the new bitmap
                )
                addToHistory(action.bitmap) // Add the change to undo/redo history
            }
            is EditorAction.StartRetouch -> _state.value = _state.value.copy(isRetouchingImage = true)
            is EditorAction.CancelRetouch -> _state.value = _state.value.copy(isRetouchingImage = false)
            is EditorAction.ConfirmRetouch -> {
                _state.value = _state.value.copy(
                    currentImage = action.bitmap,
                    isRetouchingImage = false
                )
                addToHistory(action.bitmap)
            }
            is EditorAction.StartDraw -> _state.value = _state.value.copy(isDrawing = true)
            is EditorAction.CancelDraw -> _state.value = _state.value.copy(isDrawing = false)
            is EditorAction.ConfirmDraw -> _state.value = _state.value.copy(isDrawing = false)
            is EditorAction.StartAddPhoto -> _state.value = _state.value.copy(isAddingPhoto = true)
            is EditorAction.CancelAddPhoto -> _state.value = _state.value.copy(isAddingPhoto = false)
            is EditorAction.ConfirmAddPhoto -> _state.value = _state.value.copy(isAddingPhoto = false)
            is EditorAction.StartLensFlare -> _state.value = _state.value.copy(isAddingLensFlare = true)
            is EditorAction.CancelLensFlare -> _state.value = _state.value.copy(isAddingLensFlare = false)
            is EditorAction.ConfirmLensFlare -> _state.value = _state.value.copy(isAddingLensFlare = false)
            is EditorAction.ShowMakeCollage -> _state.value = _state.value.copy(showingCollageScreen = true)
            is EditorAction.HideMakeCollage -> _state.value = _state.value.copy(showingCollageScreen = false)
            is EditorAction.ShowImageToText -> _state.value = _state.value.copy(showingImageToTextScreen = true)
            is EditorAction.HideImageToText -> _state.value = _state.value.copy(showingImageToTextScreen = false)
            is EditorAction.StartFreeCrop -> _state.value = _state.value.copy(isFreeCropping = true)
            is EditorAction.CancelFreeCrop -> _state.value = _state.value.copy(isFreeCropping = false)
            is EditorAction.ConfirmFreeCrop -> _state.value = _state.value.copy(isFreeCropping = false)
            is EditorAction.StartShapeCrop -> _state.value = _state.value.copy(isShapeCropping = true)
            is EditorAction.CancelShapeCrop -> _state.value = _state.value.copy(isShapeCropping = false)
            is EditorAction.ConfirmShapeCrop -> {
                _state.value = _state.value.copy(
                    currentImage = action.bitmap,  // Set the cropped bitmap as current image
                    isShapeCropping = false,
                    shapeCroppedBitmap = null
                )
                addToHistory(action.bitmap)  // Add to undo/redo history
            }
            is EditorAction.StartStretch -> _state.value = _state.value.copy(isStretching = true)
            is EditorAction.CancelStretch -> _state.value = _state.value.copy(isStretching = false)
            is EditorAction.ConfirmStretch -> _state.value = _state.value.copy(isStretching = false)
            is EditorAction.StartCurves -> _state.value = _state.value.copy(isAdjustingCurves = true)
            is EditorAction.CancelCurves -> _state.value = _state.value.copy(isAdjustingCurves = false)
            is EditorAction.ConfirmCurves -> {
                _state.value = _state.value.copy(
                    currentImage = action.processedBitmap,
                    isAdjustingCurves = false
                )
                addToHistory(action.processedBitmap)
            }
            is EditorAction.StartTiltShift -> _state.value = _state.value.copy(isApplyingTiltShift = true)
            is EditorAction.CancelTiltShift -> _state.value = _state.value.copy(isApplyingTiltShift = false)
            is EditorAction.ConfirmTiltShift -> _state.value = _state.value.copy(isApplyingTiltShift = false)
            is EditorAction.StartFlipRotate -> {
                _state.value = _state.value.copy(isFlipRotating = true)
            }

            is EditorAction.CancelFlipRotate -> {
                _state.value = _state.value.copy(isFlipRotating = false)
            }

            is EditorAction.SetFlipRotateResult -> {
                val bitmap = action.bitmap
                _state.value = _state.value.copy(
                    currentImage = bitmap,
                    isFlipRotating = false
                )
                addToHistory(bitmap)
            }
            else -> {}
        }
    }

    fun setImage(bitmap: Bitmap) {
        _state.value = _state.value.copy(
            originalImage = bitmap,
            currentImage = bitmap,
            error = null
        )
        addToHistory(bitmap)
    }

    private fun startCrop() {
        _state.value = _state.value.copy(isCropping = true)
    }

    private fun cancelCrop() {
        _state.value = _state.value.copy(isCropping = false)
    }

    private fun confirmCrop(cropRect: CropRect) {
        val currentImage = _state.value.currentImage ?: return

        try {
            val result = PhotoEditorUtils.crop(
                currentImage,
                cropRect.left.toInt(),
                cropRect.top.toInt(),
                cropRect.width.toInt(),
                cropRect.height.toInt()
            )
            _state.value = _state.value.copy(
                currentImage = result,
                isCropping = false
            )
            addToHistory(result)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = "Failed to crop image: ${e.message}",
                isCropping = false
            )
        }
    }

    private fun startObjectRemoval() {
        _state.value = _state.value.copy(
            isRemovingObject = true,
            objectRemovalState = ObjectRemovalState(
                showStrokes = true
            )
        )
        removalStrokeHistory.clear()
        removalStrokeHistory.add(emptyList())
        removalStrokeIndex = 0
    }

    private fun cancelObjectRemoval() {
        _state.value = _state.value.copy(
            isRemovingObject = false,
            objectRemovalState = ObjectRemovalState()
        )
        removalStrokeHistory.clear()
        removalStrokeIndex = -1
    }

    private fun confirmObjectRemoval() {
        _state.value = _state.value.copy(
            isRemovingObject = false,
            objectRemovalState = ObjectRemovalState()
        )
        removalStrokeHistory.clear()
        removalStrokeIndex = -1
    }

    private fun addRemovalStroke(stroke: BrushStroke) {
        val currentStrokes = _state.value.objectRemovalState.strokes
        val newStrokes = currentStrokes + stroke

        while (removalStrokeHistory.size > removalStrokeIndex + 1) {
            removalStrokeHistory.removeAt(removalStrokeHistory.size - 1)
        }

        removalStrokeHistory.add(newStrokes)
        removalStrokeIndex = removalStrokeHistory.size - 1

        if (removalStrokeHistory.size > 50) {
            removalStrokeHistory.removeAt(0)
            removalStrokeIndex--
        }

        val canUndo = removalStrokeIndex > 0
        val canRedo = removalStrokeIndex < removalStrokeHistory.size - 1

        android.util.Log.d("ObjectRemoval", "addStroke: index=$removalStrokeIndex, historySize=${removalStrokeHistory.size}, canUndo=$canUndo, canRedo=$canRedo")

        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                strokes = newStrokes,
                canUndo = canUndo,
                canRedo = canRedo
            )
        )

        triggerSmartMaskSnap()
    }

    private fun triggerSmartMaskSnap() {
        val currentImage = _state.value.currentImage ?: return
        val strokes = _state.value.objectRemovalState.strokes

        if (strokes.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    isRefiningMask = true,
                    showStrokes = true,
                    showLivePreview = false,
                    livePreviewOverlay = null
                )
            )

            try {
                AiModelManager.initialize(getApplication())

                // Create rough mask from strokes directly here
                val roughMask = withContext(Dispatchers.Default) {
                    createMaskFromStrokes(
                        currentImage.width,
                        currentImage.height,
                        strokes
                    )
                }

                val refinedMask = withContext(Dispatchers.Default) {
                    SmartMaskSnap.snapToObject(getApplication(), currentImage, roughMask)
                }

                roughMask.recycle()

                _state.value.objectRemovalState.livePreviewOverlay?.recycle()

                _state.value = _state.value.copy(
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        isRefiningMask = false,
                        refinedMaskPreview = refinedMask,
                        livePreviewOverlay = refinedMask.copy(refinedMask.config ?: Bitmap.Config.ARGB_8888, false),
                        showLivePreview = true,
                        showStrokes = false
                    )
                )

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        isRefiningMask = false,
                        showStrokes = true,
                        showLivePreview = false,
                        livePreviewOverlay = null
                    ),
                    error = "Failed to refine mask: ${e.message}"
                )
            }
        }
    }

    /**
     * Creates a grayscale mask bitmap from brush strokes.
     * Moved from MaskRefinement to keep it local.
     */
    private fun createMaskFromStrokes(
        width: Int,
        height: Int,
        strokes: List<BrushStroke>
    ): Bitmap {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.BLACK) // Start with black (empty) mask

        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        strokes.forEach { stroke ->
            paint.color = if (stroke.isEraser) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            paint.strokeWidth = stroke.brushSize * 2f

            if (stroke.points.size > 1) {
                val path = Path()
                val firstPoint = stroke.points.first()

                // Scale normalized points [0, 1] to pixel coordinates
                path.moveTo(firstPoint.x * width, firstPoint.y * height)

                for (i in 1 until stroke.points.size) {
                    val point = stroke.points[i]
                    path.lineTo(point.x * width, point.y * height)
                }

                canvas.drawPath(path, paint)
            }
        }

        return maskBitmap
    }

    private fun undoRemovalStroke() {
        android.util.Log.d("ObjectRemoval", "undoRemovalStroke called: index=$removalStrokeIndex, historySize=${removalStrokeHistory.size}")
        if (removalStrokeIndex > 0) {
            removalStrokeIndex--
            val strokes = removalStrokeHistory[removalStrokeIndex]

            _state.value.objectRemovalState.livePreviewOverlay?.recycle()
            _state.value.objectRemovalState.refinedMaskPreview?.recycle()

            val canUndo = removalStrokeIndex > 0
            val canRedo = removalStrokeIndex < removalStrokeHistory.size - 1

            android.util.Log.d("ObjectRemoval", "undo: newIndex=$removalStrokeIndex, canUndo=$canUndo, canRedo=$canRedo, strokes=${strokes.size}")

            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    strokes = strokes,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    showStrokes = true,
                    showLivePreview = false,
                    livePreviewOverlay = null,
                    refinedMaskPreview = null,
                    isRefiningMask = false
                )
            )
        }
    }

    private fun redoRemovalStroke() {
        android.util.Log.d("ObjectRemoval", "redoRemovalStroke called: index=$removalStrokeIndex, historySize=${removalStrokeHistory.size}")
        if (removalStrokeIndex < removalStrokeHistory.size - 1) {
            removalStrokeIndex++
            val strokes = removalStrokeHistory[removalStrokeIndex]

            _state.value.objectRemovalState.livePreviewOverlay?.recycle()
            _state.value.objectRemovalState.refinedMaskPreview?.recycle()

            val canUndo = removalStrokeIndex > 0
            val canRedo = removalStrokeIndex < removalStrokeHistory.size - 1

            android.util.Log.d("ObjectRemoval", "redo: newIndex=$removalStrokeIndex, canUndo=$canUndo, canRedo=$canRedo, strokes=${strokes.size}")

            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    strokes = strokes,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    showStrokes = true,
                    showLivePreview = false,
                    livePreviewOverlay = null,
                    refinedMaskPreview = null,
                    isRefiningMask = false
                )
            )
        }
    }

    private fun resetRemovalStrokes() {
        android.util.Log.d("ObjectRemoval", "resetRemovalStrokes called")
        removalStrokeHistory.clear()
        removalStrokeHistory.add(emptyList())
        removalStrokeIndex = 0

        _state.value.objectRemovalState.livePreviewOverlay?.recycle()
        _state.value.objectRemovalState.refinedMaskPreview?.recycle()

        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                strokes = emptyList(),
                canUndo = false,
                canRedo = false,
                showStrokes = true,
                showLivePreview = false,
                livePreviewOverlay = null,
                refinedMaskPreview = null,
                isRefiningMask = false
            )
        )
    }

    private fun updateBrushSize(size: Float) {
        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                brushSize = size
            )
        )
    }

    private fun toggleEraserMode() {
        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                isEraserMode = !_state.value.objectRemovalState.isEraserMode
            )
        )
    }

    private fun refineAndPreviewMask() {
        triggerSmartMaskSnap()
    }

    private fun acceptRefinedMask() {
        val currentImage = _state.value.currentImage ?: return
        val refinedMask = _state.value.objectRemovalState.refinedMaskPreview ?: return

        viewModelScope.launch {
            _state.value.objectRemovalState.livePreviewOverlay?.recycle()

            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    isProcessing = true,
                    showLivePreview = false,
                    livePreviewOverlay = null
                )
            )

            try {
                val result = withContext(Dispatchers.IO) {
                    ObjectRemoval.removeObject(getApplication(), currentImage, refinedMask) { progress ->
                        // Optional: update progress in state
                    }
                }

                _state.value = _state.value.copy(
                    currentImage = result,
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        strokes = emptyList(),
                        isProcessing = false,
                        refinedMaskPreview = null,
                        canUndo = false,
                        canRedo = false
                    )
                )
                addToHistory(result)
                removalStrokeHistory.clear()
                removalStrokeHistory.add(emptyList())
                removalStrokeIndex = 0

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        isProcessing = false,
                        showLivePreview = false,
                        livePreviewOverlay = null,
                        refinedMaskPreview = null
                    ),
                    error = "Failed to remove object: ${e.message}"
                )
            }
        }
    }

    private fun rejectRefinedMask() {
        _state.value.objectRemovalState.refinedMaskPreview?.recycle()
        _state.value.objectRemovalState.livePreviewOverlay?.recycle()
        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                showRefinedPreview = false,
                showLivePreview = false,
                refinedMaskPreview = null,
                livePreviewOverlay = null,
                showStrokes = false
            )
        )
    }

    private fun applyObjectRemoval() {
        val currentImage = _state.value.currentImage ?: return
        val strokes = _state.value.objectRemovalState.strokes

        if (strokes.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    isProcessing = true
                )
            )

            try {
                val mask = withContext(Dispatchers.Default) {
                    createMaskFromStrokes(
                        currentImage.width,
                        currentImage.height,
                        strokes
                    )
                }

                val result = withContext(Dispatchers.Default) {
                    ObjectRemoval.removeObject(getApplication(), currentImage, mask) { progress ->
                        // Optional: update progress
                    }
                }

                mask.recycle()

                _state.value = _state.value.copy(
                    currentImage = result,
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        strokes = emptyList(),
                        isProcessing = false,
                        canUndo = false,
                        canRedo = false
                    )
                )
                addToHistory(result)
                removalStrokeHistory.clear()
                removalStrokeHistory.add(emptyList())
                removalStrokeIndex = 0

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        isProcessing = false
                    ),
                    error = "Failed to remove object: ${e.message}"
                )
            }
        }
    }



    private fun resizeImage(width: Int, height: Int) {
        val currentImage = _state.value.currentImage ?: return

        try {
            val result = PhotoEditorUtils.resize(currentImage, width, height)
            _state.value = _state.value.copy(currentImage = result)
            addToHistory(result)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Failed to resize image: ${e.message}")
        }
    }

    private fun rotateImage(degrees: Float) {
        val currentImage = _state.value.currentImage ?: return

        try {
            val result = PhotoEditorUtils.rotate(currentImage, degrees)
            _state.value = _state.value.copy(currentImage = result)
            addToHistory(result)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Failed to rotate image: ${e.message}")
        }
    }

    private fun startAddText() {
        _state.value = _state.value.copy(
            isAddingText = true,
            currentText = "",
            currentTextStyle = TextStyle()
        )
    }

    private fun cancelAddText() {
        _state.value = _state.value.copy(
            isAddingText = false,
            currentText = "",
            currentTextStyle = TextStyle()
        )
    }

    private fun confirmText(text: String) {
        _state.value = _state.value.copy(
            isAddingText = false,
            isStylingText = true,
            currentText = text
        )
    }

    private fun startTextStyling() {
        _state.value = _state.value.copy(isStylingText = true)
    }

    private fun cancelTextStyling() {
        _state.value = _state.value.copy(
            isStylingText = false,
            currentText = "",
            currentTextStyle = TextStyle()
        )
    }

    private fun updateTextStyle(style: TextStyle) {
        _state.value = _state.value.copy(currentTextStyle = style)
    }

    private fun updateTextPosition(position: TextPosition) {
        _state.value = _state.value.copy(textPosition = position)
    }

    private fun confirmTextStyling() {
        val currentImage = _state.value.currentImage ?: return
        val text = _state.value.currentText
        val style = _state.value.currentTextStyle
        val position = _state.value.textPosition
        val density = _state.value.density

        try {
            // Calculate actual position from normalized position
            val x = position.x * currentImage.width
            val y = position.y * currentImage.height

            val result = PhotoEditorUtils.addStyledText(currentImage, text, x, y, style, density)
            _state.value = _state.value.copy(
                currentImage = result,
                isStylingText = false,
                currentText = "",
                currentTextStyle = TextStyle(),
                textPosition = TextPosition()
            )
            addToHistory(result)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = "Failed to add text: ${e.message}",
                isStylingText = false,
                currentText = "",
                currentTextStyle = TextStyle(),
                textPosition = TextPosition()
            )
        }
    }

    fun setDensity(density: Float) {
        _state.value = _state.value.copy(density = density)
    }

    private fun startAdjust() {
        _state.value = _state.value.copy(
            isAdjusting = true,
            adjustmentValues = AdjustmentValues()
        )
    }

    private fun cancelAdjust() {
        _state.value = _state.value.copy(
            isAdjusting = false,
            adjustmentValues = AdjustmentValues()
        )
    }

    private fun updateAdjustment(type: AdjustmentType, value: Float) {
        val newAdjustments = _state.value.adjustmentValues.setValue(type, value)
        _state.value = _state.value.copy(
            adjustmentValues = newAdjustments
        )
    }

    private fun confirmAdjust() {
        val currentImage = _state.value.currentImage ?: return
        val adjustments = _state.value.adjustmentValues

        if (adjustments == AdjustmentValues()) {
            _state.value = _state.value.copy(
                isAdjusting = false,
                adjustmentValues = AdjustmentValues()
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isProcessing = true,
                processingMessage = "Applying adjustments..."
            )

            try {
                val result = withContext(Dispatchers.Default) {
                    PhotoEditorUtils.applyAdjustments(currentImage, adjustments)
                }

                _state.value = _state.value.copy(
                    currentImage = result,
                    isAdjusting = false,
                    adjustmentValues = AdjustmentValues(),
                    isProcessing = false,
                    processingMessage = ""
                )
                addToHistory(result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    processingMessage = "",
                    error = "Failed to apply adjustments: ${e.message}"
                )
            }
        }
    }

    private fun addToHistory(bitmap: Bitmap) {
        // Remove any items after current index
        while (history.size > historyIndex + 1) {
            history.removeAt(history.size - 1)
        }

        history.add(bitmap)
        historyIndex = history.size - 1

        // Limit history size to prevent memory issues
        if (history.size > 10) {
            history.removeAt(0)
            historyIndex--
        }

        updateHistoryState()
    }

    private fun undo() {
        if (historyIndex > 0) {
            historyIndex--
            _state.value = _state.value.copy(currentImage = history[historyIndex])
            updateHistoryState()
        }
    }

    private fun redo() {
        if (historyIndex < history.size - 1) {
            historyIndex++
            _state.value = _state.value.copy(currentImage = history[historyIndex])
            updateHistoryState()
        }
    }

    private fun updateHistoryState() {
        _state.value = _state.value.copy(
            canUndo = historyIndex > 0,
            canRedo = historyIndex < history.size - 1
        )
    }

    private fun saveImage() {
        // This will be implemented in the UI layer
    }

    private fun shareImage() {
        // This will be implemented in the UI layer
    }

    private fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun backToStart() {
        _state.value = EditorState()
        history.clear()
        historyIndex = -1
        removalStrokeHistory.clear()
        removalStrokeIndex = -1
    }

    private fun showSaveDialog() {
        _state.value = _state.value.copy(showingSaveDialog = true)
    }

    private fun hideSaveDialog() {
        _state.value = _state.value.copy(showingSaveDialog = false)
    }

    private fun startPhotoEnhancement() {
        val currentImage = _state.value.currentImage ?: return
        _state.value = _state.value.copy(
            isEnhancingPhoto = true,
            photoEnhancementState = PhotoEnhancementState(
                originalBitmap = currentImage
            )
        )
    }

    private fun cancelPhotoEnhancement() {
        _state.value.photoEnhancementState.enhancedBitmap?.recycle()
        _state.value = _state.value.copy(
            isEnhancingPhoto = false,
            photoEnhancementState = PhotoEnhancementState()
        )
    }

    private fun confirmPhotoEnhancement() {
        val enhancedBitmap = _state.value.photoEnhancementState.enhancedBitmap ?: return

        _state.value = _state.value.copy(
            currentImage = enhancedBitmap,
            isEnhancingPhoto = false,
            photoEnhancementState = PhotoEnhancementState()
        )
        addToHistory(enhancedBitmap)
    }

    private fun runPhotoEnhancement() {
        val originalBitmap = _state.value.photoEnhancementState.originalBitmap ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                photoEnhancementState = _state.value.photoEnhancementState.copy(
                    isProcessing = true,
                    progress = 0f,
                    error = null,
                    enhancedBitmap = null
                )
            )

            try {
                withContext(Dispatchers.IO) {
                    AiModelManager.initialize(getApplication())
                }

                val enhanced = withContext(Dispatchers.Default) {
                    PhotoEnhancement.enhance(getApplication(), originalBitmap) { progress ->
                        _state.value = _state.value.copy(
                            photoEnhancementState = _state.value.photoEnhancementState.copy(
                                progress = progress
                            )
                        )
                    }
                }

                _state.value = _state.value.copy(
                    photoEnhancementState = _state.value.photoEnhancementState.copy(
                        enhancedBitmap = enhanced,
                        isProcessing = false,
                        progress = 1.0f,
                        showBeforeAfter = true
                    )
                )

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    photoEnhancementState = _state.value.photoEnhancementState.copy(
                        isProcessing = false,
                        progress = 0f,
                        error = "Enhancement failed: ${e.message}"
                    )
                )
            }
        }
    }

    private fun toggleEnhancementBeforeAfter() {
        _state.value = _state.value.copy(
            photoEnhancementState = _state.value.photoEnhancementState.copy(
                showBeforeAfter = !_state.value.photoEnhancementState.showBeforeAfter
            )
        )
    }

    private fun undoPhotoEnhancement() {
        _state.value.photoEnhancementState.enhancedBitmap?.recycle()
        val originalBitmap = _state.value.photoEnhancementState.originalBitmap
        _state.value = _state.value.copy(
            photoEnhancementState = PhotoEnhancementState(
                originalBitmap = originalBitmap
            )
        )
    }

    private fun clearEnhancementError() {
        _state.value = _state.value.copy(
            photoEnhancementState = _state.value.photoEnhancementState.copy(
                error = null
            )
        )
    }

}