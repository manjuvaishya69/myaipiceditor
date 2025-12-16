package com.dlab.myaipiceditor.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlab.myaipiceditor.ai.AiModelManager
import com.dlab.myaipiceditor.ai.PhotoEnhancement
import com.dlab.myaipiceditor.data.PhotoEnhancementState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiPhotoEnhancementViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PhotoEnhancementState())
    val state: StateFlow<PhotoEnhancementState> = _state.asStateFlow()

    private var originalBitmapCopy: Bitmap? = null

    fun setOriginalImage(bitmap: Bitmap) {
        originalBitmapCopy?.recycle()
        originalBitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        _state.value = _state.value.copy(
            originalBitmap = originalBitmapCopy,
            enhancedBitmap = null,
            showBeforeAfter = false,
            error = null
        )
    }

    fun startEnhancement() {
        val original = originalBitmapCopy ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isProcessing = true,
                progress = 0f,
                error = null,
                enhancedBitmap = null
            )

            try {
                withContext(Dispatchers.IO) {
                    AiModelManager.initialize(getApplication())
                }

                val enhanced = withContext(Dispatchers.Default) {
                    PhotoEnhancement.enhance(getApplication(), original) { progress ->
                        _state.value = _state.value.copy(progress = progress)
                    }
                }

                _state.value = _state.value.copy(
                    enhancedBitmap = enhanced,
                    isProcessing = false,
                    progress = 1.0f,
                    showBeforeAfter = true
                )

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isProcessing = false,
                    progress = 0f,
                    error = "Enhancement failed: ${e.message}"
                )
            }
        }
    }

    fun toggleBeforeAfter() {
        _state.value = _state.value.copy(
            showBeforeAfter = !_state.value.showBeforeAfter
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun reset() {
        _state.value.enhancedBitmap?.recycle()
        _state.value = PhotoEnhancementState(
            originalBitmap = originalBitmapCopy
        )
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.enhancedBitmap?.recycle()
        originalBitmapCopy?.recycle()
    }
}
