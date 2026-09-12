package com.blackcode.cascoscan.detect

import kotlin.math.max

/**
 * The drawn appearance of a candidate. Casco drawings do not agree on one symbol, so the detector
 * classifies the *form* and lets [Scoring] decide how much each form is worth as evidence.
 */
enum class SymbolKind {
    /** Thin ring, nothing inside: a pipe sleeve seen in plan. */
    CIRCLE_OUTLINE,

    /** Solid disc: often a small sleeve, but also a bullet or a node marker -> weaker evidence. */
    CIRCLE_FILLED,

    /** Ring filled with parallel hatching: a round recess to be formed. */
    CIRCLE_HATCHED,

    /** Ring with a cross through it: the classic "sparing"/penetration symbol. */
    CIRCLE_CROSSED,

    /** Empty rectangle: a rectangular opening, but also any box in the drawing -> weak on its own. */
    RECT_OUTLINE,

    /** Solid rectangle. */
    RECT_FILLED,

    /** Rectangle filled with parallel hatching: a recess or opening to be formed. */
    RECT_HATCHED,

    /** Rectangle with one or both diagonals: very strong penetration symbol. */
    RECT_CROSSED,

    /** Recognisable blob that matches none of the above. */
    UNKNOWN,
    ;

    val isRound: Boolean
        get() = this == CIRCLE_OUTLINE || this == CIRCLE_FILLED ||
            this == CIRCLE_HATCHED || this == CIRCLE_CROSSED

    val isCrossed: Boolean get() = this == CIRCLE_CROSSED || this == RECT_CROSSED
    val isHatched: Boolean get() = this == CIRCLE_HATCHED || this == RECT_HATCHED
}

/** What the auditor found on site for a given penetration. */
enum class AuditStatus {
    /** Not inspected yet. Everything starts here; the report calls these out as outstanding. */
    PENDING,
    PRESENT,

    /** Required by the drawing, absent in the building: the finding this app exists to produce. */
    MISSING,
    WRONG_SIZE,
    WRONG_POSITION,

    /** Present but unusable: blocked by rebar, a beam, a duct. */
    OBSTRUCTED,

    /** Present, correct, but the fire/acoustic seal is absent (relevant on later QC rounds). */
    NOT_SEALED,

    /** Exists in the drawing but does not apply to this scope, signed off by the auditor. */
    NOT_APPLICABLE,
    ;

    val isFinding: Boolean
        get() = this == MISSING || this == WRONG_SIZE || this == WRONG_POSITION ||
            this == OBSTRUCTED || this == NOT_SEALED

    val isClosed: Boolean get() = this != PENDING
}

/** Where a penetration record came from. Hand-added records must never be silently overwritten. */
enum class Origin { DETECTED, DETECTED_CONFIRMED, MANUAL, RECONCILED }

/**
 * Per-term evidence behind a confidence value. Kept on the record so the review screen can answer
 * "why did you flag this?" - in a QC audit an unexplainable machine verdict is worthless.
 *
 * Each term is a signed contribution already multiplied by its weight.
 */
data class Evidence(val terms: Map<String, Double>, val bias: Double, val logit: Double, val confidence: Double) {

    /** Terms ordered by how much they moved the decision, for display. */
    fun ranked(): List<Pair<String, Double>> = terms.entries
        .sortedByDescending { kotlin.math.abs(it.value) }
        .map { it.key to it.value }

    fun summary(limit: Int = 3): String = ranked().take(limit)
        .joinToString(", ") { (k, v) -> "$k ${if (v >= 0) "+" else ""}${"%.2f".format(v)}" }

    companion object {
        val EMPTY = Evidence(emptyMap(), 0.0, 0.0, 0.5)
    }
}

/** Geometric + photometric description of one ink blob, produced by [ComponentFeatures]. */
data class ShapeFeatures(
    val box: IBox,
    val inkArea: Int,
    /** Area of the blob once its interior holes are filled: the region the symbol encloses. */
    val filledArea: Int,
    val holeArea: Int,
    val holeCount: Int,
    val outerPerimeter: Int,
    /** inkArea / filledArea: ~1 solid, ~0.1 thin outline, in between hatched or crossed. */
    val inkRatio: Double,
    /** filledArea / box.area: ~0.785 for a circle, ~1.0 for an axis-aligned rectangle. */
    val boxFill: Double,
    /** 4*pi*filledArea / outerPerimeter^2: ~1 for a disc. */
    val circularity: Double,
    /** Coefficient of variation of the centre-to-boundary radius: ~0.03 circle, ~0.11 square. */
    val radialCv: Double,
    val symmetryScore: Double,
    /** Ink coverage of the *weaker* bbox diagonal, ignoring the border band: an X needs both. */
    val diagonalCoverageMin: Double,
    /** Ink coverage of the stronger bbox diagonal: some drawings strike a single diagonal only. */
    val diagonalCoverageMax: Double,
    /** Ink coverage of the weaker of the centre row / centre column: a crosshair needs both. */
    val axialCoverage: Double,
    /** Regularity of alternating ink/gap runs inside the shape: high for hatching. */
    val hatchScore: Double,
) {
    val centroid: Pt get() = box.center
}

/**
 * Where in the page structure a candidate was found.
 *
 * This matters more than it looks. A sleeve drawn across a wall touches the wall lines, so it is not
 * a free-standing blob at all - it only becomes visible once the long structural runs are stripped
 * out, or it shows up as a void enclosed by the wall. Both of those are *stronger* evidence than a
 * shape floating in white space, which could be anything.
 */
enum class CandidateSource {
    /** An isolated blob in open space: a symbol on a slab, or any unrelated bit of drawing. */
    FREE_STANDING,

    /** Recovered from inside a structural element after its long lines were removed. */
    IN_STRUCTURE,

