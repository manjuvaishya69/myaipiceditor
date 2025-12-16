package com.dlab.myaipiceditor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dlab.myaipiceditor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun FiltersScreen(
    bitmap: Bitmap?,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: FiltersViewModel = viewModel(factory = viewModelFactory {
        initializer { FiltersViewModel(context.applicationContext as android.app.Application) }
    })

    val filters by viewModel.filters.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val eraserState by viewModel.eraserState.collectAsState()
    val livePreview by viewModel.livePreview.collectAsState()
    val tempStrokeBitmap by viewModel.tempStrokeBitmap.collectAsState() // NEW
    val scope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf("SIMPLE") }
    var currentBitmap by remember { mutableStateOf(bitmap) }
    var isRestoreMode by remember { mutableStateOf(false) }

    // Initialize with original bitmap
    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            currentBitmap = bitmap
            viewModel.selectFilter(Filter("ORIGINAL", "NONE", ""), bitmap)
        }
    }

    if (bitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("No Image Loaded", color = Color.White)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = if (eraserState.isErasing) "Eraser Mode" else "Filters",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Eraser toggle button
                    IconButton(
                        onClick = { viewModel.toggleEraseMode() }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_eraser),
                            contentDescription = "Eraser",
                            tint = if (eraserState.isErasing) Color(0xFF4CAF50) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Settings button (only show in eraser mode)
                    if (eraserState.isErasing) {
                        IconButton(onClick = { viewModel.toggleEraserSettings() }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    IconButton(onClick = {
                        val resultBitmap = viewModel.getFinalBitmap() ?: currentBitmap
                        if (resultBitmap != null) {
                            onConfirm(resultBitmap)
                        }
                    }) {
                        Icon(
                            Icons.Default.Done,
                            contentDescription = "Done",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Card Canvas for Image Preview
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF424242)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .then(
                            if (eraserState.isErasing) {
                                Modifier.pointerInput(isRestoreMode) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: continue

                                            when {
                                                // DRAG START: Finger pressed down
                                                change.pressed && !change.previousPressed -> {
                                                    viewModel.startStroke(change.position, isRestore = isRestoreMode)
                                                    change.consume()
                                                }

                                                // DRAG MOVE: Finger is moving while pressed
                                                change.pressed && change.previousPressed -> {
                                                    // Get all intermediate points to avoid gaps
                                                    val history = change.historical
                                                    history.forEach {
                                                        viewModel.continueStroke(it.position)
                                                    }
                                                    // Add the final, current point
                                                    viewModel.continueStroke(change.position)

                                                    // Consume all changes in this event
                                                    event.changes.forEach { it.consume() }
                                                }

                                                // DRAG END: Finger lifted up
                                                !change.pressed && change.previousPressed -> {
                                                    viewModel.finishStroke()
                                                    change.consume()
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // NEW: Base image layer
                    val displayBitmap = when {
                        livePreview != null -> livePreview  // Blended result after stroke ends
                        else -> previewBitmap ?: currentBitmap
                    }


                    if (displayBitmap != null) {
                        Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // NEW: Overlay the temp stroke while drawing (instant feedback)
                        if (eraserState.isErasing) {
                            tempStrokeBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Stroke Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    } else {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            // Bottom Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(vertical = 12.dp)
            ) {
                if (eraserState.isErasing) {
                    // Eraser controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Undo button
                        IconButton(
                            onClick = { viewModel.undoStroke() },
                            enabled = eraserState.canUndo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (eraserState.canUndo) Color.White else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Redo button
                        IconButton(
                            onClick = { viewModel.redoStroke() },
                            enabled = eraserState.canRedo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = if (eraserState.canRedo) Color.White else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Erase button
                        Button(
                            onClick = { isRestoreMode = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isRestoreMode) Color(0xFF4CAF50) else Color(0xFF424242)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_eraser),
                                contentDescription = "Erase",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Erase", fontWeight = FontWeight.Bold)
                        }

                        // Restore button
                        Button(
                            onClick = { isRestoreMode = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRestoreMode) Color(0xFF2196F3) else Color(0xFF424242)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Restore",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Filter categories and thumbnails
                    if (filters.isNotEmpty()) {
                        val categoryOrder = listOf("SIMPLE", "FX", "BLUR")
                        val sortedCategories = filters.keys.sortedBy { category ->
                            val index = categoryOrder.indexOf(category)
                            if (index == -1) Int.MAX_VALUE else index
                        }

                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(sortedCategories) { category ->
                                Text(
                                    text = category,
                                    color = if (selectedCategory == category) Color.White else Color.Gray,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clickable { selectedCategory = category }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Divider(
                            color = Color.Gray.copy(alpha = 0.3f),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    // Filter Thumbnails
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            FilterItem(
                                name = "None",
                                thumbnail = bitmap,
                                isSelected = selectedFilter?.name == "ORIGINAL" || selectedFilter == null
                            ) {
                                currentBitmap = bitmap
                                scope.launch(Dispatchers.Default) {
                                    viewModel.selectFilter(
                                        Filter("ORIGINAL", "NONE", ""),
                                        bitmap
                                    )
                                }
                            }
                        }

                        val categoryFilters = filters[selectedCategory] ?: emptyList()
                        items(categoryFilters) { filter ->
                            var thumb by remember { mutableStateOf<Bitmap?>(null) }

                            LaunchedEffect(filter.name) {
                                scope.launch(Dispatchers.Default) {
                                    try {
                                        val resized = Bitmap.createScaledBitmap(
                                            bitmap,
                                            150,
                                            150,
                                            true
                                        )
                                        val thumbBmp = FilterProcessor.process(resized, filter)
                                        thumb = thumbBmp
                                        resized.recycle()
                                    } catch (e: Exception) {
                                        android.util.Log.e(
                                            "FiltersScreen",
                                            "Thumbnail failed: ${filter.name}",
                                            e
                                        )
                                        thumb = Bitmap.createScaledBitmap(bitmap, 150, 150, true)
                                    }
                                }
                            }

                            FilterItem(
                                name = filter.name,
                                thumbnail = thumb,
                                isSelected = selectedFilter?.name == filter.name
                            ) {
                                scope.launch(Dispatchers.Default) {
                                    viewModel.selectFilter(filter, bitmap)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Eraser Settings Panel
        EraserSettingsPanel(
            visible = eraserState.showSettings,
            settings = eraserState.settings,
            onSettingsChange = { viewModel.updateEraserSettings(it) },
            onDismiss = { viewModel.toggleEraserSettings() },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}