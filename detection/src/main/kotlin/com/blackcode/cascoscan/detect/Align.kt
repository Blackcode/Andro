package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Finds the transform that puts one drawing on top of another.
 *
 * Needed because "what is missing" is a comparison between two sheets - the MEP sheet that requires
 * the penetrations and the structural or as-built sheet that should contain them - and those sheets
 * are plotted at different scales, cropped differently, and sometimes rotated. Without alignment the
 * comparison is meaningless.
 *
 * Two points define a similarity transform, so RANSAC over point *pairs* is enough: hypothesise that
 * pair (a1,a2) corresponds to (b1,b2), solve exactly, count how many other penetrations then line up.
 * The candidate pairing is pruned by requiring the two symbols to be of similar drawn size and the
 * two pair-distances to imply a scale within [Params.minScale]..[Params.maxScale], which cuts the
 * search space by orders of magnitude on a busy sheet.
 */
object PointSetAligner {

    data class Params(
        /** How far apart two points may be, in units of the *target* page, and still count as one. */
        val inlierTolerancePx: Double = 24.0,
        val minScale: Double = 0.4,
        val maxScale: Double = 2.5,
        /** Drawings are rarely rotated by odd angles; set null to allow any rotation. */
        val maxRotationDeg: Double? = 12.0,
        val maxIterations: Int = 4000,
        /** Stop early once this fraction of the reference points is explained *and* fits tightly. */
        val goodEnoughInlierRatio: Double = 0.85,
        /**
         * How tightly, as a fraction of [inlierTolerancePx], before the search may stop early.
         *
         * Counting inliers alone is not enough to stop on. With only a handful of points and a tolerance
         * that is an appreciable share of their spacing, many quite different transforms explain every
         * point, and the first one stumbled upon wins - a wrong overlay that happens to satisfy the
         * count. Requiring the fit to be tight as well means the search only stops when it has found an
         * answer that is right rather than merely adequate.
         */
        val goodEnoughErrorFraction: Double = 0.20,
        /** Fixed seed: an audit must be reproducible. */
        val seed: Long = 20240917L,
    )

    data class Result(
        val transform: Similarity,
        val inliers: Int,
        val total: Int,
        val meanErrorPx: Double,
    ) {
        val inlierRatio: Double get() = if (total == 0) 0.0 else inliers.toDouble() / total

        /** Below this the overlay is guesswork and the app must ask for control points instead. */
        fun isTrustworthy(minInliers: Int = 4, minRatio: Double = 0.45) =
            inliers >= minInliers && inlierRatio >= minRatio
    }

    /**
     * @param from points on the reference page, [to] points on the target page. Sizes are the drawn
     *   sizes in each page's own pixels, used only to prune implausible pairings.
     */
    fun align(
        from: List<Pt>,
        to: List<Pt>,
        fromSizes: List<Double> = emptyList(),
        toSizes: List<Double> = emptyList(),
        params: Params = Params(),
    ): Result? {
        if (from.size < 2 || to.size < 2) return null
        val rng = Random(params.seed)
        val grid = SpatialIndex(to, params.inlierTolerancePx)

        var best: Result? = null
        var iterations = 0
        val maxRotation = params.maxRotationDeg?.let { Math.toRadians(it) }

        while (iterations < params.maxIterations) {
            iterations++
            val i1 = rng.nextInt(from.size)
            val i2 = rng.nextInt(from.size)
            if (i1 == i2) continue
            val j1 = rng.nextInt(to.size)
            val j2 = rng.nextInt(to.size)
            if (j1 == j2) continue

            val dFrom = from[i1].distanceTo(from[i2])
            val dTo = to[j1].distanceTo(to[j2])
            if (dFrom < 8.0 || dTo < 8.0) continue
            val impliedScale = dTo / dFrom
            if (impliedScale < params.minScale || impliedScale > params.maxScale) continue
            if (!sizesCompatible(fromSizes, toSizes, i1, j1, impliedScale)) continue
            if (!sizesCompatible(fromSizes, toSizes, i2, j2, impliedScale)) continue

            val candidate = Similarity.fit(listOf(from[i1], from[i2]), listOf(to[j1], to[j2])) ?: continue
            if (maxRotation != null && abs(normaliseAngle(candidate.rotationRad)) > maxRotation) continue

            val evaluated = evaluate(candidate, from, grid, params.inlierTolerancePx)
            if (best == null || evaluated.inliers > best.inliers ||
                (evaluated.inliers == best.inliers && evaluated.meanErrorPx < best.meanErrorPx)
            ) {
                best = evaluated
                val explained = evaluated.inlierRatio >= params.goodEnoughInlierRatio
                val tight = evaluated.meanErrorPx <= params.inlierTolerancePx * params.goodEnoughErrorFraction
                if (explained && tight) break
            }
        }

        // One least-squares refit over all inliers: RANSAC's two points give the right hypothesis but
        // not the best fit.
        val rough = best ?: return null
        val refined = refine(rough, from, to, grid, params) ?: rough
        // The refit is unconstrained, so it can drift outside the scale or rotation the caller allowed;
        // when it does, the hypothesis that respected them is the one to keep.
        return if (permitted(refined.transform, params)) refined else rough
    }

