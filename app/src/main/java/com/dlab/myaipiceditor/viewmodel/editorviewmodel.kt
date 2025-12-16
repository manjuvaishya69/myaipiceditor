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
import com.dlab.myaipiceditor.data.TextLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job // ⬇️ ADDED THIS IMPORT
import kotlinx.coroutines.delay // ⬇️ ADDED THIS IMPORT
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

    // ⬇️ ADDED THIS LINE ⬇️
    // Job to track the 2-second auto-apply timer
    private var removalJob: Job? = null

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
            is EditorAction.UpdateTextRotation -> updateTextRotation(action.rotation)
            is EditorAction.ConfirmTextStyling -> confirmTextStyling()
            is EditorAction.SelectTextLayer -> selectTextLayer(action.id)
            is EditorAction.UpdateLayerText -> updateLayerText(action.layerId, action.newText)
            is EditorAction.DeleteActiveTextLayer -> deleteActiveTextLayer()
            is EditorAction.DeleteTextLayer -> deleteTextLayer(action.id)
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
        // ✅ Remove all stroke history code
    }

    // ⬇️ MODIFIED THIS FUNCTION ⬇️
    private fun cancelObjectRemoval() {
        // Logic to exit without applying changes, reverting to previous state.
        removalJob?.cancel()
        // Recycle overlays to prevent memory leaks
        _state.value.objectRemovalState.livePreviewOverlay?.recycle()
        _state.value.objectRemovalState.refinedMaskPreview?.recycle()
        _state.value = _state.value.copy(
            isRemovingObject = false,
            objectRemovalState = ObjectRemovalState() // Reset tool state
        )
    }

    private fun confirmObjectRemoval() {
        // 1. Trigger the removal process to apply the changes to the image
        applyObjectRemoval()

        // 2. The main fix: Close the object removal screen and reset its state
        //    This is what makes the UI switch back to the main editor.
        removalJob?.cancel() // Stop any ongoing job

        _state.value.objectRemovalState.livePreviewOverlay?.recycle()
        _state.value.objectRemovalState.refinedMaskPreview?.recycle()

        _state.value = _state.value.copy(
            isRemovingObject = false, // <-- KEY: This closes the screen
            objectRemovalState = ObjectRemovalState() // Reset tool state
        )
    }

    // ⬇️ MODIFIED THIS FUNCTION ⬇️
    private fun addRemovalStroke(stroke: BrushStroke) {
        val currentImage = _state.value.currentImage ?: return
        val currentStrokes = _state.value.objectRemovalState.strokes
        val newStrokes = currentStrokes + stroke

        // ✅ No history tracking needed

        // Generate live preview mask immediately
        val liveOverlay = generateLiveMaskOverlay(
            currentImage.width,
            currentImage.height,
            newStrokes
        )

        // Recycle old overlay
        _state.value.objectRemovalState.livePreviewOverlay?.recycle()

        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                strokes = newStrokes,
                livePreviewOverlay = liveOverlay,
                showLivePreview = true,
                showStrokes = false
            )
        )

        // Auto-apply timer
        removalJob?.cancel()
        removalJob = viewModelScope.launch {
            delay(1000L)

            if (_state.value.isRemovingObject) {

                // Start refinement
                triggerSmartMaskSnap()

                // WAIT until SmartMaskSnap finishes
                while (_state.value.objectRemovalState.isRefiningMask) {
                    delay(700)
                }

                // WAIT for refinedMaskPreview to appear
                while (!_state.value.objectRemovalState.hasRefinedMask) {
                    delay(1000)
                }

                // NOW use refined mask
                applyObjectRemoval()
            }

        }
    }

    private fun triggerSmartMaskSnap() {
        val currentImage = _state.value.currentImage ?: return
        val strokes = _state.value.objectRemovalState.strokes

        if (strokes.isEmpty()) return

        viewModelScope.launch {
            // 🆕 DON'T change the live preview state, just add refining flag
            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    isRefiningMask = true
                    // Keep showLivePreview = true and livePreviewOverlay intact
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

                // 🆕 Replace the live overlay with the refined mask
                _state.value.objectRemovalState.livePreviewOverlay?.recycle()

                // Convert refined mask to colored overlay for display
                val coloredRefinedOverlay = generateColoredMaskOverlay(refinedMask)

                _state.value = _state.value.copy(
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        isRefiningMask = false,
                        refinedMaskPreview = refinedMask,
                        livePreviewOverlay = coloredRefinedOverlay, // Show colored version
                        showLivePreview = true,
                        showStrokes = false,
                        hasRefinedMask = true
                    )
                )

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        isRefiningMask = false
                        // Keep the live preview overlay as is
                    ),
                    error = "Failed to refine mask: ${e.message}"
                )
            }
        }
    }

    private fun generateColoredMaskOverlay(mask: Bitmap): Bitmap {
        val width = mask.width
        val height = mask.height
        val coloredOverlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(coloredOverlay)
        canvas.drawColor(Color.TRANSPARENT)

        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        val paint = Paint().apply {
            isAntiAlias = true
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val pixel = pixels[idx]
                val brightness = Color.red(pixel) // Get brightness (0-255)

                if (brightness > 128) { // White areas in mask
                    // Draw semi-transparent red
                    paint.color = Color.argb(150, 255, 50, 50)
                    canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
                }
            }
        }

        return coloredOverlay
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

    /**
     * Generates a semi-transparent colored overlay bitmap from brush strokes
     * for real-time visual feedback while drawing.
     */
    private fun generateLiveMaskOverlay(
        width: Int,
        height: Int,
        strokes: List<BrushStroke>
    ): Bitmap {
        android.util.Log.d("ObjectRemoval", "generateLiveMaskOverlay: width=$width, height=$height, strokes=${strokes.size}")

        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        strokes.forEach { stroke ->
            android.util.Log.d("ObjectRemoval", "Drawing stroke with ${stroke.points.size} points, brushSize=${stroke.brushSize}")

            if (stroke.isEraser) {
                paint.color = Color.argb(80, 0, 0, 0)
                paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            } else {
                paint.color = Color.argb(200, 255, 0, 0) // More opaque, pure red
                paint.xfermode = null
            }
            paint.strokeWidth = stroke.brushSize * 2f

            if (stroke.points.size > 1) {
                val path = Path()
                val firstPoint = stroke.points.first()
                path.moveTo(firstPoint.x * width, firstPoint.y * height)

                for (i in 1 until stroke.points.size) {
                    val point = stroke.points[i]
                    path.lineTo(point.x * width, point.y * height)
                }

                canvas.drawPath(path, paint)
            }
        }

        android.util.Log.d("ObjectRemoval", "Generated overlay bitmap")
        return maskBitmap
    }

    // ⬇️ MODIFIED THIS FUNCTION ⬇️
    private fun undoRemovalStroke() {
        // Cancel any pending auto-apply job
        removalJob?.cancel() // ⬅️ Add this

        android.util.Log.d("ObjectRemoval", "undoRemovalStroke called: index=$removalStrokeIndex, historySize=${removalStrokeHistory.size}")
        if (removalStrokeIndex > 0) {
            removalStrokeIndex--
            val strokes = removalStrokeHistory[removalStrokeIndex]
            val currentImage = _state.value.currentImage ?: return

            // Recycle old overlays
            _state.value.objectRemovalState.livePreviewOverlay?.recycle()
            _state.value.objectRemovalState.refinedMaskPreview?.recycle()

            val canUndo = removalStrokeIndex > 0
            val canRedo = removalStrokeIndex < removalStrokeHistory.size - 1

            // 🆕 Generate new live overlay for the undone state
            val liveOverlay = if (strokes.isNotEmpty()) {
                generateLiveMaskOverlay(
                    currentImage.width,
                    currentImage.height,
                    strokes
                )
            } else null

            android.util.Log.d("ObjectRemoval", "undo: newIndex=$removalStrokeIndex, canUndo=$canUndo, canRedo=$canRedo, strokes=${strokes.size}")

            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    strokes = strokes,
                    showStrokes = false,
                    showLivePreview = strokes.isNotEmpty(),
                    livePreviewOverlay = liveOverlay,
                    refinedMaskPreview = null,
                    isRefiningMask = false
                )
            )
        }
    }

    // ⬇️ MODIFIED THIS FUNCTION ⬇️
    private fun redoRemovalStroke() {
        // Cancel any pending auto-apply job
        removalJob?.cancel() // ⬅️ Add this

        android.util.Log.d("ObjectRemoval", "redoRemovalStroke called: index=$removalStrokeIndex, historySize=${removalStrokeHistory.size}")
        if (removalStrokeIndex < removalStrokeHistory.size - 1) {
            removalStrokeIndex++
            val strokes = removalStrokeHistory[removalStrokeIndex]
            val currentImage = _state.value.currentImage ?: return

            // Recycle old overlays
            _state.value.objectRemovalState.livePreviewOverlay?.recycle()
            _state.value.objectRemovalState.refinedMaskPreview?.recycle()

            val canUndo = removalStrokeIndex > 0
            val canRedo = removalStrokeIndex < removalStrokeHistory.size - 1

            // 🆕 Generate new live overlay for the redone state
            val liveOverlay = if (strokes.isNotEmpty()) {
                generateLiveMaskOverlay(
                    currentImage.width,
                    currentImage.height,
                    strokes
                )
            } else null

            android.util.Log.d("ObjectRemoval", "redo: newIndex=$removalStrokeIndex, canUndo=$canUndo, canRedo=$canRedo, strokes=${strokes.size}")

            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    strokes = strokes,
                    showStrokes = false,
                    showLivePreview = strokes.isNotEmpty(),
                    livePreviewOverlay = liveOverlay,
                    refinedMaskPreview = null,
                    isRefiningMask = false
                )
            )
        }
    }

    // ⬇️ MODIFIED THIS FUNCTION ⬇️
    private fun resetRemovalStrokes() {
        removalJob?.cancel()

        // Recycle overlays
        _state.value.objectRemovalState.livePreviewOverlay?.recycle()
        _state.value.objectRemovalState.refinedMaskPreview?.recycle()

        _state.value = _state.value.copy(
            objectRemovalState = _state.value.objectRemovalState.copy(
                strokes = emptyList(),
                showStrokes = false,
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

    // ⬇️ MODIFIED THIS FUNCTION ⬇️
    private fun applyObjectRemoval() {
        removalJob?.cancel()

        val currentImage = _state.value.currentImage ?: return
        val strokes = _state.value.objectRemovalState.strokes

        if (_state.value.objectRemovalState.isProcessing) return
        if (strokes.isEmpty()) return

        // 🔥 NEW: choose refined mask if available
        val refinedMask = _state.value.objectRemovalState.refinedMaskPreview

        viewModelScope.launch {
            _state.value = _state.value.copy(
                objectRemovalState = _state.value.objectRemovalState.copy(
                    isProcessing = true
                )
            )

            try {
                // Create rough mask only if refined mask does NOT exist
                val mask = refinedMask ?: withContext(Dispatchers.Default) {
                    createMaskFromStrokes(
                        currentImage.width,
                        currentImage.height,
                        strokes
                    )
                }

                val result = withContext(Dispatchers.Default) {
                    ObjectRemoval.removeObject(getApplication(), currentImage, mask) { progress -> }
                }

                if (refinedMask == null) mask.recycle()

                // Update history
                addToHistory(result)

                // Cleanup overlays
                _state.value.objectRemovalState.livePreviewOverlay?.recycle()
                _state.value.objectRemovalState.refinedMaskPreview?.recycle()

                _state.value = _state.value.copy(
                    currentImage = result,
                    isRemovingObject = true,
                    objectRemovalState = _state.value.objectRemovalState.copy(
                        strokes = emptyList(),
                        isProcessing = false,
                        livePreviewOverlay = null,
                        refinedMaskPreview = null,
                        showLivePreview = false
                    )
                )

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
        android.util.Log.d("EditorViewModel", "startAddText: Currently have ${_state.value.textLayers.size} layers")

        // ✅ Check if we're already in styling mode
        if (_state.value.isStylingText) {
            // We're in styling screen, just show the text input dialog
            _state.value = _state.value.copy(
                isAddingText = true
                // Keep isStylingText = true and all layers intact
            )
        } else {
            // We're starting fresh from main editor
            _state.value = _state.value.copy(
                isAddingText = true,
                isStylingText = false
            )
        }
    }

    private fun cancelAddText() {
        _state.value = _state.value.copy(
            isAddingText = false,
            currentText = "",
            currentTextStyle = TextStyle()
        )
    }

    private fun confirmText(text: String) {
        // Create a new layer
        val newLayer = TextLayer(
            text = text,
            style = TextStyle(),
            position = TextPosition(0.5f, 0.5f)
        )

        _state.value = _state.value.copy(
            isAddingText = false,
            isStylingText = true,  // Always go to styling after confirming text
            // ✅ KEEP existing layers and add new one
            textLayers = _state.value.textLayers + newLayer,
            // Set new layer as active
            activeLayerId = newLayer.id,

            // Sync legacy fields for compatibility with UI
            currentText = newLayer.text,
            currentTextStyle = newLayer.style,
            textPosition = newLayer.position
        )

        android.util.Log.d("EditorViewModel", "confirmText: Total layers now = ${_state.value.textLayers.size}, all layer IDs: ${_state.value.textLayers.map { it.id }}")
    }

    // Replace the startTextStyling function with this:
    private fun startTextStyling() {
        android.util.Log.d("EditorViewModel", "startTextStyling: Current layers = ${_state.value.textLayers.size}")
        _state.value = _state.value.copy(isStylingText = true)
    }

    // Replace the cancelTextStyling function with this:
    private fun cancelTextStyling() {
        // ✅ DON'T clear all layers, just close the styling screen
        _state.value = _state.value.copy(
            isStylingText = false,
            activeLayerId = null,
            currentText = "",
            currentTextStyle = TextStyle()
        )

        android.util.Log.d("EditorViewModel", "cancelTextStyling: Keeping ${_state.value.textLayers.size} layers")
    }

    // [ADD THIS FUNCTION]
    private fun selectTextLayer(id: String) {
        val layer = _state.value.textLayers.find { it.id == id }
        if (layer != null) {
            android.util.Log.d("EditorViewModel", "selectTextLayer: Selected layer $id from ${_state.value.textLayers.size} total layers")
            _state.value = _state.value.copy(
                activeLayerId = id,
                // Update properties so UI sliders reflect this layer's settings
                currentText = layer.text,
                currentTextStyle = layer.style,
                textPosition = layer.position
            )
        } else {
            android.util.Log.e("EditorViewModel", "selectTextLayer: Layer $id NOT FOUND in ${_state.value.textLayers.size} layers")
        }
    }

    private fun updateLayerText(layerId: String, newText: String) {
        val updatedLayers = _state.value.textLayers.map { layer ->
            if (layer.id == layerId) {
                layer.copy(text = newText)
            } else {
                layer
            }
        }

        _state.value = _state.value.copy(
            textLayers = updatedLayers,
            currentText = if (_state.value.activeLayerId == layerId) newText else _state.value.currentText
        )
    }

    // [ADD THIS FUNCTION]
    private fun deleteActiveTextLayer() {
        val activeId = _state.value.activeLayerId ?: return
        val newLayers = _state.value.textLayers.filter { it.id != activeId }

        // Pick a new active layer (last one) or null if empty
        val newActiveLayer = newLayers.lastOrNull()

        _state.value = _state.value.copy(
            textLayers = newLayers,
            activeLayerId = newActiveLayer?.id,
            // Update UI properties
            currentText = newActiveLayer?.text ?: "",
            currentTextStyle = newActiveLayer?.style ?: TextStyle(),
            textPosition = newActiveLayer?.position ?: TextPosition()
        )
    }

    private fun updateTextStyle(style: TextStyle) {
        val activeId = _state.value.activeLayerId ?: return

        android.util.Log.d("EditorViewModel", "updateTextStyle: Updating layer $activeId")

        // Update the specific layer in the list
        val updatedLayers = _state.value.textLayers.map {
            if (it.id == activeId) it.copy(style = style) else it
        }

        _state.value = _state.value.copy(
            textLayers = updatedLayers,
            currentTextStyle = style
        )
    }

    // Replace the updateTextPosition function with this:
    private fun updateTextPosition(position: TextPosition) {
        val activeId = _state.value.activeLayerId ?: return

        // Update the specific layer in the list
        val updatedLayers = _state.value.textLayers.map {
            if (it.id == activeId) it.copy(position = position) else it
        }

        _state.value = _state.value.copy(
            textLayers = updatedLayers,
            textPosition = position
        )
    }

    private fun updateTextRotation(rotation: Float) {
        val activeId = _state.value.activeLayerId ?: return

        android.util.Log.d("EditorViewModel", "updateTextRotation: layer=$activeId, rotation=$rotation")

        // Update the specific layer in the list
        val updatedLayers = _state.value.textLayers.map { layer ->
            if (layer.id == activeId) {
                layer.copy(rotation = rotation)
            } else {
                layer
            }
        }

        _state.value = _state.value.copy(
            textLayers = updatedLayers
        )
    }

    private fun deleteTextLayer(id: String) {
        android.util.Log.d("EditorViewModel", "deleteTextLayer: Deleting $id from ${_state.value.textLayers.size} layers")

        val newLayers = _state.value.textLayers.filter { it.id != id }

        // If we deleted the active layer, pick a new active layer
        val newActiveId = if (_state.value.activeLayerId == id) {
            newLayers.lastOrNull()?.id
        } else {
            _state.value.activeLayerId
        }

        val newActiveLayer = newLayers.find { it.id == newActiveId }

        _state.value = _state.value.copy(
            textLayers = newLayers,
            activeLayerId = newActiveId,
            // Update UI properties to match new active layer
            currentText = newActiveLayer?.text ?: "",
            currentTextStyle = newActiveLayer?.style ?: TextStyle(),
            textPosition = newActiveLayer?.position ?: TextPosition()
        )

        android.util.Log.d("EditorViewModel", "deleteTextLayer: Now have ${newLayers.size} layers")
    }

    private fun confirmTextStyling() {
        val currentImage = _state.value.currentImage ?: return
        val layers = _state.value.textLayers
        val density = _state.value.density

        android.util.Log.d("EditorViewModel", "confirmTextStyling: Burning ${layers.size} layers into image")

        if (layers.isEmpty()) {
            _state.value = _state.value.copy(isStylingText = false)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true)

            try {
                // Apply ALL layers sequentially
                var result = currentImage
                layers.forEach { layer ->
                    val x = layer.position.x * currentImage.width
                    val y = layer.position.y * currentImage.height
                    result = withContext(Dispatchers.Default) {
                        PhotoEditorUtils.addStyledText(
                            result,
                            layer.text,
                            x,
                            y,
                            layer.style,
                            density,
                            layer.rotation,  // ✅ PASS ROTATION HERE
                            getApplication()
                        )
                    }
                }

                android.util.Log.d("EditorViewModel", "confirmTextStyling: Successfully burned all layers")

                _state.value = _state.value.copy(
                    currentImage = result,
                    isStylingText = false,
                    textLayers = emptyList(),
                    activeLayerId = null,
                    isProcessing = false
                )
                addToHistory(result)
            } catch (e: Exception) {
                android.util.Log.e("EditorViewModel", "confirmTextStyling: Error - ${e.message}")
                _state.value = _state.value.copy(
                    error = "Failed to add text: ${e.message}",
                    isStylingText = false,
                    isProcessing = false
                )
            }
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