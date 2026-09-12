@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.detect.Audit
import com.blackcode.cascoscan.detect.DetectionConfig
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.pdf.PdfPageSource
import com.blackcode.cascoscan.report.Exporter
import com.blackcode.cascoscan.report.OverlayRenderer
import com.blackcode.cascoscan.report.ReportBuilder
import com.blackcode.cascoscan.ui.components.CountTile
import com.blackcode.cascoscan.ui.components.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportViewModel(
    private val context: Context,
    private val projectId: String,
    private val repository: AuditRepository,
    private val config: DetectionConfig,
) : ViewModel() {

    val penetrations: StateFlow<List<Penetration>> = repository.projectPenetrations(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _exported = MutableStateFlow<List<Uri>>(emptyList())
    val exported: StateFlow<List<Uri>> = _exported

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val acceptThreshold: Double get() = config.acceptThreshold

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Builds the deliverable.
     *
     * The marked-up sheets are rendered here rather than reused from the drawing screen: a report is
     * printed and mailed, so it gets its own larger raster - and one page at a time, because holding
     * every sheet of a twenty-sheet set in memory at print resolution would not survive.
     */
    fun export(auditor: String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching { build(auditor) }
            _busy.value = false
            outcome
                .onSuccess { uris ->
                    _exported.value = uris
                    _message.value = "Report written. Share it from the button above."
                }
                .onFailure { _message.value = "Could not build the report: ${it.message ?: "unknown error"}" }
        }
    }

    private suspend fun build(auditor: String?): List<Uri> = withContext(Dispatchers.IO) {
        val project = repository.findProject(projectId) ?: error("project is gone")
        val sheets = repository.sheetsOnce(projectId)
        val rows = penetrations.first()
        val builder = ReportBuilder(config.acceptThreshold)

        val sections = PdfPageSource.open(context, Uri.parse(project.documentUri)).use { source ->
            sheets.map { sheet ->
                val onSheet = rows.filter { it.pageIndex == sheet.pageIndex }
                val overlay = runCatching {
                    source.renderPreview(sheet.pageIndex, maxPixels = REPORT_PIXELS).also { bitmap ->
                        OverlayRenderer.draw(bitmap, onSheet, sheet.widthPx, config.acceptThreshold)
                    }
                }.getOrNull()
                ReportBuilder.SheetSection(
                    sheetName = sheet.name,
                    pageIndex = sheet.pageIndex,
                    sheetFormat = sheet.sheetFormat,
                    scaleNote = project.ratioDenominator
                        ?.let { "1:${it.toInt()} · %.2f mm/px".format(sheet.mmPerPx ?: 0.0) }
                        ?: "scale not set",
                    diagnostics = sheet.diagnostics,
                    penetrations = onSheet,
                    overlay = overlay,
                )
            }
        }

        val meta = ReportBuilder.Meta(
            projectName = project.name,
            auditor = auditor?.takeIf { it.isNotBlank() },
            documentName = Uri.parse(project.documentUri).lastPathSegment ?: "drawing set",
        )
        val html = Exporter.writeReport(context, project.name, builder.html(meta, sections))
        val csv = Exporter.writeCsv(context, project.name, builder.csv(meta, sections))
        // Release the overlays now rather than waiting for a GC that may not come before the next export.
        sections.forEach { it.overlay?.recycle() }
        listOf(html, csv)
    }

    fun shareIntent(): Intent? {
        val uris = _exported.value
        if (uris.isEmpty()) return null
        return Exporter.shareIntent(uris, "Penetration audit")
    }

    private companion object {
        /** Enough to read a marked-up A1 sheet on screen or on an A3 print. */
        const val REPORT_PIXELS = 3_200_000
    }
}

@Composable
fun ReportScreen(
    container: CascoScanApp.Container,
    projectId: String,
    onBack: () -> Unit,
) {
    val viewModel = screenViewModel(key = "report-$projectId") {
        ReportViewModel(container.context, projectId, container.repository, container.detectionConfig)
    }
    val context = LocalContext.current
    val penetrations by viewModel.penetrations.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var auditor by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val summary = Audit.summarise(penetrations, viewModel.acceptThreshold)
    val findings = Audit.findings(penetrations)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (exported.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.shareIntent()?.let { intent ->
                                    context.startActivity(Intent.createChooser(intent, "Share report"))
                                }
                            },
                        ) { Icon(Icons.Filled.Share, contentDescription = "Share") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CountTile(summary.total.toString(), "required", modifier = Modifier.weight(1f))
                    CountTile(
                        summary.missing.toString(),
                        "missing",
                        accent = Color(OverlayRenderer.Palette.MISSING).takeIf { summary.missing > 0 },
                        modifier = Modifier.weight(1f),
                    )
                    CountTile(
                        (summary.findings - summary.missing).toString(),
                        "other findings",
                        modifier = Modifier.weight(1f),
                    )
                    CountTile("%.0f%%".format(summary.progress * 100), "inspected", modifier = Modifier.weight(1f))
                }
            }

            if (summary.needingReview > 0) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${summary.needingReview} detections not yet checked", fontWeight = FontWeight.SemiBold)
                            Text(
                                "These scored below the confidence threshold and nobody has looked at them. " +
                                    "The report lists them as awaiting review rather than as findings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = auditor,
                    onValueChange = { auditor = it },
                    label = { Text("Inspected by") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.export(auditor) }, enabled = !busy) {
                        Text(if (exported.isEmpty()) "Build report" else "Rebuild")
                    }
                    if (busy) CircularProgressIndicator(Modifier.height(22.dp))
                    exported.firstOrNull()?.let { html ->
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Exporter.viewIntent(context, html))
                                }
                            },
                        ) { Text("Open") }
                    }
                }
            }

            if (findings.isEmpty()) {
                item {
                    Text(
                        if (summary.total == 0) {
                            "Nothing scanned yet."
                        } else {
                            "No findings recorded. Every penetration inspected so far was correct."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text("Findings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(findings, key = { "${it.pageIndex}-${it.id}" }) { finding ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${finding.id} · ${finding.displaySize}",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusChip(finding.status)
                            }
                            Text(
                                "Sheet ${finding.pageIndex + 1}" + (finding.note?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
