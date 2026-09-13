@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.ar

import android.app.Activity
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.report.OverlayRenderer
import com.blackcode.cascoscan.ui.components.SheetViewer
import com.blackcode.cascoscan.ui.screenViewModel
import com.google.ar.core.ArCoreApk

/**
 * The augmented reality view: the drawing's penetrations pinned to the real walls.
 *
 * The camera image is painted by OpenGL because ARCore requires it; everything else - markers, labels,
 * controls - is ordinary Compose drawn on top from positions projected on the CPU. That split keeps the
 * graphics code to one small file and means the interface can be designed rather than hand-rasterised.
 */
@Composable
fun ArScanScreen(
    container: CascoScanApp.Container,
    projectId: String,
    sheetId: String,
    onBack: () -> Unit,
) {
    val viewModel = screenViewModel(key = "ar-$sheetId") {
        ArScanViewModel(container.context, projectId, sheetId, container.repository, container.siteConfig)
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val step by viewModel.step.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingWorldPoint by viewModel.pendingWorldPoint.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var cameraGranted by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    LaunchedEffect(Unit) {
        val already = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (already) cameraGranted = true else permission.launch(android.Manifest.permission.CAMERA)
    }

    val controller = remember { ArSessionController(context) }
    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(controller.renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(controller, viewModel) {
        controller.onTracking = viewModel::onTracking
        controller.onTrackingStateChanged = viewModel::onTrackingLost
        controller.onCapture = viewModel::onCapture
        controller.onControlPoint = viewModel::onControlPointHit
        onDispose {
            controller.onTracking = null
            controller.onTrackingStateChanged = null
            controller.onCapture = null
            controller.onControlPoint = null
        }
    }

    DisposableEffect(lifecycleOwner, cameraGranted) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (cameraGranted) {
                    // Play Services for AR may need installing; this returns and is retried next resume.
                    val install = runCatching {
                        ArCoreApk.getInstance().requestInstall(context as Activity, true)
                    }.getOrNull()
                    if (install == ArCoreApk.InstallStatus.INSTALLED || install == null) {
                        if (controller.ensureSession()) {
                            controller.setDisplayGeometry(
                                glView.display?.rotation ?: 0,
                                glView.width.coerceAtLeast(1),
                                glView.height.coerceAtLeast(1),
                            )
                            controller.resume()
                            glView.onResume()
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glView.onPause()
                    controller.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause()
            controller.pause()
            controller.close()
        }
    }

    val textMeasurer = rememberTextMeasurer()

    Box(Modifier.fillMaxSize()) {
        if (cameraGranted) {
            AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        }

        // The overlay reads the live camera inside the draw lambda, so a new frame costs a redraw and
        // not a recomposition of the whole screen.
        Canvas(Modifier.fillMaxSize()) {
            val camera = viewModel.liveCamera.value ?: return@Canvas
            for (projected in viewModel.projectedTargets(camera)) {
                drawTarget(projected, textMeasurer)
            }
            for ((observation, screen) in viewModel.projectedObservations(camera)) {
                val centre = Offset(screen.x.toFloat(), screen.y.toFloat())
                drawCircle(
                    color = Color(OverlayRenderer.Palette.PRESENT),
                    radius = 10f,
                    center = centre,
                    style = Stroke(width = 3f),
                )
                if (observation.diameterMm != null) {
                    drawCircle(Color(OverlayRenderer.Palette.PRESENT), radius = 3f, center = centre)
                }
            }
            // Crosshair: what "fix point" and "check this view" aim at.
            val middle = Offset(size.width / 2f, size.height / 2f)
            val arm = 26f
            val crosshair = Color.White.copy(alpha = 0.85f)
            drawLine(crosshair, middle - Offset(arm, 0f), middle + Offset(arm, 0f), strokeWidth = 2f)
            drawLine(crosshair, middle - Offset(0f, arm), middle + Offset(0f, arm), strokeWidth = 2f)
            drawCircle(crosshair, radius = 5f, center = middle, style = Stroke(width = 2f))
        }

        Column(Modifier.fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            tracking?.let { note ->
                Card(
                    Modifier.fillMaxWidth().padding(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) { Text(note, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) }
            }
            if (!cameraGranted) {
                Card(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(
                        "The augmented reality view needs the camera. Without it you can still review the " +
                            "drawing and tick penetrations off by hand.",
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Box(Modifier.weight(1f))

            when (step) {
                ArScanViewModel.Step.REGISTER -> RegisterControls(viewModel, controller, pendingWorldPoint != null)
                ArScanViewModel.Step.OVERLAY -> OverlayControls(viewModel, controller, onBack)
                ArScanViewModel.Step.RESULT -> ResultControls(viewModel)
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { Snackbar(it) }
    }
}

/** A required penetration, drawn where it should be on the real wall. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTarget(
    projected: ProjectedTarget,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val target = projected.target
    val colour = Color(
        OverlayRenderer.Palette.forStatus(
            target.status,
            needsReview = target.penetration.confidence < 0.55 && target.status == AuditStatus.PENDING,
        ),
    )
    val centre = Offset(projected.screen.x.toFloat(), projected.screen.y.toFloat())
    val radius = projected.screenRadiusPx.toFloat().coerceIn(10f, size.width * 0.4f)

    drawCircle(
        color = colour,
        radius = radius,
        center = centre,
        style = Stroke(width = if (target.status == AuditStatus.MISSING) 6f else 4f),
    )
    if (target.status == AuditStatus.MISSING) {
        val arm = radius * 0.72f
        drawLine(colour, centre - Offset(arm, arm), centre + Offset(arm, arm), strokeWidth = 5f)
        drawLine(colour, centre + Offset(-arm, arm), centre + Offset(arm, -arm), strokeWidth = 5f)
    }
    if (projected.heightUnknown) {
        // The plan does not say how high this is, so the marker is floated at eye level and a vertical
        // guide says as much. Drawing a fixed circle would imply a height the drawing never carried.
        drawLine(
            color = colour.copy(alpha = 0.55f),
            start = Offset(centre.x, 0f),
            end = Offset(centre.x, size.height),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
        )
    }

    val caption = buildString {
        append(target.id)
        target.penetration.displaySize.takeIf { it != "?" }?.let { append("  ").append(it) }
        append("  ").append("%.1f m".format(projected.distanceM))
    }
    val layout = textMeasurer.measure(
        caption,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colour),
    )
    val textAt = Offset(centre.x + radius + 10f, centre.y - layout.size.height / 2f)
    drawRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = textAt - Offset(4f, 2f),
        size = androidx.compose.ui.geometry.Size(
            layout.size.width + 8f,
            layout.size.height + 4f,
        ),
    )
    drawText(layout, topLeft = textAt)
}

@Composable
private fun RegisterControls(
    viewModel: ArScanViewModel,
    controller: ArSessionController,
    awaitingDrawingTap: Boolean,
) {
    val controlPoints by viewModel.controlPoints.collectAsStateWithLifecycle()
    val preview by viewModel.sheetPreview.collectAsStateWithLifecycle()
    val required by viewModel.required.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth().padding(10.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    awaitingDrawingTap -> "Now tap that same feature on the drawing below."
                    controlPoints.isEmpty() ->
                        "Aim the crosshair at something you can also find on the drawing - a column face, " +
                            "a door reveal, a grid intersection - and press Fix point."
                    controlPoints.size == 1 -> "One reference point set. Now a second, as far from the first as you can."
                    else -> "Placing the drawing..."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Two points are enough: the plot scale fixes the scale and gravity fixes the tilt, so only " +
                    "the heading and the offset are unknown. Further apart is better.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (awaitingDrawingTap) {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    SheetViewer(
                        bitmap = preview,
                        pageWidthPx = sheet?.widthPx ?: 1,
                        penetrations = required,
                        acceptThreshold = 1.0,
                        controlPoints = controlPoints.map { it.onDrawing },
                        onTapBlank = viewModel::tapDrawing,
                        onSelect = { viewModel.tapDrawing(it.center) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.requestControlPoint() }, modifier = Modifier.weight(1f)) {
                        Text("Fix point ${controlPoints.size + 1}")
                    }
                    if (controlPoints.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearControlPoints) { Text("Start over") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayControls(
    viewModel: ArScanViewModel,
    controller: ArSessionController,
    onBack: () -> Unit,
) {
    val geometry by viewModel.geometry.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth().padding(10.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            registration?.let { fit ->
                Text(
                    "Drawing placed: ${targets.size} penetrations, heading %.0f degrees, fit %.0f cm"
                        .format(fit.placement.headingDeg, fit.residualM * 100),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fit.isTrustworthy()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = geometry == TargetGeometry.ON_WALL,
                    onClick = { viewModel.setGeometry(TargetGeometry.ON_WALL) },
                    label = { Text("Through walls") },
                )
                FilterChip(
                    selected = geometry == TargetGeometry.ON_SLAB,
                    onClick = { viewModel.setGeometry(TargetGeometry.ON_SLAB) },
                    label = { Text("Through the slab") },
                )
            }
            Text(
                if (geometry == TargetGeometry.ON_WALL) {
                    "A plan cannot say how high up a wall a sleeve is, so wall markers float at your own " +
                        "height with a vertical guide."
                } else {
                    "Slab penetrations are placed exactly: a plan gives their position in full."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.requestCapture() }, modifier = Modifier.weight(1f)) {
                    Text("Check this view")
                }
                OutlinedButton(onClick = viewModel::clearControlPoints) { Text("Re-place") }
                TextButton(onClick = onBack) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ResultControls(viewModel: ArScanViewModel) {
    val match by viewModel.match.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth().padding(10.dp)) {
        Column(
            Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            match?.let { result ->
                Text(
                    "${result.matched.size} found, ${result.missing.size} missing" +
                        if (result.unexpected.isNotEmpty()) ", ${result.unexpected.size} unasked-for" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                for (missing in result.missing) {
                    Text(
                        "${missing.id} ${missing.penetration.displaySize} - not found",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(OverlayRenderer.Palette.MISSING),
                    )
                }
                for (deviation in result.deviations) {
                    Text(
                        "${deviation.target.id} - ${deviation.verdict.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(OverlayRenderer.Palette.WRONG),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::apply, enabled = !busy) { Text("Record") }
                    OutlinedButton(onClick = viewModel::backToOverlay) { Text("Discard") }
                }
            }
        }
    }
}
