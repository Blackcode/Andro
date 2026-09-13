@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.Mappers
import com.blackcode.cascoscan.data.SheetEntity
import com.blackcode.cascoscan.detect.Audit
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.DetectionConfig
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.Origin
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.SizeMm
import com.blackcode.cascoscan.detect.SymbolKind
import com.blackcode.cascoscan.pdf.PdfPageSource
import com.blackcode.cascoscan.report.Exporter
import com.blackcode.cascoscan.ui.components.EvidenceBreakdown
import com.blackcode.cascoscan.ui.components.SheetViewer
import com.blackcode.cascoscan.ui.components.StatusChip
import com.blackcode.cascoscan.ui.components.statusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrawingViewModel(
    private val context: Context,
    private val projectId: String,
    private val sheetId: String,
    private val repository: AuditRepository,
    private val config: DetectionConfig,
) : ViewModel() {

    /** What the auditor wants to see. Walking a floor, "to inspect" is the only useful list. */
    enum class Filter { ALL, TO_INSPECT, TO_CHECK, FINDINGS }

    /** What a tap on blank drawing does. */
    enum class TapMode { INSPECT, ADD_PENETRATION, EXCLUDE_REGION }

    val sheet: StateFlow<SheetEntity?> =
        repository.sheet(sheetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val all: StateFlow<List<Penetration>> =
        repository.penetrations(sheetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter

    private val _tapMode = MutableStateFlow(TapMode.INSPECT)
    val tapMode: StateFlow<TapMode> = _tapMode

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** First corner of an exclusion rectangle, while the second is being chosen. */
    private val _pendingCorner = MutableStateFlow<Pt?>(null)
    val pendingCorner: StateFlow<Pt?> = _pendingCorner

    val acceptThreshold: Double get() = config.acceptThreshold

    init {
        viewModelScope.launch { renderPreview() }
    }

    /**
     * A preview raster, deliberately much smaller than the one detection used: this one only has to
     * look right on a phone screen, and holding a detection-resolution page in memory behind a live
     * UI is how the process gets killed.
     */
    private suspend fun renderPreview() {
        val sheetEntity = repository.findSheet(sheetId) ?: return
        val project = repository.findProject(projectId) ?: return
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                PdfPageSource.open(context, Uri.parse(project.documentUri)).use { source ->
                    source.renderPreview(sheetEntity.pageIndex, maxPixels = PREVIEW_PIXELS)
                }
            }.getOrNull()
        }
        if (bitmap == null) {
            _message.value = "Could not render this sheet."
            return
        }
        _preview.value = bitmap.asImageBitmap()
    }

    fun visible(rows: List<Penetration>): List<Penetration> = when (_filter.value) {
        Filter.ALL -> rows
        Filter.TO_INSPECT -> rows.filter { it.status == AuditStatus.PENDING }
        Filter.TO_CHECK -> rows.filter { it.needsReview(acceptThreshold) }
        Filter.FINDINGS -> rows.filter { it.status.isFinding }
    }

    fun setFilter(filter: Filter) {
        _filter.value = filter
    }

    fun setTapMode(mode: TapMode) {
        _tapMode.value = mode
        _pendingCorner.value = null
    }

    fun select(penetration: Penetration?) {
        _selectedId.value = penetration?.id
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setStatus(penetration: Penetration, status: AuditStatus, note: String?) {
        viewModelScope.launch {
            repository.setStatus(projectId, sheetId, penetration, status, note)
            // Confirming a detection is also a training example: the next sheet gets it right sooner.
            if (penetration.origin == Origin.DETECTED && status != AuditStatus.NOT_APPLICABLE) {
                repository.remember(projectId, penetration, wasPenetration = true)
            }
        }
    }

    /**
     * Removes something that is not a penetration at all.
     *
     * Distinct from marking it "not applicable": this says the *detection* was wrong, so the shape is
     * remembered as a negative example and the row leaves the audit entirely.
     */
    fun rejectDetection(penetration: Penetration) {
        viewModelScope.launch {
            repository.remember(projectId, penetration, wasPenetration = false)
            repository.deletePenetration(sheetId, penetration.id)
            _selectedId.value = null
            _message.value = "Removed ${penetration.id} and remembered it is not a penetration."
        }
    }

    fun addPhoto(penetration: Penetration, uri: Uri) {
        viewModelScope.launch { repository.addPhoto(projectId, sheetId, penetration, uri.toString()) }
    }

    /** Records a penetration the detector missed - the only way a false negative reaches the report. */
    fun addManual(center: Pt, diameterMm: Double?, kind: SymbolKind, note: String?) {
        viewModelScope.launch {
            val sheetEntity = repository.findSheet(sheetId) ?: return@launch
            repository.addManual(
                projectId = projectId,
                sheetId = sheetId,
                pageIndex = sheetEntity.pageIndex,
                center = center,
                sizeMm = diameterMm?.let { SizeMm(it, it) },
                kind = kind,
                note = note,
                mmPerPx = sheetEntity.mmPerPx,
            )
            _tapMode.value = TapMode.INSPECT
            _message.value = "Added a penetration at your tap."
        }
    }

    /** Two taps define the title block or legend to ignore on the next scan. */
    fun onTapBlank(point: Pt) {
        when (_tapMode.value) {
            // The screen handles these two itself: inspect does nothing on blank drawing, and adding
            // opens a dialog at the tap rather than storing a corner.
            TapMode.INSPECT, TapMode.ADD_PENETRATION -> Unit
            TapMode.EXCLUDE_REGION -> {
                val first = _pendingCorner.value
                if (first == null) {
                    _pendingCorner.value = point
                } else {
                    val region = IBox(
                        minOf(first.x, point.x).toInt(),
                        minOf(first.y, point.y).toInt(),
                        maxOf(first.x, point.x).toInt(),
                        maxOf(first.y, point.y).toInt(),
                    )
                    _pendingCorner.value = null
                    _tapMode.value = TapMode.INSPECT
                    viewModelScope.launch {
                        repository.setExcludedRegion(sheetId, region)
                        _message.value = "Area excluded. Re-scan this sheet to apply it."
                    }
                }
            }
        }
    }

    fun clearExcludedRegion() {
        viewModelScope.launch {
            repository.setExcludedRegion(sheetId, null)
            _message.value = "Exclusion cleared."
        }
    }

    /** A shareable destination for the camera app to write into. */
    fun photoTarget(reference: String): Uri = Exporter.newPhotoTarget(context, reference).second

    private companion object {
        const val PREVIEW_PIXELS = 2_600_000
    }
}

@Composable
fun DrawingScreen(
    container: CascoScanApp.Container,
    projectId: String,
    sheetId: String,
    onBack: () -> Unit,
    onScanOnSite: () -> Unit = {},
) {
    val viewModel = screenViewModel(key = sheetId) {
        DrawingViewModel(container.context, projectId, sheetId, container.repository, container.detectionConfig)
    }
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val all by viewModel.all.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val tapMode by viewModel.tapMode.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingCorner by viewModel.pendingCorner.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val visible = viewModel.visible(all)
    val selected = all.firstOrNull { it.id == selectedId }
    val summary = Audit.summarise(all, viewModel.acceptThreshold)
    var manualAt by remember { mutableStateOf<Pt?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sheet?.name ?: "Sheet", maxLines = 1)
                        Text(
                            "${summary.total} found · ${summary.inspected} inspected · ${summary.missing} missing",
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
                    IconButton(onClick = onScanOnSite) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "Inspect this area on site with the camera",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.setTapMode(
                                if (tapMode == DrawingViewModel.TapMode.ADD_PENETRATION) {
                                    DrawingViewModel.TapMode.INSPECT
                                } else {
                                    DrawingViewModel.TapMode.ADD_PENETRATION
                                },
                            )
                        },
                    ) {
                        Icon(
                            Icons.Filled.AddCircleOutline,
                            contentDescription = "Add a penetration by tapping",
                            tint = if (tapMode == DrawingViewModel.TapMode.ADD_PENETRATION) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.setTapMode(
                                if (tapMode == DrawingViewModel.TapMode.EXCLUDE_REGION) {
                                    DrawingViewModel.TapMode.INSPECT
                                } else {
                                    DrawingViewModel.TapMode.EXCLUDE_REGION
                                },
                            )
                        },
                    ) {
                        Icon(
                            Icons.Filled.CropFree,
                            contentDescription = "Exclude an area such as the title block",
                            tint = if (tapMode == DrawingViewModel.TapMode.EXCLUDE_REGION) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == DrawingViewModel.Filter.ALL,
                    onClick = { viewModel.setFilter(DrawingViewModel.Filter.ALL) },
                    label = { Text("All ${all.size}") },
                )
                FilterChip(
                    selected = filter == DrawingViewModel.Filter.TO_INSPECT,
                    onClick = { viewModel.setFilter(DrawingViewModel.Filter.TO_INSPECT) },
                    label = { Text("To inspect ${summary.count(AuditStatus.PENDING)}") },
                )
                FilterChip(
                    selected = filter == DrawingViewModel.Filter.TO_CHECK,
                    onClick = { viewModel.setFilter(DrawingViewModel.Filter.TO_CHECK) },
                    label = { Text("Unsure ${summary.needingReview}") },
                )
                FilterChip(
                    selected = filter == DrawingViewModel.Filter.FINDINGS,
                    onClick = { viewModel.setFilter(DrawingViewModel.Filter.FINDINGS) },
                    label = { Text("Findings ${summary.findings}") },
                )
            }

            when (tapMode) {
                DrawingViewModel.TapMode.ADD_PENETRATION -> Hint("Tap where the penetration should be.")
                DrawingViewModel.TapMode.EXCLUDE_REGION -> Hint(
                    if (pendingCorner == null) {
                        "Tap one corner of the area to ignore (title block, legend)."
                    } else {
                        "Now tap the opposite corner."
                    },
                )
                DrawingViewModel.TapMode.INSPECT -> Unit
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                SheetViewer(
                    bitmap = preview,
                    pageWidthPx = sheet?.widthPx ?: 1,
                    penetrations = visible,
                    acceptThreshold = viewModel.acceptThreshold,
                    selectedId = selectedId,
                    excludedRegion = sheet?.let { Mappers.excludedRegionOf(it) },
                    controlPoints = listOfNotNull(pendingCorner),
                    onSelect = viewModel::select,
                    onTapBlank = { point ->
                        if (tapMode == DrawingViewModel.TapMode.ADD_PENETRATION) {
                            manualAt = point
                        } else {
                            viewModel.onTapBlank(point)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            sheet?.let { current ->
                if (Mappers.excludedRegionOf(current) != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "An area of this sheet is excluded from scanning.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::clearExcludedRegion) { Text("Clear") }
                    }
                }
            }
        }
    }

    selected?.let { penetration ->
        InspectSheet(
            penetration = penetration,
            acceptThreshold = viewModel.acceptThreshold,
            onDismiss = { viewModel.select(null) },
            onStatus = { status, note -> viewModel.setStatus(penetration, status, note) },
            onReject = { viewModel.rejectDetection(penetration) },
            onPhoto = { uri -> viewModel.addPhoto(penetration, uri) },
            photoTarget = { viewModel.photoTarget(penetration.id) },
        )
    }

    manualAt?.let { point ->
        AddPenetrationDialog(
            onDismiss = { manualAt = null },
            onConfirm = { diameter, kind, note ->
                viewModel.addManual(point, diameter, kind, note)
                manualAt = null
            },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The inspection panel: everything known about one penetration, and every verdict an auditor needs.
 *
 * A bottom sheet rather than a dialog because this is used one-handed, on a ladder, with the drawing
 * still visible behind it.
 */
@Composable
private fun InspectSheet(
    penetration: Penetration,
    acceptThreshold: Double,
    onDismiss: () -> Unit,
    onStatus: (AuditStatus, String?) -> Unit,
    onReject: () -> Unit,
    onPhoto: (Uri) -> Unit,
    photoTarget: () -> Uri,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember(penetration.id) { mutableStateOf(penetration.note.orEmpty()) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        pendingPhoto?.let { if (success) onPhoto(it) }
        pendingPhoto = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(penetration.id, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "  ${penetration.displaySize}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f))
                StatusChip(penetration.status, penetration.needsReview(acceptThreshold))
            }

            Text(
                buildString {
                    append(penetration.kind.name.lowercase().replace('_', ' '))
                    penetration.label?.raw?.let { append(" · labelled \"").append(it).append('"') }
                    if (penetration.origin == Origin.MANUAL) append(" · added by hand")
                    if (penetration.origin == Origin.RECONCILED) {
                        penetration.requiredByPageIndex?.let { append(" · required on sheet ${it + 1}") }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            penetration.note?.takeIf { it.isNotBlank() && penetration.origin == Origin.RECONCILED }?.let {
                Card { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }

            Text("On site this penetration is:", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (status in listOf(
                    AuditStatus.PRESENT,
                    AuditStatus.MISSING,
                    AuditStatus.WRONG_SIZE,
                    AuditStatus.WRONG_POSITION,
                    AuditStatus.OBSTRUCTED,
                    AuditStatus.NOT_SEALED,
                    AuditStatus.NOT_APPLICABLE,
                )) {
                    FilterChip(
                        selected = penetration.status == status,
                        onClick = { onStatus(status, note.ifBlank { null }) },
                        label = { Text(statusLabel(status)) },
                    )
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStatus(penetration.status, note.ifBlank { null }) }) { Text("Save note") }
                OutlinedButton(
                    onClick = {
                        val target = photoTarget()
                        pendingPhoto = target
                        camera.launch(target)
                    },
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Text("  Photo")
                }
            }

            if (penetration.photoUris.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    penetration.photoUris.forEachIndexed { index, _ ->
                        AssistChip(onClick = {}, label = { Text("Photo ${index + 1}") })
                    }
                }
            }

            if (penetration.origin == Origin.DETECTED || penetration.origin == Origin.DETECTED_CONFIRMED) {
                EvidenceBreakdown(penetration, Modifier.fillMaxWidth())
            }

            if (penetration.origin == Origin.DETECTED && penetration.signature != null) {
                TextButton(onClick = onReject) { Text("Not a penetration - remove and learn") }
            }
        }
    }
}

@Composable
private fun AddPenetrationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double?, SymbolKind, String?) -> Unit,
) {
    var diameter by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        diameter.toDoubleOrNull(),
                        if (round) SymbolKind.CIRCLE_OUTLINE else SymbolKind.RECT_OUTLINE,
                        note.ifBlank { null },
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add a penetration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "For an opening the scan missed, or one that exists on site but not on the drawing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = round, onClick = { round = true }, label = { Text("Round") })
                    FilterChip(selected = !round, onClick = { round = false }, label = { Text("Rectangular") })
                }
                OutlinedTextField(
                    value = diameter,
                    onValueChange = { diameter = it.filter(Char::isDigit) },
                    label = { Text(if (round) "Diameter (mm)" else "Size (mm)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    singleLine = true,
                )
            }
        },
    )
}
