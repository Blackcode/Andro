@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.ProjectEntity
import com.blackcode.cascoscan.data.SheetEntity
import com.blackcode.cascoscan.detect.Audit
import com.blackcode.cascoscan.detect.DetectionConfig
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.RenderPlan
import com.blackcode.cascoscan.domain.DetectionService
import com.blackcode.cascoscan.report.OverlayRenderer
import com.blackcode.cascoscan.ui.components.CountTile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SheetsViewModel(
    private val projectId: String,
    private val repository: AuditRepository,
    private val detection: DetectionService,
    private val config: DetectionConfig,
) : ViewModel() {

    val project: StateFlow<ProjectEntity?> =
        repository.project(projectId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sheets: StateFlow<List<SheetEntity>> =
        repository.sheets(projectId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val penetrations: StateFlow<List<Penetration>> = repository.projectPenetrations(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val prototypeCount: StateFlow<Int> =
        repository.prototypeCount(projectId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _progress = MutableStateFlow<DetectionService.Progress?>(null)
    val progress: StateFlow<DetectionService.Progress?> = _progress

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var running: Job? = null
    val isRunning: Boolean get() = running?.isActive == true

    val acceptThreshold: Double get() = config.acceptThreshold

    fun clearMessage() {
        _message.value = null
    }

    fun detectSheet(sheetId: String) {
        if (isRunning) return
        running = viewModelScope.launch {
            val outcome = detection.detectSheet(projectId, sheetId) { _progress.value = it }
            _progress.value = null
            _message.value = outcome.fold(
                onSuccess = { result ->
                    "Found ${result.penetrations.size} on this sheet" +
                        if (result.needingReview.isNotEmpty()) ", ${result.needingReview.size} need a look" else ""
                },
                onFailure = { "Detection failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    fun detectAll(force: Boolean) {
        if (isRunning) return
        running = viewModelScope.launch {
            val outcome = detection.detectProject(projectId, force = force) { _progress.value = it }
            _progress.value = null
            _message.value = outcome.fold(
                onSuccess = { "Scanned $it sheet${if (it == 1) "" else "s"}" },
                onFailure = { "Detection failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    fun cancel() {
        running?.cancel()
        running = null
        _progress.value = null
    }

    fun setScale(ratioDenominator: Double) {
        viewModelScope.launch {
            repository.setProjectScale(projectId, ratioDenominator)
            _message.value = "Scale set to 1:${ratioDenominator.toInt()}. Sheets already scanned need another run."
        }
    }

    fun forgetLearning() {
        viewModelScope.launch {
            repository.clearPrototypes(projectId)
            _message.value = "Cleared what the app had learned from your corrections."
        }
    }

    override fun onCleared() {
        running?.cancel()
        super.onCleared()
    }
}

@Composable
fun SheetsScreen(
    container: CascoScanApp.Container,
    projectId: String,
    onBack: () -> Unit,
    onOpenSheet: (String) -> Unit,
    onReconcile: () -> Unit,
    onReport: () -> Unit,
) {
    val viewModel = screenViewModel(key = projectId) {
        SheetsViewModel(projectId, container.repository, container.detection, container.detectionConfig)
    }
    val project by viewModel.project.collectAsStateWithLifecycle()
    val sheets by viewModel.sheets.collectAsStateWithLifecycle()
    val penetrations by viewModel.penetrations.collectAsStateWithLifecycle()
    val learned by viewModel.prototypeCount.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showScaleDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val summary = Audit.summarise(penetrations, viewModel.acceptThreshold)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.name ?: "Drawing set", maxLines = 1)
                        Text(
                            project?.ratioDenominator?.let { "1:${it.toInt()}" } ?: "scale not set",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showScaleDialog = true }) {
                        Icon(Icons.Filled.Straighten, contentDescription = "Set scale")
                    }
                    IconButton(onClick = onReconcile) {
                        Icon(Icons.Filled.CompareArrows, contentDescription = "Compare sheets")
                    }
                    IconButton(onClick = onReport) {
                        Icon(Icons.Filled.Assessment, contentDescription = "Report")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
        floatingActionButton = {
            if (progress == null) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.detectAll(force = false) },
                    text = { Text(if (summary.total == 0) "Scan all sheets" else "Scan remaining") },
                    icon = { Icon(Icons.Filled.CompareArrows, contentDescription = null) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            progress?.let { current ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                when (current.stage) {
                                    DetectionService.Progress.Stage.READING_TEXT -> "Reading the drawing's text…"
                                    DetectionService.Progress.Stage.DETECTING ->
                                        "Scanning sheet ${current.sheetIndex + 1} of ${current.sheetCount}" +
                                            " — tile ${current.tilesDone} of ${current.tileCount}"
                                    DetectionService.Progress.Stage.SAVING -> "Saving results…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            LinearProgressIndicator(
                                progress = { current.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(onClick = viewModel::cancel) { Text("Stop") }
                        }
                    }
                }
            }

            if (summary.total > 0) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CountTile(summary.total.toString(), "found", modifier = Modifier.weight(1f))
                        CountTile(
                            summary.missing.toString(),
                            "missing",
                            accent = Color(OverlayRenderer.Palette.MISSING).takeIf { summary.missing > 0 },
                            modifier = Modifier.weight(1f),
                        )
                        CountTile(
                            summary.needingReview.toString(),
                            "to check",
                            accent = Color(OverlayRenderer.Palette.REVIEW).takeIf { summary.needingReview > 0 },
                            modifier = Modifier.weight(1f),
                        )
                        CountTile(
                            "%.0f%%".format(summary.progress * 100),
                            "inspected",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (project?.ratioDenominator == null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Set the plot scale", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Without it the app cannot tell a 110 mm sleeve from a 40 mm dot, and you " +
                                    "will get more false alarms.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { showScaleDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Set scale")
                            }
                        }
                    }
                }
            }

            items(sheets, key = { it.id }) { sheet ->
                val onSheet = penetrations.filter { it.pageIndex == sheet.pageIndex }
                SheetCard(
                    sheet = sheet,
                    penetrations = onSheet,
                    acceptThreshold = viewModel.acceptThreshold,
                    busy = progress != null,
                    onOpen = { onOpenSheet(sheet.id) },
                    onDetect = { viewModel.detectSheet(sheet.id) },
                )
            }

            if (learned > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Learned from $learned correction${if (learned == 1) "" else "s"} on this project",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::forgetLearning) { Text("Forget") }
                    }
                }
            }
        }
    }

    if (showScaleDialog) {
        ScaleDialog(
            current = project?.ratioDenominator,
            onDismiss = { showScaleDialog = false },
            onConfirm = {
                viewModel.setScale(it)
                showScaleDialog = false
            },
        )
    }
}

@Composable
private fun SheetCard(
    sheet: SheetEntity,
    penetrations: List<Penetration>,
    acceptThreshold: Double,
    busy: Boolean,
    onOpen: () -> Unit,
    onDetect: () -> Unit,
) {
    val summary = Audit.summarise(penetrations, acceptThreshold)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sheet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(sheet.sheetFormat)
                            append(" · ${sheet.widthPx} x ${sheet.heightPx} px at ${sheet.dpi.toInt()} dpi")
                            sheet.mmPerPx?.let { append(" · %.2f mm/px".format(it)) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (sheet.detectionState) {
                    SheetEntity.State.DONE.name ->
                        OutlinedButton(onClick = onDetect, enabled = !busy) { Text("Re-scan") }
                    SheetEntity.State.RUNNING.name -> Text("Scanning…", style = MaterialTheme.typography.labelMedium)
                    else -> Button(onClick = onDetect, enabled = !busy) { Text("Scan") }
                }
            }

            if (sheet.detectionState == SheetEntity.State.FAILED.name) {
                Text(
                    "Last scan failed: ${sheet.diagnostics ?: "unknown error"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (summary.total > 0) {
                Text(
                    buildString {
                        append("${summary.total} penetrations")
                        if (summary.missing > 0) append(" · ${summary.missing} missing")
                        if (summary.needingReview > 0) append(" · ${summary.needingReview} to check")
                        append(" · ${summary.inspected}/${summary.total} inspected")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { summary.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                sheet.diagnostics?.takeIf { sheet.detectionState == SheetEntity.State.DONE.name }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScaleDialog(current: Double?, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var choice by remember { mutableStateOf(current ?: 50.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Plot scale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20.0, 50.0, 100.0, 200.0).forEach { option ->
                        FilterChip(
                            selected = choice == option,
                            onClick = { choice = option },
                            label = { Text("1:${option.toInt()}") },
                        )
                    }
                }
                val plan = RenderPlan.forRatio(choice)
                Text(
                    "Renders at ${plan.dpi.toInt()} dpi; openings from about " +
                        "${plan.smallestReliableMm.toInt()} mm can be found." +
                        if (plan.resolutionLimited) " Smaller ones will be missed at this scale." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Applies to every sheet in the set. Sheets already scanned will need another run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
