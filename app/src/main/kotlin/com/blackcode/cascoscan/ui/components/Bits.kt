package com.blackcode.cascoscan.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.report.OverlayRenderer

/** Status as a small coloured pill, with the wording an inspector would use out loud. */
@Composable
fun StatusChip(status: AuditStatus, needsReview: Boolean = false, modifier: Modifier = Modifier) {
    val colour = Color(OverlayRenderer.Palette.forStatus(status, needsReview))
    Surface(
        modifier = modifier,
        color = colour.copy(alpha = 0.14f),
        contentColor = colour,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = statusLabel(status, needsReview),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

fun statusLabel(status: AuditStatus, needsReview: Boolean = false): String = when {
    needsReview && status == AuditStatus.PENDING -> "Check this"
    status == AuditStatus.PENDING -> "To inspect"
    status == AuditStatus.PRESENT -> "Present"
    status == AuditStatus.MISSING -> "Missing"
    status == AuditStatus.WRONG_SIZE -> "Wrong size"
    status == AuditStatus.WRONG_POSITION -> "Wrong position"
    status == AuditStatus.OBSTRUCTED -> "Obstructed"
    status == AuditStatus.NOT_SEALED -> "Not sealed"
    else -> "N/A"
}

/** A labelled count, used for the progress strips at the top of the sheet and report screens. */
@Composable
fun CountTile(value: String, label: String, accent: Color? = null, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = accent?.copy(alpha = 0.12f) ?: MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Why the detector thinks this is a penetration.
 *
 * Shown on every machine-made row. An auditor signs the report, so they are entitled to see the
 * reasoning - and in practice this is also what tells them the scale is wrong, or the title block
 * needs excluding.
 */
@Composable
fun EvidenceBreakdown(penetration: Penetration, modifier: Modifier = Modifier) {
    val terms = penetration.evidence.ranked()
    if (terms.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Why: %.0f%% confident".format(penetration.confidence * 100),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        val strongest = terms.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(0.01)
        for ((name, value) in terms) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    readableTerm(name),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                LinearProgressIndicator(
                    progress = { (kotlin.math.abs(value) / strongest).toFloat().coerceIn(0f, 1f) },
                    color = if (value >= 0) {
                        Color(OverlayRenderer.Palette.PRESENT)
                    } else {
                        Color(OverlayRenderer.Palette.MISSING)
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    "%+.2f".format(value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Turns an evidence key into something an inspector reads rather than decodes. */
fun readableTerm(name: String): String = when {
    name.startsWith("shape:") -> "Drawn as a " + name.removePrefix("shape:").replace('_', ' ')
    name.startsWith("context:") -> when (name.removePrefix("context:")) {
        "in_structure" -> "Sits inside a wall or slab"
        "void_in_structure" -> "An opening enclosed by structure"
        else -> "Position in the drawing"
    }
    name == "size" -> "Plausible real-world size"
    name == "label" -> "Labelled nearby"
    name == "sizeMatch" -> "Size matches its label"
    name == "onStructure" -> "On a structural line"
    name == "repeats" -> "Symbol repeats on this sheet"
    name == "learned" -> "Matches your earlier decisions"
    name == "annotation" -> "Looks like drawing text"
    name == "slender" -> "Too slender to be an opening"
    name == "sprawl" -> "Encloses nothing"
    name == "excluded" -> "Inside an excluded region"
    else -> name
}
