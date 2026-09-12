package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Remembers the symbols an auditor has confirmed or rejected on *this* drawing set, and uses them to
 * judge later pages.
 *
 * Drawing sets are internally consistent: whatever a sleeve looks like on sheet 3 it looks like on
 * sheet 12, and whatever the false positive was (a column bubble, a socket symbol, a grid dot) it
 * repeats too. So one or two corrections per set are worth more than any amount of tuning, and they
 * are cheap to collect because the auditor is reviewing the page anyway.
 *
 * Deliberately not a classifier that needs training: it is a nearest-prototype vote, so a single
 * example already helps and nothing has to be retrained.
 */
class PrototypeLibrary(
    private val accepted: MutableList<Signature> = mutableListOf(),
    private val rejected: MutableList<Signature> = mutableListOf(),
) {

    /**
     * Scale-free description of a symbol. Size enters as a log so that a 110 sleeve and a 125 sleeve
     * sit next to each other while a 1200 shaft does not.
     */
    data class Signature(
        val kind: SymbolKind,
        val boxFill: Double,
        val radialCv: Double,
        val inkRatio: Double,
        val hatchScore: Double,
        val diagonal: Double,
        val squareness: Double,
        val logSizeMm: Double?,
    ) {
        companion object {
            fun of(f: ShapeFeatures, kind: SymbolKind, sizeMm: Double?): Signature = Signature(
                kind = kind,
                boxFill = f.boxFill,
                radialCv = f.radialCv,
                inkRatio = f.inkRatio,
                hatchScore = f.hatchScore,
                diagonal = f.diagonalCoverageMax,
                squareness = f.box.squareness,
                logSizeMm = sizeMm?.takeIf { it > 1.0 }?.let { ln(it) },
            )
        }
    }

    val acceptedCount: Int get() = accepted.size
    val rejectedCount: Int get() = rejected.size
    val isEmpty: Boolean get() = accepted.isEmpty() && rejected.isEmpty()

    fun remember(signature: Signature, wasPenetration: Boolean) {
        val into = if (wasPenetration) accepted else rejected
        // Cap the library: prototypes are near-duplicates after a while and scoring is linear in size.
        if (into.size >= MAX_PER_CLASS) into.removeAt(0)
        into += signature
    }

    fun remember(features: ShapeFeatures, kind: SymbolKind, sizeMm: Double?, wasPenetration: Boolean) =
        remember(Signature.of(features, kind, sizeMm), wasPenetration)

    fun snapshot(): Pair<List<Signature>, List<Signature>> = accepted.toList() to rejected.toList()

    /**
     * Evidence in -1..1: how much more this shape resembles a confirmed penetration than a rejected
     * one. Returns 0.0 while the library is empty, so an un-reviewed project is simply unaffected.
     */
    fun score(features: ShapeFeatures, kind: SymbolKind, sizeMm: Double?): Double {
        if (isEmpty) return 0.0
        val query = Signature.of(features, kind, sizeMm)
        val best = accepted.maxOfOrNull { similarity(query, it) } ?: 0.0
        val worst = rejected.maxOfOrNull { similarity(query, it) } ?: 0.0
        // A near-exact match to a rejected prototype must be able to veto, hence the full difference.
        return (best - worst).coerceIn(-1.0, 1.0)
    }

    private fun similarity(a: Signature, b: Signature): Double {
        var d = 0.0
        d += sq((a.boxFill - b.boxFill) / 0.12)
        d += sq((a.radialCv - b.radialCv) / 0.06)
        d += sq((a.inkRatio - b.inkRatio) / 0.20)
        d += sq((a.hatchScore - b.hatchScore) / 0.30)
        d += sq((a.diagonal - b.diagonal) / 0.30)
        d += sq((a.squareness - b.squareness) / 0.20)
        if (a.logSizeMm != null && b.logSizeMm != null) {
            d += sq((a.logSizeMm - b.logSizeMm) / 0.35)
        }
        // Different drawn forms are different symbols; make that expensive but not absolute, since
        // the classifier itself can flip between, say, CIRCLE_OUTLINE and CIRCLE_CROSSED on faint ink.
        if (a.kind != b.kind) {
            d += if (a.kind.isRound == b.kind.isRound) 2.0 else 6.0
        }
        return exp(-d / max(1.0, WEIGHT_COUNT))
    }

    private fun sq(v: Double) = v * v

    companion object {
        private const val MAX_PER_CLASS = 64
        private const val WEIGHT_COUNT = 7.0

        /** Rebuilds a library from persisted signatures (the app stores these per project). */
        fun from(accepted: List<Signature>, rejected: List<Signature>) =
            PrototypeLibrary(accepted.toMutableList(), rejected.toMutableList())
    }
}

/** Agreement between the size measured off the drawing and the size written next to it, in -1..1. */
internal fun sizeAgreement(measuredMm: Double, declaredMm: Double): Double {
    if (measuredMm <= 0.0 || declaredMm <= 0.0) return 0.0
    val relative = abs(measuredMm - declaredMm) / declaredMm
    // Within 20% is a match (draughting tolerance plus our own pixel error); beyond 60% it argues
    // that the label belongs to something else entirely.
    return when {
        relative <= 0.20 -> 1.0
        relative >= 0.60 -> -1.0
        else -> 1.0 - 2.0 * (relative - 0.20) / 0.40
    }
}
