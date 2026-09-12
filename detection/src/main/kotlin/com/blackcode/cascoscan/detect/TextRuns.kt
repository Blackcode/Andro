package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the blobs that are *letters*, by the one property letters have and symbols do not: they come
 * in rows.
 *
 * Size cannot separate them. On a 1:50 plan a 110 mm sleeve is 2.2 mm on paper and the annotation
 * beside it is 2.5 mm, so a symbol and a character are the same size by construction - and a drawn
 * "0" or "X" is a ring or a cross as far as any shape measurement can tell. What differs is company:
 * characters sit on a shared baseline with gaps far smaller than their own height, while penetration
 * symbols are spread across the plan.
 *
 * This runs on geometry alone, so it works on a scanned sheet with no text layer - which is exactly
 * when it is needed, since a PDF text layer would have answered the question directly.
 */
object TextRuns {

    data class Params(
        /** Characters in one run are about the same height. */
        val heightRatioTolerance: Double = 1.9,
        /** ... and sit on the same baseline, within this fraction of their height. */
        val baselineTolerance: Double = 0.40,
        /** ... separated by a gap smaller than this multiple of their height. */
        val gapFactor: Double = 1.30,
        /** How many characters make a run. Two is not enough: two sleeves can sit side by side. */
        val minMembers: Int = 3,
        /**
         * Slivers below this squareness cannot be characters, so they cannot be run members either.
         *
         * This exclusion is load-bearing. A rectangular opening in a wall is drawn as a gap with a
         * short closing line at each end, and those three blobs are the same height, on the same
         * baseline, touching - a perfect text run by every other test. Ruling the closing lines out as
         * characters leaves the opening alone and unpenalised.
         */
        val minMemberSquareness: Double = 0.15,
    )

    /**
     * @return one flag per input box: true when that box belongs to a run of aligned, similar,
     *   closely-spaced neighbours - in other words, to a piece of text.
     */
    fun detect(boxes: List<IBox>, params: Params = Params()): BooleanArray {
        val n = boxes.size
        val inRun = BooleanArray(n)
        if (n < params.minMembers) return inRun

        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val up = parent[cur]
                parent[cur] = root
                cur = up
            }
            return root
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[max(ra, rb)] = kotlin.math.min(ra, rb)
        }

        // Sorting by x keeps the neighbour scan local: a character's partner is the next glyph along.
        val eligible = BooleanArray(n) { boxes[it].squareness >= params.minMemberSquareness }
        val order = boxes.indices.filter { eligible[it] }.sortedBy { boxes[it].left }
        for (oi in order.indices) {
            val i = order[oi]
            val a = boxes[i]
            for (oj in (oi + 1) until order.size) {
                val j = order[oj]
                val b = boxes[j]
                val maxHeight = max(a.height, b.height).toDouble()
                // Ordered by left edge, so once b starts beyond reach, so does everything after it.
                if (b.left - a.right > params.gapFactor * maxHeight) break
                if (!similarHeight(a, b, params)) continue
                if (abs(a.center.y - b.center.y) > params.baselineTolerance * maxHeight) continue
                val gap = (b.left - a.right).coerceAtLeast(0)
                if (gap > params.gapFactor * maxHeight) continue
                union(i, j)
            }
        }

        val sizes = HashMap<Int, Int>()
        for (i in 0 until n) if (eligible[i]) sizes.merge(find(i), 1, Int::plus)
        for (i in 0 until n) {
            if (eligible[i] && (sizes[find(i)] ?: 0) >= params.minMembers) inRun[i] = true
        }
        return inRun
    }

    private fun similarHeight(a: IBox, b: IBox, params: Params): Boolean {
        val hi = max(a.height, b.height).toDouble()
        val lo = kotlin.math.min(a.height, b.height).toDouble()
        return lo > 0 && hi / lo <= params.heightRatioTolerance
    }
}
