package com.dlab.myaipiceditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dlab.myaipiceditor.data.EditorAction

data class FeatureItem(
    val title: String,
    val icon: ImageVector,
    val action: EditorAction,
    val isAI: Boolean = false
)

@Composable
fun FeatureButtons(
    onActionClick: (EditorAction) -> Unit,
    isProcessing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val features = listOf(
        FeatureItem("Remove Object", Icons.Default.AutoFixHigh, EditorAction.StartObjectRemoval, true),
        FeatureItem("Restore Face", Icons.Default.Face, EditorAction.RestoreFace, true),
        FeatureItem("Upscale Image", Icons.Default.ZoomIn, EditorAction.UpscaleImage, true),
        FeatureItem("Crop", Icons.Default.Crop, EditorAction.StartCrop),
        FeatureItem("Resize", Icons.Default.AspectRatio, EditorAction.ResizeImage(800, 600)),
        FeatureItem("Add Text", Icons.Default.TextFields, EditorAction.StartAddText)
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        items(features) { feature ->
            FeatureButton(
                feature = feature,
                onClick = { onActionClick(feature.action) },
                enabled = !isProcessing
            )
        }
    }
}

@Composable
private fun FeatureButton(
    feature: FeatureItem,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (feature.isAI) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = feature.title,
                tint = if (feature.isAI) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = feature.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (feature.isAI) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )

            if (feature.isAI) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}