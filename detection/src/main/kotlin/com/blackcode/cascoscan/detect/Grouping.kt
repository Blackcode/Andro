package com.blackcode.cascoscan.detect

/**
 * Reconciles the several blobs that one symbol can produce.
 *
 * A ring with a cross through it is two components when the cross does not touch the ring, and three
 * when the removal of a wall line snips the ring in half. Left alone that becomes three "findings"
 * for one sleeve, which is worse than missing it - an auditor stops trusting the list.
 */
object Grouping {

    /**
     * Merges interior detail into its enclosing symbol and drops near-duplicates.
     *
     * Containment, not overlap, is the test: the interior mark of a symbol sits inside the symbol's
     * box and is clearly smaller. Two boxes that merely overlap can be two real neighbouring sleeves.
     */
    fun merge(candidates: List<Candidate>): List<Candidate> {
        if (candidates.size < 2) return candidates

        // Largest first, so a container is always considered before the things inside it.
        val order = candidates.indices.sortedByDescending { candidates[it].box.area }
        val absorbed = BooleanArray(candidates.size)
        val out = ArrayList<Candidate>(candidates.size)

        for (oi in order.indices) {
            val i = order[oi]
            if (absorbed[i]) continue
            var host = candidates[i]
            val children = ArrayList<IBox>()
            var sawCross = false
            var sawHatch = false
            var sliverChildren = 0

            for (oj in (oi + 1) until order.size) {
                val j = order[oj]
                if (absorbed[j]) continue
                val child = candidates[j]
                if (!isInteriorDetail(host.box, child.box)) continue
                absorbed[j] = true
                children += child.box
                if (looksLikeCross(child.features)) sawCross = true
                if (child.features.hatchScore >= ShapeClassifier.HATCH_THRESHOLD) sawHatch = true
                if (child.box.squareness < 0.35) sliverChildren++
            }
            // Hatching clipped just inside its outline arrives as a handful of loose parallel strips
            // rather than as one hatched blob, and no single strip has a hatch score of its own.
            if (sliverChildren >= 3) sawHatch = true

            if (children.isNotEmpty()) {
                val upgraded = when {
                    sawCross && host.kind.isRound -> SymbolKind.CIRCLE_CROSSED
                    sawCross && !host.kind.isRound && host.kind != SymbolKind.UNKNOWN -> SymbolKind.RECT_CROSSED
                    sawHatch && host.kind == SymbolKind.CIRCLE_OUTLINE -> SymbolKind.CIRCLE_HATCHED
                    sawHatch && host.kind == SymbolKind.RECT_OUTLINE -> SymbolKind.RECT_HATCHED
                    else -> host.kind
                }
                host = host.copy(
                    kind = upgraded,
                    // A symbol that carries an interior mark is more certainly a symbol.
                    shapeConfidence = Fuzzy.clamp01(host.shapeConfidence + if (upgraded != host.kind) 0.10 else 0.04),
                    mergedBoxes = children,
                )
            }
            out += host
        }

        return dropDuplicates(out)
    }

    /** A child box counts as interior detail when it sits inside the host and is clearly smaller. */
    private fun isInteriorDetail(host: IBox, child: IBox): Boolean {
        if (child.longSide > host.longSide * 0.92) return false
        val grown = IBox(host.left - 1, host.top - 1, host.right + 1, host.bottom + 1)
        if (!grown.contains(child)) return false
        return child.center.let { it.x >= host.left && it.x <= host.right && it.y >= host.top && it.y <= host.bottom }
    }

    /**
     * An X or a crosshair: thin strokes (so the flood reaches between the arms, leaving ink ~= filled
     * area) that nevertheless span their whole bounding box symmetrically.
     */
    private fun looksLikeCross(f: ShapeFeatures): Boolean {
        val thin = f.inkRatio > 0.80 && f.boxFill < 0.62
        val spans = f.diagonalCoverageMin >= 0.65 || f.axialCoverage >= 0.75
        return thin && spans && f.symmetryScore > 0.55
    }

    /** Keeps the better-formed of two candidates covering essentially the same pixels. */
    private fun dropDuplicates(candidates: List<Candidate>, iouThreshold: Double = 0.62): List<Candidate> {
        val sorted = candidates.sortedByDescending { it.shapeConfidence }
        val kept = ArrayList<Candidate>(sorted.size)
        for (c in sorted) {
            if (kept.any { it.box.intersectionOverUnion(c.box) >= iouThreshold }) continue
            kept += c
        }
        return kept
    }

    /**
     * How many candidates share a form and an approximate size. Symbol sets repeat; a shape that
     * occurs once on a sheet full of similar shapes is more likely to be a stray bit of drawing.
     *
     * Sizes are bucketed on a log scale so that 108 px and 115 px count as the same symbol while
     * 110 px and 400 px do not.
     */
    fun repetitionCounts(candidates: List<Candidate>): List<Candidate> {
        if (candidates.isEmpty()) return candidates
        fun key(c: Candidate): Pair<SymbolKind, Int> {
            val bucket = if (c.sizePx <= 1.0) 0 else Math.round(kotlin.math.ln(c.sizePx) / kotlin.math.ln(1.25)).toInt()
            return c.kind to bucket
        }
        val counts = candidates.groupingBy(::key).eachCount()
        return candidates.map { it.copy(repetitionCount = counts[key(it)] ?: 1) }
    }
}
