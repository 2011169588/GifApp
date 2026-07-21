package com.example.gifapp.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.gifapp.ui.theme.SegmentColors

/**
 * Zoomable/pannable canvas showing the source image with colored rectangle overlays
 * for each GIF segment and dark overlays for gap regions.
 */
@Composable
fun ImageCanvas(
    bitmap: Bitmap?,
    segmentCount: Int,
    gapRatio: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransformChanged: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentScale by remember(scale) { mutableFloatStateOf(scale) }
    var currentOffsetX by remember(offsetX) { mutableFloatStateOf(offsetX) }
    var currentOffsetY by remember(offsetY) { mutableFloatStateOf(offsetY) }

    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            currentScale = (currentScale * zoom).coerceIn(0.3f, 5f)
                            currentOffsetX += pan.x
                            currentOffsetY += pan.y
                            onTransformChanged(currentScale, currentOffsetX, currentOffsetY)
                        }
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val imgW = imageBitmap.width.toFloat()
                val imgH = imageBitmap.height.toFloat()
                val fitScale = minOf(
                    canvasWidth / imgW,
                    canvasHeight / imgH
                ) * 0.9f

                val displayW = imgW * fitScale
                val displayH = imgH * fitScale
                val baseX = (canvasWidth - displayW) / 2f
                val baseY = (canvasHeight - displayH) / 2f

                val finalScale = fitScale * currentScale
                val finalW = imgW * finalScale
                val finalH = imgH * finalScale

                val drawLeft = baseX + currentOffsetX - (finalW - displayW) / 2f
                val drawTop = baseY + currentOffsetY - (finalH - displayH) / 2f

                // Draw the image
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()),
                    dstSize = IntSize(finalW.toInt(), finalH.toInt())
                )

                // Draw segment overlays
                if (segmentCount > 0) {
                    drawSegmentOverlays(
                        drawLeft = drawLeft,
                        drawTop = drawTop,
                        segWidth = finalW,
                        segHeight = finalH,
                        segmentCount = segmentCount,
                        gapRatio = gapRatio,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawSegmentOverlays(
    drawLeft: Float,
    drawTop: Float,
    segWidth: Float,
    segHeight: Float,
    segmentCount: Int,
    gapRatio: Float,
    canvasWidth: Float,
    canvasHeight: Float
) {
    val clipLeft = drawLeft.coerceAtLeast(0f)
    val clipTop = drawTop.coerceAtLeast(0f)
    val clipRight = (drawLeft + segWidth).coerceAtMost(canvasWidth)
    val clipBottom = (drawTop + segHeight).coerceAtMost(canvasHeight)

    if (clipRight <= clipLeft || clipBottom <= clipTop) return

    val gapH = segHeight * gapRatio
    val segmentH = if (segmentCount > 1) {
        (segHeight - gapH * (segmentCount - 1)) / segmentCount
    } else {
        segHeight
    }

    if (segmentH <= 0f || gapH < 0f) return

    clipRect(left = clipLeft, top = clipTop, right = clipRight, bottom = clipBottom) {
        for (i in 0 until segmentCount) {
            val top = drawTop + i * (segmentH + gapH)
            val bottom = top + segmentH

            if (bottom <= drawTop || top >= drawTop + segHeight) continue

            val visTop = top.coerceAtLeast(drawTop)
            val visBottom = bottom.coerceAtMost(drawTop + segHeight)
            val segmentHeight = visBottom - visTop

            if (segmentHeight <= 0f) continue

            val color = SegmentColors[i % SegmentColors.size]

            // Semi-transparent fill
            drawRect(
                color = color.copy(alpha = 0.12f),
                topLeft = Offset(drawLeft, visTop),
                size = Size(segWidth, segmentHeight)
            )

            // Side borders
            drawRect(
                color = color.copy(alpha = 0.7f),
                topLeft = Offset(drawLeft, visTop),
                size = Size(3f, segmentHeight)
            )
            drawRect(
                color = color.copy(alpha = 0.7f),
                topLeft = Offset(drawLeft + segWidth - 3f, visTop),
                size = Size(3f, segmentHeight)
            )

            // Top/bottom borders
            drawRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset(drawLeft, visTop),
                size = Size(segWidth, 2f)
            )
            drawRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset(drawLeft, visBottom - 2f),
                size = Size(segWidth, 2f)
            )

            // Segment label
            val label = "#${i + 1}"
            val textPaint = android.graphics.Paint().apply {
                setColor(android.graphics.Color.WHITE)
                textSize = 32f
                isAntiAlias = true
                isFakeBoldText = true
            }
            val textW = textPaint.measureText(label)
            val pillLeft = drawLeft + 10f
            val pillTop = visTop + 10f
            val pillW = textW + 18f
            val pillH = textPaint.textSize + 10f

            // Pill background
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(pillLeft, pillTop),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(pillH / 2f)
            )

            // Label text
            drawContext.canvas.nativeCanvas.drawText(
                label,
                pillLeft + 9f,
                pillTop + textPaint.textSize - 2f,
                textPaint
            )

            // Gap area between segments
            if (i < segmentCount - 1) {
                val gapTop = bottom
                val gapBottom = minOf(bottom + gapH, drawTop + segHeight)

                if (gapBottom > gapTop) {
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset(drawLeft, gapTop),
                        size = Size(segWidth, gapBottom - gapTop)
                    )

                    // Dashed line
                    val dashLen = 8f
                    val dashGap = 6f
                    val lineY = gapTop + (gapBottom - gapTop) / 2f
                    var x = drawLeft
                    while (x < drawLeft + segWidth) {
                        val endX = minOf(x + dashLen, drawLeft + segWidth)
                        drawLine(
                            color = Color.White.copy(alpha = 0.25f),
                            start = Offset(x, lineY),
                            end = Offset(endX, lineY),
                            strokeWidth = 1.5f
                        )
                        x += dashLen + dashGap
                    }

                    // Gap label
                    val gapLabel = "✕ ${(gapRatio * 100).toInt()}%"
                    val gapTextPaint = android.graphics.Paint().apply {
                        setColor(android.graphics.Color.WHITE)
                        textSize = 22f
                        isAntiAlias = true
                        alpha = 80
                    }
                    val gapTextW = gapTextPaint.measureText(gapLabel)
                    drawContext.canvas.nativeCanvas.drawText(
                        gapLabel,
                        drawLeft + segWidth / 2f - gapTextW / 2f,
                        lineY + 8f,
                        gapTextPaint
                    )
                }
            }
        }
    }
}
