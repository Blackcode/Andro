@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.site

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.report.OverlayRenderer
import com.blackcode.cascoscan.ui.components.CountTile
import com.blackcode.cascoscan.ui.components.SheetViewer
import com.blackcode.cascoscan.ui.screenViewModel
import java.util.concurrent.Executor
import kotlinx.coroutines.launch

/**
 * Inspecting a wall: say where you are, photograph it, and see which of the penetrations the drawing
 * asks for are actually there.
 */
@Composable
fun SiteScanScreen(
    container: CascoScanApp.Container,
    projectId: String,
    sheetId: String,
    onBack: () -> Unit,
) {
    val viewModel = screenViewModel(key = "sitescan-$sheetId") {
        SiteScanViewModel(container.context, projectId, sheetId, container.repository, container.siteConfig)
    }
    val step by viewModel.step.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val applied by viewModel.applied.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(applied) {
        if (applied) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Inspect on site")
                        Text(
                            when (step) {
                                SiteScanViewModel.Step.LOCATE -> "1 of 4 - where are you?"
                                SiteScanViewModel.Step.CAPTURE -> "2 of 4 - photograph the wall"
                                SiteScanViewModel.Step.CALIBRATE -> "3 of 4 - set the scale"
                                SiteScanViewModel.Step.REVIEW -> "4 of 4 - check and record"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (step) {
                SiteScanViewModel.Step.LOCATE -> LocateStep(viewModel, sheet?.widthPx ?: 1)
                SiteScanViewModel.Step.CAPTURE -> CaptureStep(viewModel)
                SiteScanViewModel.Step.CALIBRATE -> CalibrateStep(viewModel)
                SiteScanViewModel.Step.REVIEW -> ReviewStep(viewModel)
            }
        }
    }
}

@Composable
private fun LocateStep(viewModel: SiteScanViewModel, pageWidthPx: Int) {
    val preview by viewModel.sheetPreview.collectAsStateWithLifecycle()
    val required by viewModel.required.collectAsStateWithLifecycle()
    val tap by viewModel.tapOnDrawing.collectAsStateWithLifecycle()
    val radius by viewModel.zoneRadiusMm.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Tap the drawing where you are standing.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Only the penetrations near that point are compared with your photograph, so the app " +
                        "does not report the rest of the floor as missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SheetViewer(
                bitmap = preview,
                pageWidthPx = pageWidthPx,
                penetrations = required,
                acceptThreshold = 1.0,
                controlPoints = listOfNotNull(tap),
                onTapBlank = viewModel::tapDrawing,
                onSelect = { viewModel.tapDrawing(it.center) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.padding(12.dp)) {
            Text("How much of the building is in shot?", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(2000.0, 4000.0, 8000.0).forEach { option ->
                    FilterChip(
                        selected = radius == option,
                        onClick = { viewModel.setZoneRadius(option) },
                        label = { Text("${(option / 1000).toInt()} m") },
                    )
                }
            }
            Button(
                onClick = viewModel::toCapture,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = tap != null,
            ) { Text(if (tap == null) "Tap the drawing first" else "Photograph this area") }
        }
    }
}

