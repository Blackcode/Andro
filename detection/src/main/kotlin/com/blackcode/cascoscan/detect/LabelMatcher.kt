package com.blackcode.cascoscan.detect

import kotlin.math.hypot

/**
 * Attaches each annotation to the symbol it belongs to.
 *
 * Draughtsmen put the text beside the symbol, sometimes with a leader line, and several symbols can
 * share a crowded corner - so this is an assignment problem, not a lookup. Greedy nearest-first is
 * enough in practice and has the property that matters here: one label is never counted as evidence
 * for two different penetrations.
 */
object LabelMatcher {

    data class Match(val candidateIndex: Int, val label: ParsedLabel, val distancePx: Double)

    fun match(candidates: List<Candidate>, labels: List<ParsedLabel>, config: DetectionConfig): List<Match> {
        if (candidates.isEmpty() || labels.isEmpty()) return emptyList()

        data class Pair(val ci: Int, val li: Int, val d: Double)

        val pairs = ArrayList<Pair>()
        for (ci in candidates.indices) {
            val c = candidates[ci]
            val radius = Scoring.labelSearchRadius(c, config)
            for (li in labels.indices) {
                val d = boxDistance(c.box, labels[li].box)
                if (d <= radius) pairs += Pair(ci, li, d)
            }
        }
        pairs.sortBy { it.d }

        val takenCandidates = HashSet<Int>()
        val takenLabels = HashSet<Int>()
        val out = ArrayList<Match>()
        for (p in pairs) {
            if (p.ci in takenCandidates || p.li in takenLabels) continue
            takenCandidates += p.ci
            takenLabels += p.li
            out += Match(p.ci, labels[p.li], p.d)
        }
        return out
    }

    /** Applies the matches, returning a new candidate list. */
    fun apply(candidates: List<Candidate>, matches: List<Match>): List<Candidate> {
        if (matches.isEmpty()) return candidates
        val byIndex = matches.associateBy { it.candidateIndex }
        return candidates.mapIndexed { i, c ->
            val m = byIndex[i] ?: return@mapIndexed c
            c.copy(label = m.label, labelDistancePx = m.distancePx)
        }
    }

    /** Gap between two boxes: 0 when they touch or overlap, otherwise the shortest edge distance. */
    fun boxDistance(a: IBox, b: IBox): Double {
        val dx = when {
            b.right < a.left -> (a.left - b.right).toDouble()
            a.right < b.left -> (b.left - a.right).toDouble()
            else -> 0.0
        }
        val dy = when {
            b.bottom < a.top -> (a.top - b.bottom).toDouble()
            a.bottom < b.top -> (b.top - a.bottom).toDouble()
            else -> 0.0
        }
        return hypot(dx, dy)
    }
}
