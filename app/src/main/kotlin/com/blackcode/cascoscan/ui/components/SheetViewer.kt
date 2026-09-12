package com.blackcode.cascoscan.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.report.OverlayRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing, with the audit drawn on top of it.
 *
 * Three coordinate spaces meet here and conflating them is the classic way to get markers that drift
 * as you zoom:
 *  - **page** pixels, what detection worked in and what every stored position is expressed in;
 *  - **bitmap** pixels, the downscaled preview actually on screen;
 *  - **screen** pixels, after the user's pan and zoom.
 *
 * Markers are drawn in screen space with the stroke widths held constant, so a hairline marker stays a
 * hairline at 8x zoom instead of becoming a blob - the opposite of what a `graphicsLayer` transform
 * would give.
 */
@Composable
fun SheetViewer(
    bitmap: ImageBitmap?,
    pageWidthPx: Int,
    penetrations: List<Penetration>,
    acceptThreshold: Double,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    excludedRegion: IBox? = null,
    controlPoints: List<Pt> = emptyList(),
    onSelect: (Penetration) -> Unit = {},
    onTapBlank: (Pt) -> Unit = {},
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text("Rendering sheet…", style = MaterialTheme.typography.bodyMedium)
            return@BoxWithConstraints
        }

        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        var scale by remember(bitmap) { mutableFloatStateOf(0f) }
        var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

        val fitScale = remember(bitmap, viewportWidth, viewportHeight) {
            if (bitmap.width == 0 || bitmap.height == 0) 1f
            else min(viewportWidth / bitmap.width, viewportHeight / bitmap.height)
        }
        LaunchedEffect(bitmap, fitScale) {
            scale = fitScale
            offset = Offset(
                (viewportWidth - bitmap.width * fitScale) / 2f,
                (viewportHeight - bitmap.height * fitScale) / 2f,
            )
        }
        if (scale <= 0f) return@BoxWithConstraints

        // Page pixels -> bitmap pixels. Detection ran on a far larger raster than this preview.
        val pageToBitmap = if (pageWidthPx > 0) bitmap.width.toDouble() / pageWidthPx else 1.0
        fun pageToScreen(p: Pt) = Offset(
            (p.x * pageToBitmap).toFloat() * scale + offset.x,
            (p.y * pageToBitmap).toFloat() * scale + offset.y,
        )
        fun screenToPage(o: Offset) = Pt(
            ((o.x - offset.x) / scale / pageToBitmap),
            ((o.y - offset.y) / scale / pageToBitmap),
        )

        val hitRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, penetrations) {
                    detectTapGestures { tap ->
                        val hit = penetrations.minByOrNull { pageToScreen(it.center).minus(tap).getDistance() }
                        if (hit != null && pageToScreen(hit.center).minus(tap).getDistance() <= hitRadiusPx) {
                            onSelect(hit)
                        } else {
                            onTapBlank(screenToPage(tap))
                        }
                    }
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(fitScale * 0.5f, fitScale * 24f)
                        // Keep whatever is under the fingers under the fingers.
                        offset = centroid + pan - (centroid - offset) * (next / scale)
                        scale = next
                    }
                },
        ) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()),
                dstSize = IntSize(
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                ),
            )

            excludedRegion?.let { region ->
                val topLeft = pageToScreen(Pt(region.left.toDouble(), region.top.toDouble()))
                val bottomRight = pageToScreen(Pt(region.right.toDouble(), region.bottom.toDouble()))
                drawRect(
                    color = Color(0xFF757575),
                    topLeft = topLeft,
                    size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                    ),
                )
            }

            for (penetration in penetrations) {
                val center = pageToScreen(penetration.center)
                val needsReview = penetration.needsReview(acceptThreshold)
                val colour = Color(OverlayRenderer.Palette.forStatus(penetration.status, needsReview))
                val drawn = (penetration.box.longSide * pageToBitmap).toFloat() * scale / 2f
                // Never smaller than a thumb can hit: at fit-scale a sleeve is a couple of pixels wide.
                val radius = max(drawn, 9f)
                val selected = penetration.id == selectedId

                drawCircle(
                    color = colour,
                    radius = radius,
                    center = center,
                    style = Stroke(width = if (penetration.status == AuditStatus.MISSING) 3.5f else 2.2f),
                )
                if (penetration.status == AuditStatus.MISSING) {
                    val arm = radius * 0.72f
                    drawLine(colour, center - Offset(arm, arm), center + Offset(arm, arm), strokeWidth = 3f)
                    drawLine(colour, center + Offset(-arm, arm), center + Offset(arm, -arm), strokeWidth = 3f)
                }
                if (needsReview) {
                    // A dashed ring says "the machine is unsure", which the colour alone cannot.
                    drawCircle(
                        color = colour,
                        radius = radius + 5f,
                        center = center,
                        style = Stroke(
                            width = 1.8f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        ),
                    )
                }
                if (selected) {
                    drawCircle(color = colour, radius = radius + 12f, center = center, style = Stroke(width = 2.5f))
                    drawCircle(color = colour.copy(alpha = 0.14f), radius = radius + 12f, center = center)
                }
            }

            controlPoints.forEachIndexed { index, point ->
                val center = pageToScreen(point)
                val colour = Color(0xFF00838F)
                drawCircle(colour, radius = 12f, center = center, style = Stroke(width = 3f))
                drawLine(colour, center - Offset(20f, 0f), center + Offset(20f, 0f), strokeWidth = 2f)
                drawLine(colour, center - Offset(0f, 20f), center + Offset(0f, 20f), strokeWidth = 2f)
                // Index is conveyed by the ring count, since text in a DrawScope needs a text measurer.
                for (ring in 0..index) {
                    drawCircle(colour, radius = 16f + ring * 5f, center = center, style = Stroke(width = 1.2f))
                }
            }
        }
    }
}