    private fun permitted(t: Similarity, params: Params): Boolean {
        if (t.scale < params.minScale || t.scale > params.maxScale) return false
        val maxRotation = params.maxRotationDeg?.let { Math.toRadians(it) } ?: return true
        return abs(normaliseAngle(t.rotationRad)) <= maxRotation
    }

    private fun sizesCompatible(
        fromSizes: List<Double>,
        toSizes: List<Double>,
        i: Int,
        j: Int,
        scale: Double,
    ): Boolean {
        if (i >= fromSizes.size || j >= toSizes.size) return true
        val a = fromSizes[i] * scale
        val b = toSizes[j]
        if (a <= 0.0 || b <= 0.0) return true
        val ratio = max(a, b) / kotlin.math.min(a, b)
        return ratio <= 2.0
    }

    /**
     * Counts how many of [from] land on a target point, **one target at most once**.
     *
     * The uniqueness is not a nicety. Without it a transform that squeezes several source points onto one
     * target counts them all, so a degenerate fit can score a perfect inlier ratio while explaining only
     * one real correspondence - which is exactly how a wrong overlay wins when the tolerance is an
     * appreciable fraction of the spacing between targets.
     */
    private fun evaluate(t: Similarity, from: List<Pt>, index: SpatialIndex, tolerance: Double): Result {
        var inliers = 0
        var errorSum = 0.0
        val claimed = HashSet<Int>()
        for (p in from) {
            val mapped = t.apply(p)
            val nearest = index.nearest(mapped, tolerance) ?: continue
            if (!claimed.add(nearest)) continue
            inliers++
            errorSum += index.distanceTo(nearest, mapped)
        }
        return Result(t, inliers, from.size, if (inliers == 0) Double.MAX_VALUE else errorSum / inliers)
    }

    private fun refine(
        rough: Result,
        from: List<Pt>,
        to: List<Pt>,
        index: SpatialIndex,
        params: Params,
    ): Result? {
        val src = ArrayList<Pt>()
        val dst = ArrayList<Pt>()
        val claimed = HashSet<Int>()
        for (p in from) {
            val mapped = rough.transform.apply(p)
            val nearest = index.nearest(mapped, params.inlierTolerancePx) ?: continue
            if (!claimed.add(nearest)) continue
            src += p
            dst += to[nearest]
        }
        if (src.size < 2) return null
        val refitted = Similarity.fit(src, dst) ?: return null
        val evaluated = evaluate(refitted, from, index, params.inlierTolerancePx)
        return if (evaluated.inliers >= rough.inliers) evaluated else rough
    }

    private fun normaliseAngle(rad: Double): Double {
        var a = rad
        while (a > Math.PI) a -= 2 * Math.PI
        while (a < -Math.PI) a += 2 * Math.PI
        return a
    }
}

/** Uniform-grid nearest-neighbour lookup: the inner loop of RANSAC, so it must not be a linear scan. */
class SpatialIndex(private val points: List<Pt>, cellSize: Double) {

    private val cell = max(1.0, cellSize)
    private val buckets = HashMap<Long, MutableList<Int>>()

    init {
        for (i in points.indices) {
            buckets.getOrPut(key(points[i])) { ArrayList() } += i
        }
    }

    private fun key(p: Pt): Long = key(Math.floor(p.x / cell).toInt(), Math.floor(p.y / cell).toInt())

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    /** Index of the closest point within [maxDistance], or null. */
    fun nearest(p: Pt, maxDistance: Double): Int? {
        val cx = Math.floor(p.x / cell).toInt()
        val cy = Math.floor(p.y / cell).toInt()
        val reach = Math.ceil(maxDistance / cell).toInt()
        var bestIndex: Int? = null
        var bestDistance = maxDistance
        for (dy in -reach..reach) {
            for (dx in -reach..reach) {
                val bucket = buckets[key(cx + dx, cy + dy)] ?: continue
                for (i in bucket) {
                    val d = points[i].distanceTo(p)
                    if (d <= bestDistance) {
                        bestDistance = d
                        bestIndex = i
                    }
                }
            }
        }
        return bestIndex
    }

    fun nearestDistance(p: Pt, maxDistance: Double): Double? =
        nearest(p, maxDistance)?.let { points[it].distanceTo(p) }

    fun distanceTo(index: Int, p: Pt): Double = points[index].distanceTo(p)

    /**
     * Median distance from each point to its nearest neighbour: the natural scale at which a matching
     * tolerance stops being able to tell these points apart.
     */
    fun medianNearestNeighbour(): Double? {
        if (points.size < 2) return null
        val distances = points.indices.mapNotNull { i ->
            points.indices.filter { it != i }.minOfOrNull { points[it].distanceTo(points[i]) }
        }.sorted()
        return distances.getOrNull(distances.size / 2)
    }

    /** Every point within [maxDistance] of [p], as index/distance pairs. */
    fun within(p: Pt, maxDistance: Double): List<Pair<Int, Double>> {
        val cx = Math.floor(p.x / cell).toInt()
        val cy = Math.floor(p.y / cell).toInt()
        val reach = Math.ceil(maxDistance / cell).toInt()
        val out = ArrayList<Pair<Int, Double>>()
        for (dy in -reach..reach) {
            for (dx in -reach..reach) {
                val bucket = buckets[key(cx + dx, cy + dy)] ?: continue
                for (i in bucket) {
                    val d = points[i].distanceTo(p)
                    if (d <= maxDistance) out += i to d
                }
            }
        }
        return out
    }
}
