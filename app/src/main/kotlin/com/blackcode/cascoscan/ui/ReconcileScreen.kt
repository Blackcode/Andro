@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.SheetEntity
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.Reconciler
import com.blackcode.cascoscan.domain.ReconcileService
import com.blackcode.cascoscan.pdf.PdfPageSource
import com.blackcode.cascoscan.report.OverlayRenderer
import com.blackcode.cascoscan.ui.components.CountTile
import com.blackcode.cascoscan.ui.components.SheetViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the comparison between the sheet that *requires* penetrations and the sheet that should
 * *contain* them.
 *
 * The control-point flow exists because the automatic alignment cannot be relied on in the very case
 * the app is for: a structural sheet that is missing most of its openings has few points in common
 * with the MEP sheet, so there is nothing for RANSAC to lock onto. Two taps on a pair of grid
 * intersections settles it, and the user can see the overlay agree before trusting any finding.
 */
class ReconcileViewModel(
    private val context: Context,
    private val projectId: String,
    private val repository: AuditRepository,
    private val service: ReconcileService,
) : ViewModel() {

    val sheets: StateFlow<List<SheetEntity>> =
        repository.sheets(projectId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _referenceId = MutableStateFlow<String?>(null)
    val referenceId: StateFlow<String?> = _referenceId

    private val _targetId = MutableStateFlow<String?>(null)
    val targetId: StateFlow<String?> = _targetId

    private val _outcome = MutableStateFlow<ReconcileService.Outcome?>(null)
    val outcome: StateFlow<ReconcileService.Outcome?> = _outcome

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _controlPointMode = MutableStateFlow(false)
    val controlPointMode: StateFlow<Boolean> = _controlPointMode

    private val _referencePoints = MutableStateFlow<List<Pt>>(emptyList())
    val referencePoints: StateFlow<List<Pt>> = _referencePoints

    private val _targetPoints = MutableStateFlow<List<Pt>>(emptyList())
    val targetPoints: StateFlow<List<Pt>> = _targetPoints

    private val _referencePreview = MutableStateFlow<ImageBitmap?>(null)
    val referencePreview: StateFlow<ImageBitmap?> = _referencePreview

    private val _targetPreview = MutableStateFlow<ImageBitmap?>(null)
    val targetPreview: StateFlow<ImageBitmap?> = _targetPreview

    fun clearMessage() {
        _message.value = null
    }

    fun chooseReference(sheetId: String) {
        _referenceId.value = sheetId
        _outcome.value = null
        _referencePoints.value = emptyList()
        viewModelScope.launch { _referencePreview.value = render(sheetId) }
    }

    fun chooseTarget(sheetId: String) {
        _targetId.value = sheetId
        _outcome.value = null
        _targetPoints.value = emptyList()
        viewModelScope.launch { _targetPreview.value = render(sheetId) }
    }

    fun toggleControlPointMode() {
        _controlPointMode.value = !_controlPointMode.value
        _referencePoints.value = emptyList()
        _targetPoints.value = emptyList()
    }

    /** Points are taken in pairs: one on the reference, then its twin on the target. */
    fun addReferencePoint(point: Pt) {
        if (_referencePoints.value.size <= _targetPoints.value.size) {
            _referencePoints.value = _referencePoints.value + point
        }
    }

    fun addTargetPoint(point: Pt) {
        if (_targetPoints.value.size < _referencePoints.value.size) {
            _targetPoints.value = _targetPoints.value + point
        }
    }

    fun clearPoints() {
        _referencePoints.value = emptyList()
        _targetPoints.value = emptyList()
    }

    fun compare() {
        val reference = _referenceId.value ?: return
        val target = _targetId.value ?: return
        val pairs = _referencePoints.value.zip(_targetPoints.value)
        _busy.value = true
        viewModelScope.launch {
            val result = service.reconcile(projectId, reference, target, pairs)
            _busy.value = false
            result.onSuccess { _outcome.value = it }
            result.onFailure { _message.value = it.message ?: "Could not compare those sheets." }
        }
    }

    fun applyFindings() {
        val target = _targetId.value ?: return
        val current = _outcome.value ?: return
        viewModelScope.launch {
            service.apply(projectId, target, current)
            _message.value = "Added ${current.result.requiredCount} rows to that sheet's checklist, " +
                "${current.result.missingCount} of them marked missing."
        }
    }

    private suspend fun render(sheetId: String): ImageBitmap? {
        val sheet = repository.findSheet(sheetId) ?: return null
        val project = repository.findProject(projectId) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                PdfPageSource.open(context, Uri.parse(project.documentUri)).use { source ->
                    source.renderPreview(sheet.pageIndex, maxPixels = 1_400_000).asImageBitmap()
                }
            }.getOrNull()
        }
    }
}

