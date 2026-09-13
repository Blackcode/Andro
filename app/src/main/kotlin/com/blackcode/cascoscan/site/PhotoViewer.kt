package com.blackcode.cascoscan.site

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.report.OverlayRenderer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The photograph with what was detected drawn over it, pannable and zoomable.
 *
 * Each detection is drawn as the ellipse that was actually fitted, not as a circle around it. That is
 * deliberate: the fitted ellipse is what the measurement came from, so if it is sitting wrong on the
 * hole the auditor can see that at a glance rather than having to distrust a number with no way to check
 * it.
 */
@Composable
fun PhotoViewer(
    bitmap: ImageBitmap?,
    /** Width of the image the observations were measured in; usually a reduction of [bitmap]. */
    analysedWidth: Int,
    observations: List<SiteObservation>,
    acceptThreshold: Double,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    calibrationPoints: List<Pt> = emptyList(),
    onSelect: (SiteObservation) -> Unit = {},
    onTapBlank: (Pt) -> Unit = {},
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text("No photograph yet", style = MaterialTheme.typography.bodyMedium)
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

        // Observations are measured on a reduced copy; this maps that space onto the displayed bitmap.
        val analysedToBitmap = if (analysedWidth > 0) bitmap.width.toDouble() / analysedWidth else 1.0
        fun toScreen(p: Pt) = Offset(
            (p.x * analysedToBitmap).toFloat() * scale + offset.x,
            (p.y * analysedToBitmap).toFloat() * scale + offset.y,
        )
        fun toAnalysed(o: Offset) = Pt(
            (o.x - offset.x) / scale / analysedToBitmap,
            (o.y - offset.y) / scale / analysedToBitmap,
        )

        val hitRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, observations) {
                    detectTapGestures { tap ->
                        val hit = observations.minByOrNull { toScreen(it.center).minus(tap).getDistance() }
                        if (hit != null && toScreen(hit.center).minus(tap).getDistance() <= hitRadius) {
                            onSelect(hit)
                        } else {
                            onTapBlank(toAnalysed(tap))
                        }
                    }
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(fitScale * 0.5f, fitScale * 20f)
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

            for (observation in observations) {
                val accepted = observation.confidence >= acceptThreshold
                val colour = if (accepted) {
                    Color(OverlayRenderer.Palette.PRESENT)
                } else {
                    Color(OverlayRenderer.Palette.REVIEW)
                }
                val ellipse = observation.features.ellipse
                // Draw the fitted ellipse itself, sampled, so the fit is visible and checkable.
                val steps = 48
                var previous: Offset? = null
                for (i in 0..steps) {
                    val t = 2.0 * Math.PI * i / steps
                    val point = toScreen(ellipse.pointAt(t))
                    previous?.let { drawLine(colour, it, point, strokeWidth = if (accepted) 3f else 2f) }
                    previous = point
                }
                if (!accepted) {
                    // A dashed guide ring says the machine is unsure, which colour alone cannot.
                    drawCircle(
                        color = colour,
                        radius = (max(ellipse.semiMajor, 6.0) * analysedToBitmap).toFloat() * scale + 8f,
                        center = toScreen(observation.center),
                        style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))),
                    )
                }
                if (observation.id == selectedId) {
                    drawCircle(
                        color = colour,
                        radius = (ellipse.semiMajor * analysedToBitmap).toFloat() * scale + 16f,
                        center = toScreen(observation.center),
                        style = Stroke(width = 2.5f),
                    )
                }
                // A short tick along the major axis: this is the measurement the diameter came from.
                val axis = Pt(cos(ellipse.angleRad) * ellipse.semiMajor, sin(ellipse.angleRad) * ellipse.semiMajor)
                drawLine(
                    colour,
                    toScreen(Pt(observation.center.x - axis.x, observation.center.y - axis.y)),
                    toScreen(Pt(observation.center.x + axis.x, observation.center.y + axis.y)),
                    strokeWidth = 1.5f,
                )
            }

            calibrationPoints.forEachIndexed { index, point ->
                val centre = toScreen(point)
                val colour = Color(0xFF00838F)
                drawCircle(colour, radius = 11f, center = centre, style = Stroke(width = 3f))
                drawLine(colour, centre - Offset(22f, 0f), centre + Offset(22f, 0f), strokeWidth = 2f)
                drawLine(colour, centre - Offset(0f, 22f), centre + Offset(0f, 22f), strokeWidth = 2f)
                for (ring in 0..index) {
                    drawCircle(colour, radius = 15f + ring * 5f, center = centre, style = Stroke(width = 1.2f))
                }
            }
            if (calibrationPoints.size == 2) {
                drawLine(
                    Color(0xFF00838F),
                    toScreen(calibrationPoints[0]),
                    toScreen(calibrationPoints[1]),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
                )
            }
        }
    }
}
