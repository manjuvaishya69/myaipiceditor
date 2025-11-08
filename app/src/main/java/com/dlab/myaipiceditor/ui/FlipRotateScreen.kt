package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Custom ImageVector for flip vertical
val FlipVerticalIcon: ImageVector
    get() = ImageVector.Builder(
        name = "FlipVertical",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) { // Changed to white for better visibility on dark themes
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 8f)
            lineTo(4f, 8f)
            close()
            moveTo(4f, 16f)
            lineTo(20f, 16f)
            lineTo(20f, 20f)
            close()
        }
    }.build()

/**
 * A simplified full-screen Jetpack Compose component for flipping and rotating images.
 * All cropping, panning, and zooming features have been removed to ensure stability.
 *
 * @param bitmap The bitmap to be edited.
 * @param onConfirm Callback with the processed Bitmap when the user accepts the changes.
 * @param onCancel Callback for when the user cancels the editing operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlipRotateScreen(
    bitmap: Bitmap?,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    // State management for image transformations
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var scaleX by remember { mutableFloatStateOf(1f) }
    var scaleY by remember { mutableFloatStateOf(1f) }
    var isProcessing by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val undoStack = remember { mutableStateListOf<Triple<Float, Float, Float>>() }
    val redoStack = remember { mutableStateListOf<Triple<Float, Float, Float>>() }

    // Function to reset all transformations to their initial state
    fun resetAll() {
        rotationDegrees = 0f
        scaleX = 1f
        scaleY = 1f
        undoStack.clear()
        redoStack.clear()
    }

    fun updateStateForUndoRedo(newRotation: Float, newScaleX: Float, newScaleY: Float) {
        undoStack.add(Triple(rotationDegrees, scaleX, scaleY))
        redoStack.clear()
        rotationDegrees = newRotation
        scaleX = newScaleX
        scaleY = newScaleY
    }

    fun handleUndo() {
        if (undoStack.isNotEmpty()) {
            val lastState = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(Triple(rotationDegrees, scaleX, scaleY))
            rotationDegrees = lastState.first
            scaleX = lastState.second
            scaleY = lastState.third
        }
    }

    fun handleRedo() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(Triple(rotationDegrees, scaleX, scaleY))
            rotationDegrees = nextState.first
            scaleX = nextState.second
            scaleY = nextState.third
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Rotate & Flip", fontWeight = FontWeight.Medium, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { resetAll() }) {
                        Icon(Icons.Default.Replay, contentDescription = "Reset All")
                    }
                    IconButton(onClick = { handleUndo() }, enabled = undoStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { handleRedo() }, enabled = redoStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    Button(
                        onClick = {
                            if (bitmap == null) return@Button
                            isProcessing = true
                            coroutineScope.launch {
                                val finalBitmap = createFinalBitmap(
                                    originalBitmap = bitmap,
                                    rotation = rotationDegrees,
                                    scaleX = scaleX,
                                    scaleY = scaleY
                                )
                                onConfirm(finalBitmap)
                                isProcessing = false
                            }
                        },
                        enabled = !isProcessing && bitmap != null
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Done, contentDescription = "Done")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            BottomControls(
                onRotateLeft = { updateStateForUndoRedo((rotationDegrees - 90f).mod(360f), scaleX, scaleY) },
                onRotateRight = { updateStateForUndoRedo((rotationDegrees + 90f).mod(360f), scaleX, scaleY) },
                onFlipHorizontal = { updateStateForUndoRedo(rotationDegrees, -scaleX, scaleY) },
                onFlipVertical = { updateStateForUndoRedo(rotationDegrees, scaleX, -scaleY) }
            )
        }
    ) { paddingValues ->
        // The main content area. A Box is used to center the image.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .padding(16.dp), // Add some padding around the image
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                // Animate the state changes for smooth transitions
                val animatedRotation by animateFloatAsState(targetValue = rotationDegrees, label = "rotation")
                val animatedScaleX by animateFloatAsState(targetValue = scaleX, label = "scaleX")
                val animatedScaleY by animateFloatAsState(targetValue = scaleY, label = "scaleY")

                // ** SIMPLIFIED IMAGE DISPLAY **
                // We use a standard Image composable.
                // ContentScale.Fit ensures the image fits within the bounds of the Box.
                // The .graphicsLayer modifier is the modern, correct way to apply transformations like rotation and scaling.
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Image for editing",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.rotationZ = animatedRotation
                            this.scaleX = animatedScaleX
                            this.scaleY = animatedScaleY
                        }
                )
            } else {
                // Show a loader if the bitmap isn't ready
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Simplified bottom controls for rotation and flipping.
 */
@Composable
private fun BottomControls(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlAction(icon = Icons.Default.Rotate90DegreesCcw, text = "Rotate Left", onClick = onRotateLeft)
            ControlAction(icon = Icons.AutoMirrored.Filled.RotateRight, text = "Rotate Right", onClick = onRotateRight)
            ControlAction(icon = Icons.Default.Flip, text = "Flip H", onClick = onFlipHorizontal)
            ControlAction(icon = FlipVerticalIcon, text = "Flip V", onClick = onFlipVertical)
        }
    }
}

@Composable
private fun ControlAction(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = text, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Simplified function to create the final bitmap.
 * It only applies rotation and flip transformations.
 */
private suspend fun createFinalBitmap(
    originalBitmap: Bitmap,
    rotation: Float,
    scaleX: Float,
    scaleY: Float
): Bitmap = withContext(Dispatchers.Default) {
    if (rotation == 0f && scaleX == 1f && scaleY == 1f) {
        return@withContext originalBitmap // No changes needed
    }

    try {
        // Create a transformation matrix
        val matrix = Matrix()

        // Apply rotation around the center of the bitmap
        matrix.postRotate(rotation, originalBitmap.width / 2f, originalBitmap.height / 2f)

        // Apply flip (scaling) around the center of the bitmap
        matrix.postScale(scaleX, scaleY, originalBitmap.width / 2f, originalBitmap.height / 2f)

        // Create the new bitmap with the transformations applied
        Bitmap.createBitmap(
            originalBitmap,
            0, 0,
            originalBitmap.width,
            originalBitmap.height,
            matrix,
            true // Use filtering for better quality
        )
    } catch (e: Exception) {
        e.printStackTrace()
        originalBitmap // Return original bitmap in case of an error
    }
}