@Composable
fun ReconcileScreen(
    container: CascoScanApp.Container,
    projectId: String,
    onBack: () -> Unit,
    onOpenSheet: (String, String) -> Unit,
) {
    val viewModel = screenViewModel(key = "reconcile-$projectId") {
        ReconcileViewModel(container.context, projectId, container.repository, container.reconcile)
    }
    val sheets by viewModel.sheets.collectAsStateWithLifecycle()
    val referenceId by viewModel.referenceId.collectAsStateWithLifecycle()
    val targetId by viewModel.targetId.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val controlPointMode by viewModel.controlPointMode.collectAsStateWithLifecycle()
    val referencePoints by viewModel.referencePoints.collectAsStateWithLifecycle()
    val targetPoints by viewModel.targetPoints.collectAsStateWithLifecycle()
    val referencePreview by viewModel.referencePreview.collectAsStateWithLifecycle()
    val targetPreview by viewModel.targetPreview.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val referenceSheet = sheets.firstOrNull { it.id == referenceId }
    val targetSheet = sheets.firstOrNull { it.id == targetId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What is missing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Compare the sheet that asks for the penetrations with the sheet that should have " +
                        "them. Anything required but not found is reported as missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SheetPicker("Required by", sheets, referenceId, viewModel::chooseReference)
            }
            item {
                SheetPicker("Should contain them", sheets, targetId, viewModel::chooseTarget)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::compare,
                        enabled = !busy && referenceId != null && targetId != null && referenceId != targetId,
                    ) { Text(if (controlPointMode) "Compare using my points" else "Compare") }
                    OutlinedButton(onClick = viewModel::toggleControlPointMode) {
                        Text(if (controlPointMode) "Use automatic overlay" else "Line up by hand")
                    }
                }
            }

            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Text("  Comparing…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (controlPointMode && referenceSheet != null && targetSheet != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Tap the same two features on both sheets - a grid intersection or a " +
                                    "building corner. Tap on the top sheet, then its twin below.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Pairs so far: ${minOf(referencePoints.size, targetPoints.size)}" +
                                    if (referencePoints.size > targetPoints.size) " (waiting for the twin below)" else "",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(onClick = viewModel::clearPoints) { Text("Start over") }
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        SheetViewer(
                            bitmap = referencePreview,
                            pageWidthPx = referenceSheet.widthPx,
                            penetrations = emptyList(),
                            acceptThreshold = 1.0,
                            controlPoints = referencePoints,
                            onTapBlank = viewModel::addReferencePoint,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        SheetViewer(
                            bitmap = targetPreview,
                            pageWidthPx = targetSheet.widthPx,
                            penetrations = emptyList(),
                            acceptThreshold = 1.0,
                            controlPoints = targetPoints,
                            onTapBlank = viewModel::addTargetPoint,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            outcome?.let { current ->
                item { OutcomeSummary(current) }
                current.warning?.let { warning ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(warning, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::applyFindings) { Text("Add to checklist") }
                        targetSheet?.let { sheet ->
                            OutlinedButton(onClick = { onOpenSheet(projectId, sheet.id) }) { Text("Open sheet") }
                        }
                    }
                }
                if (current.result.missingInTarget.isNotEmpty()) {
                    item {
                        Text("Missing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    items(current.result.missingInTarget, key = { "missing-${it.id}" }) { missing ->
                        MissingRow(missing)
                    }
                }
                if (current.result.deviations.isNotEmpty()) {
                    item {
                        Text(
                            "Found but not right",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(current.result.deviations, key = { "dev-${it.reference.id}" }) { pair ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${pair.reference.id} → ${pair.target.id}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    when (pair.verdict) {
                                        Reconciler.MatchVerdict.SIZE_MISMATCH ->
                                            "Size differs" + (pair.sizeDeltaMm?.let { " by ${it.toInt()} mm" } ?: "")
                                        Reconciler.MatchVerdict.POSITION_SHIFTED ->
                                            "Offset by ${pair.offsetMm?.toInt() ?: pair.offsetPx.toInt()} mm"
                                        Reconciler.MatchVerdict.OK -> "Correct"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (current.result.extraInTarget.isNotEmpty()) {
                    item {
                        Text(
                            "Openings nobody asked for (${current.result.extraInTarget.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item {
                        Text(
                            current.result.extraInTarget.joinToString { it.id },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetPicker(
    label: String,
    sheets: List<SheetEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sheets.forEach { sheet ->
                FilterChip(
                    selected = sheet.id == selectedId,
                    onClick = { onSelect(sheet.id) },
                    label = { Text(sheet.name) },
                )
            }
        }
    }
}

@Composable
private fun OutcomeSummary(outcome: ReconcileService.Outcome) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CountTile(outcome.result.requiredCount.toString(), "required", modifier = Modifier.weight(1f))
        CountTile(outcome.result.matched.size.toString(), "found", modifier = Modifier.weight(1f))
        CountTile(
            outcome.result.missingCount.toString(),
            "missing",
            accent = Color(OverlayRenderer.Palette.MISSING).takeIf { outcome.result.missingCount > 0 },
            modifier = Modifier.weight(1f),
        )
        CountTile("${outcome.inliers}/${outcome.total}", "lined up", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MissingRow(missing: Penetration) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${missing.id} · ${missing.displaySize}",
                fontWeight = FontWeight.SemiBold,
                color = Color(OverlayRenderer.Palette.MISSING),
            )
            Text(
                "Required at ${missing.center.x.toInt()}, ${missing.center.y.toInt()} px on sheet " +
                    "${missing.pageIndex + 1}" +
                    (missing.label?.raw?.let { " · \"$it\"" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
