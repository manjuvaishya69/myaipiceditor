package com.dlab.myaipiceditor

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlab.myaipiceditor.data.EditorAction
import com.dlab.myaipiceditor.ui.theme.MyAiPicEditorTheme
import com.dlab.myaipiceditor.ui.*
import com.dlab.myaipiceditor.viewmodel.EditorViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemUI()
        setContent {
            MyAiPicEditorTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val density = LocalContext.current.resources.displayMetrics.density

                LaunchedEffect(density) {
                    viewModel.setDensity(density)
                }

                // Permission handling
                val permissions = rememberMultiplePermissionsState(
                    permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.CAMERA)
                    else
                        listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
                )

                // Image picker launcher
                val imagePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let {
                        val bitmap = loadAndProcessBitmapFromUri(it)
                        bitmap?.let { bmp -> viewModel.setImage(bmp) }
                    }
                }

                // Camera launcher
                var photoUri by remember { mutableStateOf<Uri?>(null) }
                val cameraLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.TakePicture()
                ) { success ->
                    if (success && photoUri != null) {
                        val bitmap = loadAndProcessBitmapFromUri(photoUri!!)
                        bitmap?.let { bmp -> viewModel.setImage(bmp) }
                    }
                }

                LaunchedEffect(Unit) {
                    permissions.launchMultiplePermissionRequest()
                }

                BackHandler(enabled = state.currentImage != null || state.showingCollageScreen || state.showingImageToTextScreen) {
                    when {
                        state.showingSaveDialog -> viewModel.handleAction(EditorAction.HideSaveDialog)
                        state.showingToolsSheet -> viewModel.handleAction(EditorAction.HideToolsSheet)
                        state.isCropping -> viewModel.handleAction(EditorAction.CancelCrop)
                        state.isAddingText -> viewModel.handleAction(EditorAction.CancelAddText)
                        state.isStylingText -> viewModel.handleAction(EditorAction.CancelTextStyling)
                        state.isAdjusting -> viewModel.handleAction(EditorAction.CancelAdjust)
                        state.isRemovingObject -> viewModel.handleAction(EditorAction.CancelObjectRemoval)
                        state.isEnhancingPhoto -> viewModel.handleAction(EditorAction.CancelPhotoEnhancement)
                        state.isFilteringImage -> viewModel.handleAction(EditorAction.CancelFilters)
                        state.isRetouchingImage -> viewModel.handleAction(EditorAction.CancelRetouch)
                        state.isDrawing -> viewModel.handleAction(EditorAction.CancelDraw)
                        state.isAddingPhoto -> viewModel.handleAction(EditorAction.CancelAddPhoto)
                        state.isAddingLensFlare -> viewModel.handleAction(EditorAction.CancelLensFlare)
                        state.isFreeCropping -> viewModel.handleAction(EditorAction.CancelFreeCrop)
                        state.isShapeCropping -> viewModel.handleAction(EditorAction.CancelShapeCrop)
                        state.isStretching -> viewModel.handleAction(EditorAction.CancelStretch)
                        state.isAdjustingCurves -> viewModel.handleAction(EditorAction.CancelCurves)
                        state.isApplyingTiltShift -> viewModel.handleAction(EditorAction.CancelTiltShift)
                        state.isFlipRotating -> viewModel.handleAction(EditorAction.CancelFlipRotate)
                        state.showingCollageScreen -> viewModel.handleAction(EditorAction.HideMakeCollage)
                        state.showingImageToTextScreen -> viewModel.handleAction(EditorAction.HideImageToText)
                        else -> viewModel.handleAction(EditorAction.BackToStart)
                    }
                }

                // Show first screen if no image is loaded and not on collage/ocr screens
                if (state.currentImage == null && !state.showingCollageScreen && !state.showingImageToTextScreen) {
                    FirstScreen(
                        onSelectFromGallery = {
                            if (permissions.allPermissionsGranted) {
                                imagePickerLauncher.launch("image/*")
                            } else {
                                permissions.launchMultiplePermissionRequest()
                            }
                        },
                        onTakePhoto = {
                            if (permissions.allPermissionsGranted) {
                                photoUri = createImageUri()
                                photoUri?.let { cameraLauncher.launch(it) }
                            } else {
                                permissions.launchMultiplePermissionRequest()
                            }
                        },
                        onMakeCollage = {
                            viewModel.handleAction(EditorAction.ShowMakeCollage)
                        },
                        onImageToText = {
                            viewModel.handleAction(EditorAction.ShowImageToText)
                        }
                    )
                } else if (state.currentImage != null) {
                    EditorScreen(
                        state = state,
                        onActionClick = { action -> viewModel.handleAction(action) },
                        onSelectFromGallery = {
                            if (permissions.allPermissionsGranted) {
                                imagePickerLauncher.launch("image/*")
                            } else {
                                permissions.launchMultiplePermissionRequest()
                            }
                        },
                        onTakePhoto = {
                            if (permissions.allPermissionsGranted) {
                                photoUri = createImageUri()
                                photoUri?.let { cameraLauncher.launch(it) }
                            } else {
                                permissions.launchMultiplePermissionRequest()
                            }
                        },
                        onShareImage = { shareImage(state.currentImage!!) }
                    )
                }

                // Show crop screen when cropping
                if (state.isCropping && state.currentImage != null) {
                    CropScreen(
                        bitmap = state.currentImage!!,
                        onCropConfirm = { cropRect ->
                            viewModel.handleAction(EditorAction.ConfirmCrop(cropRect))
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelCrop)
                        }
                    )
                }

                // Show text editor screen when adding text
                if (state.isAddingText) {
                    TextEditorScreen(
                        bitmap = state.currentImage, // pass the photo here
                        onTextConfirm = { text ->
                            viewModel.handleAction(EditorAction.ConfirmText(text))
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelAddText)
                        }
                    )
                }

                // Show text styling screen when styling text (but NOT when adding new text)
                if (state.isStylingText && !state.isAddingText) {
                    var showEditDialog by remember { mutableStateOf(false) }
                    var editingLayerId by remember { mutableStateOf("") }
                    var editingText by remember { mutableStateOf("") }
                    var dialogTextValue by remember { mutableStateOf("") }

                    TextStylingScreen(
                        text = state.currentText,
                        bitmap = state.currentImage,
                        currentStyle = state.currentTextStyle,
                        textLayers = state.textLayers,
                        activeLayerId = state.activeLayerId,
                        onLayerSelected = { id ->
                            if (id.isNotEmpty()) {
                                android.util.Log.d("MainActivity", "Selecting layer: $id")
                                viewModel.handleAction(EditorAction.SelectTextLayer(id))
                            }
                        },
                        onStyleChange = { style ->
                            viewModel.handleAction(EditorAction.UpdateTextStyle(style))
                        },
                        onPositionChange = { position ->
                            viewModel.handleAction(EditorAction.UpdateTextPosition(position))
                        },
                        onRotationChange = { rotation ->  // ✅ ADD THIS BLOCK
                            viewModel.handleAction(EditorAction.UpdateTextRotation(rotation))
                        },
                        onConfirm = {
                            viewModel.handleAction(EditorAction.ConfirmTextStyling)
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelTextStyling)
                        },
                        onAddText = {
                            viewModel.handleAction(EditorAction.StartAddText)
                        },
                        onDeleteText = { id ->
                            viewModel.handleAction(EditorAction.DeleteTextLayer(id))
                        },
                        onEditText = { layerId, currentText ->
                            editingLayerId = layerId
                            editingText = currentText
                            dialogTextValue = currentText
                            showEditDialog = true
                        },
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onUndo = { viewModel.handleAction(EditorAction.Undo) },
                        onRedo = { viewModel.handleAction(EditorAction.Redo) }
                    )

                    // Edit Text Dialog
                    if (showEditDialog) {
                        AlertDialog(
                            onDismissRequest = { showEditDialog = false },
                            title = { Text("Edit Text") },
                            text = {
                                var textFieldValue by remember { mutableStateOf(editingText) }
                                OutlinedTextField(
                                    value = textFieldValue,
                                    onValueChange = { textFieldValue = it },
                                    label = { Text("Text") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // Store the updated value
                                editingText = textFieldValue
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (editingText.isNotBlank()) {
                                            viewModel.handleAction(EditorAction.UpdateLayerText(editingLayerId, editingText))
                                        }
                                        showEditDialog = false
                                    }
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showEditDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }

                // Show adjust screen when adjusting
                if (state.isAdjusting) {
                    AdjustScreen(
                        bitmap = state.currentImage,
                        adjustmentValues = state.adjustmentValues,
                        onAdjustmentChange = { type, value ->
                            viewModel.handleAction(EditorAction.UpdateAdjustment(type, value))
                        },
                        onConfirm = {
                            viewModel.handleAction(EditorAction.ConfirmAdjust)
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelAdjust)
                        },
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onUndo = { viewModel.handleAction(EditorAction.Undo) },
                        onRedo = { viewModel.handleAction(EditorAction.Redo) }
                    )
                }

                // Show save screen when saving
                if (state.showingSaveDialog && state.currentImage != null) {
                    SaveScreen(
                        bitmap = state.currentImage,
                        onSaveClick = { config ->
                            saveImageToGallery(state.currentImage!!, config)
                            viewModel.handleAction(EditorAction.HideSaveDialog)
                        },
                        onBackClick = {
                            viewModel.handleAction(EditorAction.HideSaveDialog)
                        }
                    )
                }

                // Show object removal screen when removing objects
                if (state.isRemovingObject && state.currentImage != null) {
                    ObjectRemovalScreen(
                        bitmap = state.currentImage!!,
                        removalState = state.objectRemovalState,
                        onStrokeAdded = { stroke ->
                            viewModel.handleAction(EditorAction.AddRemovalStroke(stroke))
                        },
                        onUndo = {
                            viewModel.handleAction(EditorAction.Undo)  // ✅ Simple: just main undo
                        },
                        onRedo = {
                            viewModel.handleAction(EditorAction.Redo)  // ✅ Simple: just main redo
                        },
                        onReset = {
                            viewModel.handleAction(EditorAction.ResetRemovalStrokes)
                        },
                        onBrushSizeChange = { size ->
                            viewModel.handleAction(EditorAction.UpdateBrushSize(size))
                        },
                        onToggleEraser = {
                            viewModel.handleAction(EditorAction.ToggleEraserMode)
                        },
                        onApply = {
                            viewModel.handleAction(EditorAction.ApplyObjectRemoval)
                        },
                        onRefineAndPreview = {
                            viewModel.handleAction(EditorAction.RefineAndPreviewMask)
                        },
                        onAcceptRefinedMask = {
                            viewModel.handleAction(EditorAction.AcceptRefinedMask)
                        },
                        onRejectRefinedMask = {
                            viewModel.handleAction(EditorAction.RejectRefinedMask)
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelObjectRemoval)
                        },
                        onConfirm = {
                            viewModel.handleAction(EditorAction.ConfirmObjectRemoval)
                        },
                        canUndo = state.canUndo,    // ✅ Pass main app undo state
                        canRedo = state.canRedo     // ✅ Pass main app redo state
                    )
                }

                // Show AI photo enhancement screen
                if (state.isEnhancingPhoto && state.currentImage != null) {
                    AiPhotoEnhancementScreen(
                        bitmap = state.currentImage!!,
                        enhancementState = state.photoEnhancementState,
                        onEnhanceClick = {
                            viewModel.handleAction(EditorAction.RunPhotoEnhancement)
                        },
                        onToggleBeforeAfter = {
                            viewModel.handleAction(EditorAction.ToggleEnhancementBeforeAfter)
                        },
                        onConfirm = {
                            viewModel.handleAction(EditorAction.ConfirmPhotoEnhancement)
                        },
                        onUndo = {
                            viewModel.handleAction(EditorAction.UndoPhotoEnhancement)
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelPhotoEnhancement)
                        },
                        onClearError = {
                            viewModel.handleAction(EditorAction.ClearEnhancementError)
                        }
                    )
                }

                // Show Filters screen
                if (state.isFilteringImage && state.currentImage != null) {
                    FiltersScreen(
                        bitmap = state.currentImage,
                        onConfirm = { filteredBitmap ->
                            viewModel.handleAction(EditorAction.ConfirmFilters(filteredBitmap))
                        },
                        onCancel = { viewModel.handleAction(EditorAction.CancelFilters) }
                    )
                }

                // Show Retouch screen
                if (state.isRetouchingImage && state.currentImage != null) {
                    RetouchScreen(
                        bitmap = state.currentImage,
                        onConfirm = { retouchedBitmap ->  // ✅ Now receives the bitmap parameter
                            viewModel.handleAction(EditorAction.ConfirmRetouch(retouchedBitmap))
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelRetouch)
                        }
                    )
                }

                // Show Draw screen
                if (state.isDrawing && state.currentImage != null) {
                    DrawScreen(
                        bitmap = state.currentImage,
                        onConfirm = { viewModel.handleAction(EditorAction.ConfirmDraw) },
                        onCancel = { viewModel.handleAction(EditorAction.CancelDraw) }
                    )
                }

                // Show Add Photo screen
                if (state.isAddingPhoto && state.currentImage != null) {
                    AddPhotoScreen(
                        bitmap = state.currentImage,
                        onConfirm = { viewModel.handleAction(EditorAction.ConfirmAddPhoto) },
                        onCancel = { viewModel.handleAction(EditorAction.CancelAddPhoto) }
                    )
                }

                // Show Lens Flare screen
                if (state.isAddingLensFlare && state.currentImage != null) {
                    LensFlareScreen(
                        bitmap = state.currentImage,
                        onConfirm = { viewModel.handleAction(EditorAction.ConfirmLensFlare) },
                        onCancel = { viewModel.handleAction(EditorAction.CancelLensFlare) }
                    )
                }

                // Show Make Collage screen
                if (state.showingCollageScreen) {
                    MakeCollageScreen(
                        onBack = { viewModel.handleAction(EditorAction.HideMakeCollage) }
                    )
                }

                // Show Image to Text screen
                if (state.showingImageToTextScreen) {
                    ImageToTextScreen(
                        onBack = { viewModel.handleAction(EditorAction.HideImageToText) }
                    )
                }

                // Show Free Crop screen
                if (state.isFreeCropping && state.currentImage != null) {
                    FreeCropScreen(
                        bitmap = state.currentImage,
                        onConfirm = { viewModel.handleAction(EditorAction.ConfirmFreeCrop) },
                        onCancel = { viewModel.handleAction(EditorAction.CancelFreeCrop) }
                    )
                }

                // Show Shape Crop screen
                if (state.isShapeCropping && state.currentImage != null) {
                    ShapeCropScreen(
                        bitmap = state.currentImage,
                        onConfirm = { croppedBitmap ->  // UPDATE: Now receives the bitmap
                            viewModel.handleAction(EditorAction.ConfirmShapeCrop(croppedBitmap))
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelShapeCrop)
                        }
                    )
                }

                // Show Stretch screen
                if (state.isStretching && state.currentImage != null) {
                    StretchScreen(
                        bitmap = state.currentImage,
                        onConfirm = { viewModel.handleAction(EditorAction.ConfirmStretch) },
                        onCancel = { viewModel.handleAction(EditorAction.CancelStretch) }
                    )
                }

                // Show Curves screen
                if (state.isAdjustingCurves && state.currentImage != null) {
                    CurvesScreen(
                        bitmap = state.currentImage,
                        onConfirm = { processedBitmap ->
                            viewModel.handleAction(EditorAction.ConfirmCurves(processedBitmap))
                        },
                        onCancel = { viewModel.handleAction(EditorAction.CancelCurves) }
                    )
                }

                // Show Tilt Shift screen
                if (state.isApplyingTiltShift && state.currentImage != null) {
                    TiltShiftScreen(
                        bitmap = state.currentImage,
                        onConfirm = { viewModel.handleAction(EditorAction.ConfirmTiltShift) },
                        onCancel = { viewModel.handleAction(EditorAction.CancelTiltShift) }
                    )
                }

                // Show Flip/Rotate screen
                if (state.isFlipRotating && state.currentImage != null) {
                    FlipRotateScreen(
                        bitmap = state.currentImage!!,
                        onConfirm = { transformedBitmap ->
                            viewModel.handleAction(EditorAction.SetFlipRotateResult(transformedBitmap))
                        },
                        onCancel = {
                            viewModel.handleAction(EditorAction.CancelFlipRotate)
                        }
                    )
                }

            }
        }
    }

    private fun loadAndProcessBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            // Handle image rotation based on EXIF data
            val rotatedBitmap = handleImageRotation(uri, bitmap)

            // Resize if image is too large (to prevent memory issues)
            resizeIfNeeded(rotatedBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun handleImageRotation(uri: Uri, bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null

        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val exif = ExifInterface(inputStream!!)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            inputStream.close()

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }

            if (!matrix.isIdentity) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null

        val maxSize = 2048
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun createImageUri(): Uri {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = File(getExternalFilesDir(null), "Pictures")
        if (!storageDir.exists()) storageDir.mkdirs()

        val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
    }

    private fun saveImageToGallery(bitmap: Bitmap, config: SaveConfig) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "AI_EDIT_${timeStamp}.${config.format.extension}"

            val mimeType = when (config.format.extension) {
                "png" -> "image/png"
                else -> "image/jpeg"
            }

            val resolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AI Photo Editor")
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(config.format.compressFormat, config.quality, outputStream)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareImage(bitmap: Bitmap) {
        try {
            val cachePath = File(cacheDir, "images")
            cachePath.mkdirs()
            val file = File(cachePath, "shared_image.jpg")

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share Image"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }
    }
}

@Composable
fun FirstScreen(
    onSelectFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onMakeCollage: () -> Unit,
    onImageToText: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Background Image
        Image(
            painter = painterResource(id = R.drawable.first_screen_bg),
            contentDescription = "Background",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Bottom Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Select from Gallery Button
            Button(
                onClick = onSelectFromGallery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Select Image from Gallery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Camera Button
            Button(
                onClick = onTakePhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Take Photo with Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Make Collage Button
            OutlinedButton(
                onClick = onMakeCollage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.GridOn,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Make Collage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Image to Text Button
            OutlinedButton(
                onClick = onImageToText,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.TextFields,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Image to Text (OCR)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun EditorScreen(
    state: com.dlab.myaipiceditor.data.EditorState,
    onActionClick: (EditorAction) -> Unit,
    onSelectFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onShareImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            EditorTopBar(
                canUndo = state.canUndo && !state.isProcessing,
                canRedo = state.canRedo && !state.isProcessing,
                onBackClick = { onActionClick(EditorAction.BackToStart) },
                onUndoClick = { onActionClick(EditorAction.Undo) },
                onRedoClick = { onActionClick(EditorAction.Redo) },
                onSaveClick = { onActionClick(EditorAction.ShowSaveDialog) }
            )
        },
        bottomBar = {
            EditorBottomToolbar(
                onToolClick = { tool ->
                    when (tool) {
                        "crop" -> onActionClick(EditorAction.StartCrop)
                        "rotate" -> onActionClick(EditorAction.RotateImage(90f))
                        "text" -> onActionClick(EditorAction.StartAddText)
                        "adjust" -> onActionClick(EditorAction.StartAdjust)
                        "ai_object_removal" -> onActionClick(EditorAction.StartObjectRemoval)
                        "ai_photo_enhancement" -> onActionClick(EditorAction.StartPhotoEnhancement)
                        "ai_face_restoration" -> onActionClick(EditorAction.RestoreFace)
                        "ai_upscaler" -> onActionClick(EditorAction.UpscaleImage)
                        "filters" -> onActionClick(EditorAction.StartFilters)
                        "retouch" -> onActionClick(EditorAction.StartRetouch)
                        "draw" -> onActionClick(EditorAction.StartDraw)
                        "add_photo" -> onActionClick(EditorAction.StartAddPhoto)
                        "lens_flare" -> onActionClick(EditorAction.StartLensFlare)
                        "free_crop" -> onActionClick(EditorAction.StartFreeCrop)
                        "shape_crop" -> onActionClick(EditorAction.StartShapeCrop)
                        "stretch" -> onActionClick(EditorAction.StartStretch)
                        "curves" -> onActionClick(EditorAction.StartCurves)
                        "tilt_shift" -> onActionClick(EditorAction.StartTiltShift)
                        "flip_rotate" -> onActionClick(EditorAction.StartFlipRotate)
                    }
                },
                isProcessing = state.isProcessing
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Main Image Preview Area
            ZoomableImagePreview(
                bitmap = state.currentImage,
                isProcessing = state.isProcessing,
                processingMessage = state.processingMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Error Display
            state.error?.let { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { onActionClick(EditorAction.ClearError) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onBackClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Text(
                "Edit Photo",
                fontWeight = FontWeight.Medium
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(
                onClick = onUndoClick,
                enabled = canUndo
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    "Undo",
                    tint = if (canUndo) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }

            IconButton(
                onClick = onRedoClick,
                enabled = canRedo
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    "Redo",
                    tint = if (canRedo) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }

            IconButton(onClick = onSaveClick) {
                Icon(Icons.Default.Save, "Save")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBottomToolbar(
    onToolClick: (String) -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    var showToolsSheet by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tools button - opens bottom sheet with grid
            ToolButton(
                tool = ToolItem("tools", "Tools", Icons.Default.Apps),
                onClick = { showToolsSheet = true },
                enabled = !isProcessing
            )

            // Additional buttons outside of grid
            ToolButton(
                tool = ToolItem("filters", "Filters", Icons.Default.FilterVintage),
                onClick = { onToolClick("filters") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("retouch", "Retouch", Icons.Default.Healing),
                onClick = { onToolClick("retouch") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("draw", "Draw", Icons.Default.Brush),
                onClick = { onToolClick("draw") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("add_photo", "Add Photo", Icons.Default.AddPhotoAlternate),
                onClick = { onToolClick("add_photo") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("lens_flare", "Lens Flare", Icons.Default.Flare),
                onClick = { onToolClick("lens_flare") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("text", "Text", Icons.Default.TextFields),
                onClick = { onToolClick("text") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("adjust", "Adjust", Icons.Default.Tune),
                onClick = { onToolClick("adjust") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("ai_object_removal", "AI Remove", Icons.Default.AutoFixHigh),
                onClick = { onToolClick("ai_object_removal") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("ai_photo_enhancement", "AI Enhance", Icons.Default.AutoAwesome),
                onClick = { onToolClick("ai_photo_enhancement") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("ai_face_restoration", "AI Face", Icons.Default.Face),
                onClick = { onToolClick("ai_face_restoration") },
                enabled = !isProcessing
            )

            ToolButton(
                tool = ToolItem("ai_upscaler", "AI Upscale", Icons.Default.ZoomIn),
                onClick = { onToolClick("ai_upscaler") },
                enabled = !isProcessing
            )
        }
    }

    // Tools Grid Bottom Sheet
    if (showToolsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false }
        ) {
            ToolsGridContent(
                onToolClick = { toolId ->
                    showToolsSheet = false
                    onToolClick(toolId)
                },
                isProcessing = isProcessing
            )
        }
    }
}

@Composable
fun ToolsGridContent(
    onToolClick: (String) -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    val gridTools = listOf(
        ToolItem("crop", "Crop", Icons.Default.Crop),
        ToolItem("rotate", "Rotate", Icons.AutoMirrored.Filled.RotateRight),
        ToolItem("free_crop", "Free Crop", Icons.Default.CropFree),
        ToolItem("shape_crop", "Shape Crop", Icons.Default.Category),
        ToolItem("stretch", "Stretch", Icons.Default.OpenInFull),
        ToolItem("curves", "Curves", Icons.Default.Timeline),
        ToolItem("tilt_shift", "Tilt Shift", Icons.Default.Gradient),
        ToolItem("flip_rotate", "Flip/Rotate", Icons.Default.FlipCameraAndroid)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Tools",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(max = 400.dp)
        ) {
            items(gridTools.size) { index ->
                val tool = gridTools[index]
                GridToolButton(
                    tool = tool,
                    onClick = { onToolClick(tool.id) },
                    enabled = !isProcessing
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun GridToolButton(
    tool: ToolItem,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = tool.name,
                modifier = Modifier.size(28.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = tool.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
                },
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

data class ToolItem(
    val id: String,
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun ToolButton(
    tool: ToolItem,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(min = 64.dp)
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = tool.name,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = tool.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
    }
}

@Composable
fun ZoomableImagePreview(
    bitmap: Bitmap?,
    isProcessing: Boolean,
    processingMessage: String,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset += offsetChange
    }

    Card(
        modifier = modifier.padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when {
                isProcessing -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = processingMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                bitmap != null -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image Preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                            .transformable(state = transformableState),
                        contentScale = ContentScale.Fit
                    )
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "No Image",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No image selected",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorCard(
    error: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}