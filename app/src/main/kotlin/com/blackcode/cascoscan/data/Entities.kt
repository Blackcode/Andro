package com.blackcode.cascoscan.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The database schema.
 *
 * Flat columns throughout, and the few composite values (evidence terms, photo lists) are stored as
 * text encoded by [Codec]. An audit record has to survive app updates and be exportable years later,
 * so the storage format stays something a human can read out of a database browser.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** The drawing set. Persisted as a uri the app took durable read permission on. */
    val documentUri: String,
    val pageCount: Int,
    val createdAt: Long,
    /** Plot scale denominator, e.g. 50.0 for 1:50. Null until the user says. */
    val ratioDenominator: Double?,
    val renderDpi: Double,
    /** The sheet whose penetrations are the requirement, when the user has nominated one. */
    val referenceSheetId: String?,
    val notes: String?,
)

@Entity(
    tableName = "sheets",
    indices = [Index("projectId"), Index(value = ["projectId", "pageIndex"], unique = true)],
)
data class SheetEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val pageIndex: Int,
    val name: String,
    val sheetFormat: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Double,
    /** Null means the sheet has no scale yet; detection runs without size evidence. */
    val mmPerPx: Double?,
    val scaleSource: String?,
    val detectionState: String,
    val detectedAt: Long?,
    val diagnostics: String?,
    /** Title block / legend to ignore, in page pixels. All four null when nothing is excluded. */
    @ColumnInfo(name = "excl_left") val excludedLeft: Int?,
    @ColumnInfo(name = "excl_top") val excludedTop: Int?,
    @ColumnInfo(name = "excl_right") val excludedRight: Int?,
    @ColumnInfo(name = "excl_bottom") val excludedBottom: Int?,
) {
    enum class State { NOT_RUN, RUNNING, DONE, FAILED }
}

@Entity(
    tableName = "penetrations",
    indices = [Index("projectId"), Index("sheetId")],
)
data class PenetrationEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sheetId: String,
    val pageIndex: Int,
    /** Human-facing number such as "P3.14", stable for the life of the record. */
    val displayId: String,
    val centerX: Double,
    val centerY: Double,
    val boxLeft: Int,
    val boxTop: Int,
    val boxRight: Int,
    val boxBottom: Int,
    val kind: String,
    val confidence: Double,
    /** Evidence breakdown, encoded by [Codec.encodeTerms]; kept so the review screen can explain. */
    val evidence: String?,
    val evidenceLogit: Double,
    val sizeWidthMm: Double?,
    val sizeHeightMm: Double?,
    val labelRaw: String?,
    val labelDiameterMm: Double?,
    val labelWidthMm: Double?,
    val labelHeightMm: Double?,
    val labelKeyword: String?,
    val labelTag: String?,
    val origin: String,
    val status: String,
    val note: String?,
    val photoUris: String?,
    val requiredByPageIndex: Int?,
    /**
     * Shape measurements, so that confirming or rejecting this row still teaches the project weeks
     * later. Squareness and size are derivable from the other columns and are not duplicated here.
     */
    val shapeBoxFill: Double?,
    val shapeRadialCv: Double?,
    val shapeInkRatio: Double?,
    val shapeHatchScore: Double?,
    val shapeDiagonal: Double?,
    val updatedAt: Long,
)

/**
 * A shape the auditor has confirmed or rejected, kept per project so later sheets benefit.
 *
 * One correction on sheet 3 improves sheets 4 to 20, because a drawing set is internally consistent -
 * this is the cheapest accuracy the app can buy.
 */
@Entity(tableName = "prototypes", indices = [Index("projectId")])
data class PrototypeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val projectId: String,
    val wasPenetration: Boolean,
    val kind: String,
    val boxFill: Double,
    val radialCv: Double,
    val inkRatio: Double,
    val hatchScore: Double,
    val diagonal: Double,
    val squareness: Double,
    val logSizeMm: Double?,
)

/**
 * A saved sheet-to-sheet alignment, so a reconciliation can be reproduced or re-run.
 *
 * The sheet pair *is* the key: re-comparing the same two sheets must replace the stored transform,
 * not accumulate a row per attempt.
 */
@Entity(tableName = "alignments", primaryKeys = ["referenceSheetId", "targetSheetId"])
data class AlignmentEntity(
    val projectId: String,
    val referenceSheetId: String,
    val targetSheetId: String,
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
    /** How the transform was obtained: AUTO or CONTROL_POINTS. Findings are weighed accordingly. */
    val source: String,
    val inliers: Int,
    val total: Int,
    val createdAt: Long,
)

/** Text encoding for the few composite values, chosen so the database stays legible. */
object Codec {

    fun encodeList(values: List<String>): String? = values.takeIf { it.isNotEmpty() }?.joinToString("\n")

    fun decodeList(encoded: String?): List<String> =
        encoded?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** "shape:circle_crossed=2.00;size=1.20" - term names are identifiers, so no escaping is needed. */
    fun encodeTerms(terms: Map<String, Double>): String? = terms.takeIf { it.isNotEmpty() }
        ?.entries
        ?.joinToString(";") { (key, value) -> "${key.replace(';', ',').replace('=', '-')}=$value" }

    fun decodeTerms(encoded: String?): Map<String, Double> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (part in encoded.split(';')) {
            val index = part.lastIndexOf('=')
            if (index <= 0) continue
            val value = part.substring(index + 1).toDoubleOrNull() ?: continue
            out[part.substring(0, index)] = value
        }
        return out
    }
}