@Composable
private fun CaptureStep(viewModel: SiteScanViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executor { it.run() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        val already = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (already) granted = true else permission.launch(android.Manifest.permission.CAMERA)
    }

    DisposableEffect(granted, lifecycleOwner) {
        var session: CameraCapture.Session? = null
        if (granted) {
            scope.launch {
                runCatching { CameraCapture.start(context, lifecycleOwner, previewView) }
                    .onSuccess {
                        session = it
                        capture = it.imageCapture
                    }
            }
        }
        onDispose {
            session?.unbind()
            capture = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (granted) {
                    "Stand square to the wall if you can. A hole's edge is the measurement, so a sharp, " +
                        "well-lit shot matters more than framing it tightly."
                } else {
                    "The camera is how the app sees what was actually built. Without it you can still " +
                        "review the drawing and tick penetrations off by hand."
                },
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (granted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Text("No camera permission", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::backToLocate) { Text("Back") }
            Button(
                onClick = {
                    val imageCapture = capture ?: return@Button
                    val target = viewModel.newPhotoTarget()
                    scope.launch {
                        runCatching { CameraCapture.capture(imageCapture, target, executor) }
                            .onSuccess { viewModel.onPhotoCaptured(it) }
                    }
                },
                enabled = granted && capture != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Text("  Take the photograph")
            }
        }
    }
}

@Composable
private fun CalibrateStep(viewModel: SiteScanViewModel) {
    val photo by viewModel.photo.collectAsStateWithLifecycle()
    val detection by viewModel.detection.collectAsStateWithLifecycle()
    val points by viewModel.calibrationPoints.collectAsStateWithLifecycle()
    val distance by viewModel.knownDistanceMm.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when (points.size) {
                        0 -> "Tap one end of something you know the length of."
                        1 -> "Now tap the other end."
                        else -> "Enter the real distance between those two points."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "A tape measure in shot is ideal; a door width or a block course works. Without this " +
                        "the app can still say which holes are there, but not whether they are the right size.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PhotoViewer(
                bitmap = photo,
                analysedWidth = detection?.workingImage?.width ?: 1,
                observations = detection?.observations ?: emptyList(),
                acceptThreshold = viewModel.acceptThreshold,
                calibrationPoints = points,
                onTapBlank = viewModel::tapPhoto,
                onSelect = { viewModel.tapPhoto(it.center) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = distance,
                onValueChange = viewModel::setKnownDistance,
                label = { Text("Distance between the two points (mm)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::applyCalibration, enabled = points.size == 2) { Text("Use this scale") }
                OutlinedButton(onClick = viewModel::skipCalibration) { Text("Skip sizes") }
                if (points.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearCalibration) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(viewModel: SiteScanViewModel) {
    val photo by viewModel.photo.collectAsStateWithLifecycle()
    val detection by viewModel.detection.collectAsStateWithLifecycle()
    val match by viewModel.match.collectAsStateWithLifecycle()
    val strategy by viewModel.strategy.collectAsStateWithLifecycle()
    val mmPerPx by viewModel.mmPerPx.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            PhotoViewer(
                bitmap = photo,
                analysedWidth = detection?.workingImage?.width ?: 1,
                observations = detection?.observations ?: emptyList(),
                acceptThreshold = viewModel.acceptThreshold,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            match?.let { result ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CountTile(result.requiredCount.toString(), "required here", modifier = Modifier.weight(1f))
                    CountTile(result.matched.size.toString(), "found", modifier = Modifier.weight(1f))
                    CountTile(
                        result.missing.size.toString(),
                        "missing",
                        accent = Color(OverlayRenderer.Palette.MISSING).takeIf { result.missing.isNotEmpty() },
                        modifier = Modifier.weight(1f),
                    )
                }

                result.warning?.let { warning ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) { Text(warning, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                }

                Text("How should the photograph be matched?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = strategy == SiteMatcher.Strategy.ORDER_AND_SIZE,
                        onClick = { viewModel.setStrategy(SiteMatcher.Strategy.ORDER_AND_SIZE) },
                        label = { Text("Along a wall") },
                    )
                    FilterChip(
                        selected = strategy == SiteMatcher.Strategy.PLANAR,
                        onClick = { viewModel.setStrategy(SiteMatcher.Strategy.PLANAR) },
                        label = { Text("Slab from above") },
                    )
                }
                Text(
                    if (strategy == SiteMatcher.Strategy.PLANAR) {
                        "Lines the photograph up with the plan. Needs at least two holes in common."
                    } else {
                        "Matches the holes in order along the wall, without lining the images up - which " +
                            "is what a wall needs, since the plan draws it as a line."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mmPerPx == null) {
                    Text(
                        "No scale was set, so nothing is claimed about sizes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                for (pair in result.matched) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${pair.required.id} - ${verdictLabel(pair.verdict)}",
                                fontWeight = FontWeight.SemiBold,
                                color = if (pair.verdict == SiteMatcher.Verdict.PRESENT) {
                                    Color(OverlayRenderer.Palette.PRESENT)
                                } else {
                                    Color(OverlayRenderer.Palette.WRONG)
                                },
                            )
                            Text(
                                "drawing asks ${pair.required.displaySize}, seen ${pair.observed.describeSize}" +
                                    " at ${pair.observed.tiltDeg.toInt()} degrees off-axis",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                for (missing in result.missing) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${missing.id} - not found",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(OverlayRenderer.Palette.MISSING),
                            )
                            Text(
                                "drawing asks ${missing.displaySize}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (result.unexpected.isNotEmpty()) {
                    Text(
                        "${result.unexpected.size} opening(s) found that the drawing does not ask for here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::apply, enabled = !busy) { Text("Record these findings") }
                    OutlinedButton(onClick = viewModel::backToLocate) { Text("Another area") }
                }
            } ?: Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text("  Comparing with the drawing...", style = MaterialTheme.typography.bodyMedium)
            }

            detection?.let { result ->
                Text(
                    "Detector: ${result.observations.size} dark regions kept of ${result.diagnostics.darkRegions}" +
                        ", threshold ${result.diagnostics.topHatThreshold}" +
                        ", ${result.diagnostics.elapsedMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun verdictLabel(verdict: SiteMatcher.Verdict) = when (verdict) {
    SiteMatcher.Verdict.PRESENT -> "present"
    SiteMatcher.Verdict.WRONG_SIZE -> "wrong size"
    SiteMatcher.Verdict.WRONG_POSITION -> "wrong position"
}
