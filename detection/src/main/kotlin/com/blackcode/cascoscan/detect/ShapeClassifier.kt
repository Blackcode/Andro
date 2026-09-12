package com.blackcode.cascoscan.detect

import kotlin.math.max

/**
 * Decides what a blob *looks like*, with no notion of whether it is a penetration - that judgement
 * belongs to [Scoring], which also gets to weigh size, labels and context.
 *
 * Splitting the two matters: the same ring is a sleeve on a floor plan and a column marker on a
 * grid plan, and only the surrounding evidence tells them apart.
 */
object ShapeClassifier {

    data class Classification(val kind: SymbolKind, val confidence: Double, val roundScore: Double, val rectScore: Double)

    /**
     * How round the enclosed region is. Measured on the filled region, so an outline ring and a
     * solid disc score the same:
     *  - a disc inscribed in its bbox fills pi/4 = 0.785 of it, a rectangle fills ~1.0
     *  - the centre-to-boundary radius of a circle barely varies (cv ~ 0.03); for a square the
     *    corners push cv to ~0.105
     *  - circles are square in the bbox sense; ellipses drawn as sleeves still are, near enough
     */
    fun roundScore(f: ShapeFeatures): Double {
        val fill = Fuzzy.bell(f.boxFill, center = 0.785, halfWidth = 0.16)
        val radial = Fuzzy.falling(f.radialCv, good = 0.045, bad = 0.115)
        val aspect = Fuzzy.falling(1.0 - f.box.squareness, good = 0.10, bad = 0.30)
        val symmetry = Fuzzy.rising(f.symmetryScore, bad = 0.60, good = 0.90)
        return Fuzzy.clamp01(0.38 * fill + 0.30 * radial + 0.17 * aspect + 0.15 * symmetry)
    }

    /**
     * How rectangular the enclosed region is: it fills its bounding box, and the radius to the
     * boundary varies in the way corners make it vary. Elongated rectangles are allowed - a slot
     * for a cable tray is a legitimate penetration.
     */
    fun rectScore(f: ShapeFeatures): Double {
        val fill = Fuzzy.rising(f.boxFill, bad = 0.74, good = 0.93)
        val corners = Fuzzy.bell(f.radialCv, center = 0.115, halfWidth = 0.11)
        val symmetry = Fuzzy.rising(f.symmetryScore, bad = 0.55, good = 0.88)
        return Fuzzy.clamp01(0.50 * fill + 0.26 * corners + 0.24 * symmetry)
    }

    fun classify(f: ShapeFeatures, config: DetectionConfig = DetectionConfig()): Classification {
        val round = roundScore(f)
        val rect = rectScore(f)
        val best = max(round, rect)

        // Nothing coherent enough to name. Still returned (not dropped) so that a label sitting next
        // to it can rescue it later; scoring will demote it heavily.
        if (best < 0.45 || f.box.longSide < config.minBlobPx) {
            return Classification(SymbolKind.UNKNOWN, best, round, rect)
        }

        val isRound = round >= rect
        val interior = interiorStyle(f)
        val kind = when {
            isRound && interior == Interior.CROSSED -> SymbolKind.CIRCLE_CROSSED
            isRound && interior == Interior.HATCHED -> SymbolKind.CIRCLE_HATCHED
            isRound && interior == Interior.SOLID -> SymbolKind.CIRCLE_FILLED
            isRound -> SymbolKind.CIRCLE_OUTLINE
            interior == Interior.CROSSED -> SymbolKind.RECT_CROSSED
            interior == Interior.HATCHED -> SymbolKind.RECT_HATCHED
            interior == Interior.SOLID -> SymbolKind.RECT_FILLED
            else -> SymbolKind.RECT_OUTLINE
        }

        // A crossed or hatched interior is itself corroboration that this is a symbol and not an
        // incidental box, so it lifts the shape confidence a little.
        val interiorBonus = when (interior) {
            Interior.CROSSED -> 0.10
            Interior.HATCHED -> 0.06
            else -> 0.0
        }
        return Classification(kind, Fuzzy.clamp01(best + interiorBonus), round, rect)
    }

    private enum class Interior { SOLID, HATCHED, CROSSED, HOLLOW }

    /**
     * What is inside the outline.
     *
     * Hatching is tested first, and excludes a cross verdict. Diagonal hatching at 45 degrees lies
     * along the bounding box diagonal, so it saturates the cross test completely - dense hatching
     * reads as a perfect X. Nothing goes the other way: a two-stroke cross gives a scanline four ink
     * runs whose spacing shifts from line to line, which is exactly what the hatch score measures the
     * absence of. So hatching can veto a cross, and a cross cannot be mistaken for hatching.
     *
     * Both are tested before "solid", because a heavy interior mark on a small symbol can push the
     * ink ratio up near a solid fill.
     */
    private fun interiorStyle(f: ShapeFeatures): Interior {
        val hasOutline = f.inkRatio < 0.88
        if (!hasOutline) return Interior.SOLID
        if (f.hatchScore >= HATCH_THRESHOLD) return Interior.HATCHED
        val crossed = f.diagonalCoverageMin >= 0.72 || f.axialCoverage >= 0.80
        if (crossed) return Interior.CROSSED
        return Interior.HOLLOW
    }

    /** Hatch score above which a filled interior is read as hatching rather than as a cross. */
    const val HATCH_THRESHOLD = 0.45
}
