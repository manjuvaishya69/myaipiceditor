package com.dlab.myaipiceditor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun CurvesEditorCanvasNormalized(
    points: List<Offset>,
    selectedChannel: String,
    onPointsChange: (List<Offset>) -> Unit,
    onDragStateChange: (Boolean) -> Unit = {},
    onDragEnd: () -> Unit = {}, // ✅ FIX #1: New callback for drag end
    modifier: Modifier = Modifier
) {
    val visualPoints = remember { mutableStateListOf<Offset>() }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    val channelColor = when (selectedChannel) {
        "Red" -> Color(0xFFFF0000)
        "Green" -> Color(0xFF00FF00)
        "Blue" -> Color(0xFF0000FF)
        else -> Color.White
    }

    val animatedPoints = remember(points.size) {
        List(points.size) { i ->
            Animatable(points.getOrNull(i) ?: Offset.Zero, Offset.VectorConverter)
        }
    }

    val scope = rememberCoroutineScope()
    val updateChannel = remember { Channel<List<Offset>>(Channel.CONFLATED) }

    LaunchedEffect(points) {
        if (!isDragging) {
            visualPoints.clear()
            visualPoints.addAll(points)
            points.forEachIndexed { index, point ->
                if (index < animatedPoints.size) {
                    animatedPoints[index].snapTo(point)
                }
            }
        }
    }

    LaunchedEffect(isDragging, selectedIndex) {
        if (isDragging && selectedIndex != null) {
            val idx = selectedIndex!!
            snapshotFlow { animatedPoints[idx].value }
                .collect { animatedValue ->
                    visualPoints[idx] = animatedValue
                    updateChannel.trySend(visualPoints.toList())
                }
        }
    }

    LaunchedEffect(Unit) {
        updateChannel.consumeAsFlow()
            .collect { newPoints ->
                if (isDragging) {
                    onPointsChange(newPoints)
                }
            }
    }

    LaunchedEffect(isDragging) {
        onDragStateChange(isDragging)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(0.32f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(visualPoints.size) {
                    detectTapGestures(
                        onTap = { tap ->
                            val normX = (tap.x / size.width).coerceIn(0f, 1f)
                            val normY = (1f - (tap.y / size.height)).coerceIn(0f, 1f)
                            val normTap = Offset(normX, normY)

                            val nearAny = visualPoints.any {
                                abs(it.x - normTap.x) < 0.05f &&
                                        abs(it.y - normTap.y) < 0.05f
                            }

                            if (!nearAny) {
                                val newPoints = (visualPoints + normTap)
                                    .sortedBy { it.x }
                                    .map { Offset(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                                onPointsChange(newPoints)
                                // ✅ FIX #1: Add to history after tap-adding point
                                onDragEnd()
                            }
                        },
                        onLongPress = { longPress ->
                            if (visualPoints.size > 2) {
                                val normX = longPress.x / size.width
                                val normY = 1f - (longPress.y / size.height)

                                val idx = visualPoints.indexOfFirst {
                                    abs(it.x - normX) < 0.05f &&
                                            abs(it.y - normY) < 0.05f
                                }

                                if (idx >= 0) {
                                    val newPoints = visualPoints.toMutableList().also { it.removeAt(idx) }
                                    onPointsChange(newPoints)
                                    // ✅ FIX #1: Add to history after deleting point
                                    onDragEnd()
                                }
                            }
                        }
                    )
                }
                .pointerInput(visualPoints.size) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val normX = start.x / size.width
                            val normY = 1f - (start.y / size.height)

                            selectedIndex = visualPoints.indexOfFirst {
                                abs(it.x - normX) < 0.05f &&
                                        abs(it.y - normY) < 0.05f
                            }.takeIf { it >= 0 }

                            isDragging = selectedIndex != null
                        },
                        onDrag = { change, _ ->
                            selectedIndex?.let { idx ->
                                val normX = (change.position.x / size.width)
                                val normY = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)

                                val clampedX = when {
                                    idx == 0 -> 0f
                                    idx == visualPoints.lastIndex -> 1f
                                    else -> {
                                        val prevX = visualPoints[idx - 1].x
                                        val nextX = visualPoints[idx + 1].x
                                        val buffer = 0.015f
                                        normX.coerceIn(prevX + buffer, nextX - buffer)
                                    }
                                }

                                val targetOffset = Offset(clampedX, normY)

                                scope.launch {
                                    animatedPoints[idx].animateTo(
                                        targetValue = targetOffset,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 400f,
                                        )
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            if (isDragging && selectedIndex != null) {
                                onPointsChange(visualPoints.toList())
                                // ✅ FIX #1: Notify parent to add to history
                                onDragEnd()
                            }
                            selectedIndex = null
                            isDragging = false
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height

            // Draw grid
            for (i in 0..4) {
                val x = width * i / 4f
                val y = height * i / 4f
                drawLine(Color.White, Offset(x, 0f), Offset(x, height), strokeWidth = 2.5f)
                drawLine(Color.White, Offset(0f, y), Offset(width, y), strokeWidth = 2.5f)
            }

            // Diagonal guideline
            drawLine(
                Color.White.copy(0.5f),
                Offset(0f, height),
                Offset(width, 0f),
                strokeWidth = 1.5f
            )

            val currentPoints = if (isDragging) {
                animatedPoints.map { it.value }
            } else {
                visualPoints.toList()
            }

            if (currentPoints.size >= 2) {
                val sortedPoints = currentPoints.sortedBy { it.x }
                val xs = sortedPoints.map { it.x }
                val ys = sortedPoints.map { it.y }

                val path = Path()
                val steps = 100

                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val y = cubicSpline(xs, ys, t)
                    val screenX = t * width
                    val screenY = (1f - y) * height

                    if (i == 0) {
                        path.moveTo(screenX, screenY)
                    } else {
                        path.lineTo(screenX, screenY)
                    }
                }
                drawPath(path, color = channelColor, style = Stroke(width = 4f))
            }

            currentPoints.forEachIndexed { idx, point ->
                val pt = Offset(
                    point.x * width,
                    (1f - point.y) * height
                )

                // Shadow
                drawCircle(
                    color = Color.Black.copy(0.5f),
                    radius = 28f,
                    center = Offset(pt.x + 2f, pt.y + 2f)
                )

                // Main circle
                drawCircle(
                    color = if (selectedIndex == idx) channelColor else Color.White,
                    radius = 23f,
                    center = pt
                )

                // Inner dot
                drawCircle(
                    color = Color.Black.copy(0.4f),
                    radius = 11f,
                    center = pt
                )
            }
        }
    }
}

// Helper functions
private fun cubicSpline(xs: List<Float>, ys: List<Float>, t: Float): Float {
    if (xs.size < 2) return ys.firstOrNull() ?: t

    if (xs.size == 2) {
        val x1 = xs[0]
        val x2 = xs[1]
        val y1 = ys[0]
        val y2 = ys[1]
        val denominator = (x2 - x1)
        if (denominator == 0f) return y1
        val mu = (t - x1) / denominator
        return (y1 + mu * (y2 - y1)).coerceIn(0f, 1f)
    }

    for (i in 0 until xs.lastIndex) {
        if (t in xs[i]..xs[i + 1]) {
            val x0 = if (i > 0) xs[i - 1] else xs[i]
            val x1 = xs[i]
            val x2 = xs[i + 1]
            val x3 = if (i + 2 < xs.size) xs[i + 2] else xs[i + 1]

            val y0 = if (i > 0) ys[i - 1] else ys[i]
            val y1 = ys[i]
            val y2 = ys[i + 1]
            val y3 = if (i + 2 < ys.size) ys[i + 2] else ys[i + 1]

            val mu = (t - x1) / (x2 - x1).let { if (it == 0f) 1f else it }
            return catmullRom(y0, y1, y2, y3, mu)
        }
    }
    return ys.last()
}

private fun catmullRom(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
    val mu2 = mu * mu
    val a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
    val a1 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
    val a2 = -0.5f * y0 + 0.5f * y2
    val a3 = y1
    return (a0 * mu * mu2 + a1 * mu2 + a2 * mu + a3).coerceIn(0f, 1f)
}