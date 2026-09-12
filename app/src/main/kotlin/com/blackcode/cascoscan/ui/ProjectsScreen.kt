@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.blackcode.cascoscan.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackcode.cascoscan.CascoScanApp
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.ProjectEntity
import com.blackcode.cascoscan.detect.RenderPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(private val repository: AuditRepository) : ViewModel() {

    val projects: StateFlow<List<ProjectEntity>> =
        repository.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _openProject = MutableStateFlow<String?>(null)
    val openProject: StateFlow<String?> = _openProject

    fun consumeOpen() {
        _openProject.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    fun import(uri: Uri, name: String, ratioDenominator: Double?) {
        viewModelScope.launch {
            runCatching { repository.importDocument(uri, name, ratioDenominator) }
                .onSuccess { _openProject.value = it }
                .onFailure {
                    _error.value = "Could not read that PDF: ${it.message ?: "unsupported or password protected"}"
                }
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch { repository.deleteProject(projectId) }
    }
}

@Composable
fun ProjectsScreen(
    container: CascoScanApp.Container,
    incomingDocument: Uri?,
    onOpenProject: (String) -> Unit,
) {
    val viewModel = screenViewModel { ProjectsViewModel(container.repository) }
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val openProject by viewModel.openProject.collectAsStateWithLifecycle()

    var pending by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = uri
    }

    // A set opened from a mail attachment goes straight into the same import dialog.
    LaunchedEffect(incomingDocument) {
        if (incomingDocument != null) pending = incomingDocument
    }
    LaunchedEffect(openProject) {
        openProject?.let {
            onOpenProject(it)
            viewModel.consumeOpen()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Penetration audits") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add drawing set") },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No drawing sets yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add the casco drawing PDF for a project. Every sheet is scanned for sleeves, " +
                        "cores and openings, and you check them off as you walk the building.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    Card(onClick = { onOpenProject(project.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append("${project.pageCount} sheet${if (project.pageCount == 1) "" else "s"}")
                                    project.ratioDenominator?.let { append(" · 1:${it.toInt()}") }
                                        ?: append(" · scale not set")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    pending?.let { uri ->
        ImportDialog(
            onDismiss = { pending = null },
            onConfirm = { name, ratio ->
                viewModel.import(uri, name, ratio)
                pending = null
            },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
            title = { Text("Import failed") },
            text = { Text(message) },
        )
    }
}

/**
 * Asks for the two things that cannot be guessed: what to call the job, and the plot scale.
 *
 * The scale is not a nicety. It is what turns a 17-pixel ring into "a 110 mm sleeve", and without it
 * the detector has to fall back on shape alone and will report more rubbish. The dialog therefore
 * spells out what each choice means for the smallest opening that can still be found.
 */
@Composable
private fun ImportDialog(onDismiss: () -> Unit, onConfirm: (String, Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf<Double?>(50.0) }
    val options = listOf(20.0, 50.0, 100.0, 200.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { "Untitled project" }, ratio) },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New drawing set") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project or building") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Plot scale", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        FilterChip(
                            selected = ratio == option,
                            onClick = { ratio = option },
                            label = { Text("1:${option.toInt()}") },
                        )
                    }
                }
                FilterChip(
                    selected = ratio == null,
                    onClick = { ratio = null },
                    label = { Text("I don't know yet") },
                )
                val explanation = ratio?.let { denominator ->
                    val plan = RenderPlan.forRatio(denominator)
                    buildString {
                        append("Sheets will be rendered at ${plan.dpi.toInt()} dpi. ")
                        append("Openings from about ${plan.smallestReliableMm.toInt()} mm upwards can be found")
                        if (plan.resolutionLimited) append("; anything smaller will be missed at this scale")
                        append(".")
                    }
                } ?: "Without a scale the detector cannot judge whether a shape is the right size to be " +
                    "an opening, so expect more false alarms. You can set it later."
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