    /** A closed void enclosed by structure: how larger rectangular openings are usually drawn. */
    VOID_IN_STRUCTURE,
}

/** A detected shape before scoring: geometry plus whatever label was found next to it. */
data class Candidate(
    val features: ShapeFeatures,
    val kind: SymbolKind,
    val shapeConfidence: Double,
    val label: ParsedLabel? = null,
    /** Distance in px from the candidate to the label box that was matched, if any. */
    val labelDistancePx: Double? = null,
    /** Components that were merged into this candidate (e.g. a ring and the cross inside it). */
    val mergedBoxes: List<IBox> = emptyList(),
    val source: CandidateSource = CandidateSource.FREE_STANDING,
    val nearWall: Boolean = false,
    val wallDistancePx: Double = Double.MAX_VALUE,
    /** How many other candidates on the page share this kind and size class. */
    val repetitionCount: Int = 1,
    val inExcludedRegion: Boolean = false,
    /** Sits inside a text box reported by the PDF text layer or by OCR: it is a character. */
    val inTextBox: Boolean = false,
    /** Sits in a row of aligned, similarly sized, closely spaced blobs: it is a character. */
    val inTextRun: Boolean = false,
) {
    val box: IBox get() = features.box
    val center: Pt get() = features.box.center

    /** Nominal drawn size in px: the diameter for round symbols, the long side otherwise. */
    val sizePx: Double
        get() = if (kind.isRound) (box.width + box.height) / 2.0 else box.longSide.toDouble()
}

/**
 * A penetration as the app tracks it: one row in the audit checklist, one marker on the drawing.
 *
 * [sizeMm] is the *measured* size (from the drawing geometry and scale); [ParsedLabel.diameterMm]
 * is the *declared* size. They are deliberately kept apart so a mismatch can be reported.
 */
data class Penetration(
    val id: String,
    val pageIndex: Int,
    val center: Pt,
    val box: IBox,
    val kind: SymbolKind,
    val confidence: Double,
    val evidence: Evidence = Evidence.EMPTY,
    val sizeMm: SizeMm? = null,
    val label: ParsedLabel? = null,
    val origin: Origin = Origin.DETECTED,
    val status: AuditStatus = AuditStatus.PENDING,
    val note: String? = null,
    val photoUris: List<String> = emptyList(),
    /** Set when this record exists because another drawing required it - see [Reconciler]. */
    val requiredByPageIndex: Int? = null,
    /**
     * The shape measurements that produced this record, in the compact form [PrototypeLibrary] uses.
     *
     * Carried on the record, and stored with it, so that confirming or rejecting a penetration weeks
     * later still teaches the project. Without it a correction could only be remembered during the
     * session that made the detection, which is not how an audit is actually carried out.
     */
    val signature: PrototypeLibrary.Signature? = null,
) {
    /**
     * Still waiting for a human: the machine was unsure and nobody has looked yet.
     *
     * All three conditions matter. Once someone records a status the row has *been* reviewed, however
     * low the original confidence was, and a confident detection needs no second opinion.
     */
    fun needsReview(acceptThreshold: Double) =
        origin == Origin.DETECTED && status == AuditStatus.PENDING && confidence < acceptThreshold

    val displaySize: String
        get() = label?.sizeText ?: sizeMm?.let { it.describe(kind.isRound) } ?: "?"
}

/** Real-world size of a penetration in millimetres. */
data class SizeMm(val width: Double, val height: Double) {
    val diameter: Double get() = (width + height) / 2.0
    val long: Double get() = max(width, height)

    fun describe(round: Boolean): String =
        if (round) "Ø${diameter.toInt()}" else "${width.toInt()}x${height.toInt()}"
}

/** Tunables. Defaults are aimed at A1/A0 casco floor and wall plans rendered at 200-300 dpi. */
data class DetectionConfig(
    /** Ink blobs smaller than this in px are noise or text fragments. */
    val minBlobPx: Int = 6,
    /** Ink blobs larger than this fraction of the page's short side are walls, frames, title blocks. */
    val maxBlobFractionOfPage: Double = 0.25,
    /** Plausible real-world penetration sizes. Outside the absolute bounds a candidate is dropped. */
    val absoluteMinMm: Double = 30.0,
    val softMinMm: Double = 70.0,
    val softMaxMm: Double = 1200.0,
    val absoluteMaxMm: Double = 3000.0,
    /** A label further away than this multiple of the symbol size is not considered its label. */
    val labelSearchFactor: Double = 3.0,
    val labelSearchMinPx: Double = 40.0,
    /** Ink runs at least this long (px) are structural lines, not symbols. */
    val wallRunPx: Int = 60,
    /**
     * How far apart two ink fragments may be and still be treated as one symbol. Sized for the gap a
     * removed structural line leaves behind, which is that line's own width.
     */
    val bridgeRadius: Int = 2,
    /**
     * How wide a break in an outline may be before the interior is considered open. Should match
     * [bridgeRadius]: a fragment that was bridged must also measure as closed.
     */
    val sealRadius: Int = 2,
    /** How close (px) a symbol must be to a structural line to count as sitting in a wall. */
    val wallProximityPx: Int = 12,
    /** Records at or above this confidence are accepted; below it they are queued for review. */
    val acceptThreshold: Double = 0.55,
    /** Below this a candidate is discarded outright. */
    val reviewThreshold: Double = 0.28,
    val scoring: ScoringWeights = ScoringWeights(),
    /** Page regions to ignore, in page-pixel space: title blocks, legends, revision tables. */
    val excludedRegions: List<IBox> = emptyList(),
) {
    fun sizeIsPossible(mm: Double) = mm in absoluteMinMm..absoluteMaxMm
}
