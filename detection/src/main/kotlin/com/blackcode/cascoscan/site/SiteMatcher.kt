package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.Origin
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.PointSetAligner
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.Similarity
import kotlin.math.abs
import kotlin.math.max

/**
 * Decides which of the penetrations the drawing requires are actually there, from what the camera saw.
 *
 * The awkward part is that the two sides live in unrelated coordinate systems: the drawing is a plan in
 * page pixels, the photograph is a wall in camera pixels, and nothing registers one to the other. Two
 * strategies are offered because neither works everywhere.
 *
 * **Planar** fits a similarity transform from the photograph to the drawing by RANSAC over the point
 * sets. It is the right answer when the photograph really is a flat view of the same plane the drawing
 * shows - a slab shot from above - and it needs at least two penetrations in common to lock on.
 *
 * **Order and size** matches the two sequences along their dominant axis instead, without ever
 * registering the images. This is what a row of sleeves through a wall needs: the plan shows that wall
 * as a line, so the vertical position of a hole in the photograph does not exist on the drawing at all
 * and no 2-D transform can be fitted. It also degrades gracefully to a single hole.
 *
 * The auditor's tap on the drawing is what makes either usable, by narrowing "required" from the whole
 * sheet to the few penetrations that could plausibly be in frame - see [requiredNear].
 */
object SiteMatcher {

    enum class Strategy {
        /** Similarity transform fitted from the photograph to the drawing. */
        PLANAR,

        /** Sequence match along the dominant axis, with no image registration. */
        ORDER_AND_SIZE,
    }

    enum class Verdict {
        PRESENT,

        /** Found, but not the size the drawing asks for. */
        WRONG_SIZE,

        /** Found, but further from its drawn position than the tolerance allows. */
        WRONG_POSITION,
    }

    data class Match(
        val required: Penetration,
        val observed: SiteObservation,
        /** Null when no transform was fitted, which is the normal case for a wall. */
        val offsetMm: Double?,
        val sizeDeltaMm: Double?,
        val verdict: Verdict,
    )

    data class Result(
        val strategy: Strategy,
        /** Photograph pixels to drawing pixels, when one was fitted. */
        val transform: Similarity?,
        val matched: List<Match>,
        /** Required by the drawing, not found in the photograph: the findings. */
        val missing: List<Penetration>,
        /** In the building, not on the drawing. */
        val unexpected: List<SiteObservation>,
        val alignment: PointSetAligner.Result?,
        /** Set when the result should be confirmed by hand before it is believed. */
        val warning: String?,
    ) {
        val requiredCount: Int get() = matched.size + missing.size
        val deviations: List<Match> get() = matched.filter { it.verdict != Verdict.PRESENT }
    }

    data class Params(
        /** How far a hole may be from its drawn position, once a transform exists, and still match. */
        val positionToleranceMm: Double = 250.0,
        /** Beyond this it is reported as found-but-displaced rather than clean. */
        val positionWarnMm: Double = 120.0,
        /**
         * Relative size difference tolerated before a size finding is raised. Wider than the drawing
         * detector's, because one side of this comparison is a measurement off a photograph.
         */
        val sizeToleranceRatio: Double = 0.25,
        /**
         * Relative size difference within which two holes may still be *paired*. Looser than
         * [sizeToleranceRatio], which decides whether a pairing is then reported as the wrong size:
         * calling a present hole missing is a worse error than flagging its bore.
         */
        val matchSizeToleranceRatio: Double = 0.40,
        /** Required penetrations below this confidence do not raise findings against the builder. */
        val minRequiredConfidence: Double = 0.5,
        /** Observations below this are not treated as evidence that a hole exists. */
        val minObservedConfidence: Double = 0.5,
        /** How far the fitted photograph-to-drawing scale may stray from the calibrated expectation. */
        val scaleTolerance: Double = 0.4,
    )

    /**
     * The penetrations that could plausibly be in a photograph taken at [tapOnDrawing].
     *
     * Without this the comparison is meaningless: three holes in a photograph would be matched against
     * every penetration on the sheet, and the other two hundred would all be reported missing.
     */
    fun requiredNear(
        all: List<Penetration>,
        tapOnDrawing: Pt,
        radiusMm: Double,
        drawingScale: DrawingScale,
    ): List<Penetration> {
        val radiusPx = drawingScale.mmToPx(radiusMm)
        return all.filter { it.center.distanceTo(tapOnDrawing) <= radiusPx }
    }

    /**
     * @param photoMmPerPx calibration of the photograph, if it has one. Used both to judge sizes and to
     *   constrain the alignment: with both sides calibrated the photograph-to-drawing scale is known in
     *   advance, which is what lets RANSAC lock on from only two or three shared holes.
     */
    fun match(
        required: List<Penetration>,
        observed: List<SiteObservation>,
        drawingScale: DrawingScale?,
        photoMmPerPx: Double?,
        params: Params = Params(),
        strategy: Strategy = Strategy.ORDER_AND_SIZE,
        siteConfig: SiteConfig = SiteConfig(),
    ): Result {
        val usableRequired = required.filter {
            it.confidence >= params.minRequiredConfidence || it.origin != Origin.DETECTED
        }
        val usableObserved = observed.filter { it.confidence >= params.minObservedConfidence }

        if (strategy == Strategy.PLANAR && drawingScale != null && usableRequired.size >= 2 && usableObserved.size >= 2) {
            planar(usableRequired, usableObserved, drawingScale, photoMmPerPx, params, siteConfig)
                ?.let { return it }
        }
        return orderAndSize(usableRequired, usableObserved, params, siteConfig)
    }

    /** Fits photograph -> drawing and matches by position. Returns null when the fit is not usable. */
    private fun planar(
        required: List<Penetration>,
        observed: List<SiteObservation>,
        drawingScale: DrawingScale,
        photoMmPerPx: Double?,
        params: Params,
        siteConfig: SiteConfig,
    ): Result? {
        // With both sides calibrated the scale is not a free parameter: one photograph pixel is
        // photoMmPerPx of building, one drawing pixel is drawingScale.mmPerPx, so the ratio is known.
        val expected = photoMmPerPx?.let { it / drawingScale.mmPerPx }

        // A tolerance wider than the gaps between the required penetrations cannot tell them apart, and
        // invites a wrong overlay that satisfies everything. Cap it by how closely they actually sit.
        val requiredIndex = com.blackcode.cascoscan.detect.SpatialIndex(
            required.map { it.center },
            drawingScale.mmToPx(params.positionToleranceMm),
        )
        val spacingCap = requiredIndex.medianNearestNeighbour()?.times(0.45) ?: Double.MAX_VALUE
        val tolerancePx = kotlin.math.min(drawingScale.mmToPx(params.positionToleranceMm), spacingCap)

        val alignParams = if (expected != null) {
            PointSetAligner.Params(
                inlierTolerancePx = tolerancePx,
                minScale = expected * (1.0 - params.scaleTolerance),
                maxScale = expected * (1.0 + params.scaleTolerance),
                maxRotationDeg = null,
            )
        } else {
            PointSetAligner.Params(inlierTolerancePx = tolerancePx)
        }

        val alignment = PointSetAligner.align(
            from = observed.map { it.center },
            to = required.map { it.center },
            fromSizes = observed.map { it.features.ellipse.diameterPx },
            toSizes = required.map { it.box.longSide.toDouble() },
            params = alignParams,
        ) ?: return null
        if (!alignment.isTrustworthy(minInliers = 2, minRatio = 0.4)) return null

        data class Link(val oi: Int, val ri: Int, val d: Double)
        val links = ArrayList<Link>()
        for (oi in observed.indices) {
            val mapped = alignment.transform.apply(observed[oi].center)
            for (ri in required.indices) {
                val d = required[ri].center.distanceTo(mapped)
                if (d <= tolerancePx) links += Link(oi, ri, d)
            }
        }
        links.sortBy { it.d }

        val usedObserved = HashSet<Int>()
        val usedRequired = HashSet<Int>()
        val matched = ArrayList<Match>()
        for (link in links) {
            if (link.oi in usedObserved || link.ri in usedRequired) continue
            usedObserved += link.oi
            usedRequired += link.ri
            matched += verdictFor(
                required[link.ri],
                observed[link.oi],
                offsetMm = drawingScale.pxToMm(link.d),
                params = params,
                siteConfig = siteConfig,
            )
        }
        return Result(
            strategy = Strategy.PLANAR,
            transform = alignment.transform,
            matched = matched,
            missing = required.indices.filter { it !in usedRequired }.map { required[it] },
            unexpected = observed.indices.filter { it !in usedObserved }.map { observed[it] },
            alignment = alignment,
            warning = if (alignment.inlierRatio < 0.7) {
                "Only ${alignment.inliers} of ${alignment.total} holes lined up with the drawing; " +
                    "check the matches before accepting them."
            } else {
                null
            },
        )
    }

    /**
     * Matches the two sequences along their dominant axis, preserving order.
     *
     * No registration, so it works for a wall the drawing shows only as a line - and order is a real
     * constraint, not a convenience: holes along a wall cannot swap places between the drawing and the
     * building. It also degrades gracefully to a single hole.
     *
     * The pairing is chosen by maximising total size agreement with a gap cost, in the manner of a
     * sequence alignment, rather than by maximising the *number* of pairs that pass a compatibility
     * test. That distinction decides real cases. Given a wall wanting 110, 160, 200, 110 and a photograph
     * showing 110, 200, 110, any threshold loose enough to tolerate photographic error also lets 160
     * pair with 200, so two different orderings tie on count and the wrong one can win. Scoring the
     * agreement instead prefers the reading where every pair matches well, and correctly reports the 160
     * as the hole nobody drilled.
     *
     * The gap cost follows from the tolerance rather than being tuned: at half of
     * `1 - matchSizeToleranceRatio`, paying two gaps costs exactly what a pair at the tolerance limit
     * scores, so the alignment starts preferring "missing plus unexpected" precisely where a pairing
     * stops being credible. One consequence is worth having: a hole drilled to the wrong bore is still
     * paired, and reported as the wrong size, instead of becoming a missing hole *and* an unrequested one.
     */
    private fun orderAndSize(
        required: List<Penetration>,
        observed: List<SiteObservation>,
        params: Params,
        siteConfig: SiteConfig,
    ): Result {
        val requiredAxis = dominantAxis(required.map { it.center })
        val observedAxis = dominantAxis(observed.map { it.center })
        val requiredSorted = required.sortedBy { requiredAxis(it.center) }
        val observedSorted = observed.sortedBy { observedAxis(it.center) }

        val n = requiredSorted.size
        val m = observedSorted.size
        val gap = (1.0 - params.matchSizeToleranceRatio) / 2.0

        val score = Array(n + 1) { DoubleArray(m + 1) }
        val step = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            score[i][0] = score[i - 1][0] - gap
            step[i][0] = SKIP_REQUIRED
        }
        for (j in 1..m) {
            score[0][j] = score[0][j - 1] - gap
            step[0][j] = SKIP_OBSERVED
        }
        for (i in 1..n) {
            for (j in 1..m) {
                val paired = score[i - 1][j - 1] +
                    sizeAgreement(requiredSorted[i - 1], observedSorted[j - 1], siteConfig)
                val skipRequired = score[i - 1][j] - gap
                val skipObserved = score[i][j - 1] - gap
                val best = maxOf(paired, skipRequired, skipObserved)
                score[i][j] = best
                step[i][j] = when (best) {
                    paired -> PAIR
                    skipRequired -> SKIP_REQUIRED
                    else -> SKIP_OBSERVED
                }
            }
        }

        val matched = ArrayList<Match>()
        val missing = ArrayList<Penetration>()
        val unexpected = ArrayList<SiteObservation>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && step[i][j] == PAIR -> {
                    matched += verdictFor(requiredSorted[i - 1], observedSorted[j - 1], null, params, siteConfig)
                    i--
                    j--
                }
                i > 0 && (j == 0 || step[i][j] == SKIP_REQUIRED) -> {
                    missing += requiredSorted[i - 1]
                    i--
                }
                else -> {
                    unexpected += observedSorted[j - 1]
                    j--
                }
            }
        }

        return Result(
            strategy = Strategy.ORDER_AND_SIZE,
            transform = null,
            matched = matched.reversed(),
            missing = missing.reversed(),
            unexpected = unexpected.reversed(),
            alignment = null,
            warning = if (observed.isEmpty() && required.isNotEmpty()) {
                "No penetrations were found in this photograph, so every one the drawing asks for here " +
                    "is reported missing. Check the framing and the light before accepting that."
            } else {
                null
            },
        )
    }

    /**
     * How well two holes agree on size, in 0..1. Returns [NEUTRAL_AGREEMENT] - deliberately above the
     * cost of two gaps - when either side cannot state a size, so an unmeasurable hole is paired rather
     * than being turned into a missing hole and an unexpected one at the same spot.
     */
    private fun sizeAgreement(
        required: Penetration,
        observed: SiteObservation,
        siteConfig: SiteConfig,
    ): Double {
        val wanted = requiredSizeMm(required) ?: return NEUTRAL_AGREEMENT
        val seen = observed.sizeMm?.long ?: return NEUTRAL_AGREEMENT
        if (!observed.sizeIsReliable(siteConfig)) return NEUTRAL_AGREEMENT
        return (1.0 - abs(seen - wanted) / max(1.0, wanted)).coerceIn(0.0, 1.0)
    }

    /** Sorts along whichever axis the points are more spread over: a wall is a row, not a cloud. */
    private fun dominantAxis(points: List<Pt>): (Pt) -> Double {
        if (points.size < 2) return { it.x }
        val spreadX = points.maxOf { it.x } - points.minOf { it.x }
        val spreadY = points.maxOf { it.y } - points.minOf { it.y }
        return if (spreadX >= spreadY) ({ p: Pt -> p.x }) else ({ p: Pt -> p.y })
    }

    /** What the drawing asks for: the declared size if it is labelled, else what was measured off it. */
    private fun requiredSizeMm(required: Penetration): Double? =
        required.label?.declaredSizeMm ?: required.sizeMm?.long

    private fun verdictFor(
        required: Penetration,
        observed: SiteObservation,
        offsetMm: Double?,
        params: Params,
        siteConfig: SiteConfig,
    ): Match {
        val wanted = requiredSizeMm(required)
        val seen = observed.sizeMm?.long
        // A size finding is only raised when the measurement can carry it: at a steep viewing angle the
        // number is indicative, and accusing a builder of the wrong bore on that basis is not on.
        val comparable = wanted != null && seen != null && observed.sizeIsReliable(siteConfig)
        val delta = if (wanted != null && seen != null) seen - wanted else null
        val verdict = when {
            comparable && abs(delta!!) / max(1.0, wanted!!) > params.sizeToleranceRatio -> Verdict.WRONG_SIZE
            offsetMm != null && offsetMm > params.positionWarnMm -> Verdict.WRONG_POSITION
            else -> Verdict.PRESENT
        }
        return Match(required, observed, offsetMm, delta, verdict)
    }

    /**
     * Turns a comparison into checklist updates: what to set each required penetration to, and the note
     * explaining why. The caller applies these to its own store.
     */
    fun toChecklistUpdates(result: Result): List<Pair<Penetration, Update>> {
        val out = ArrayList<Pair<Penetration, Update>>(result.requiredCount)
        for (match in result.matched) {
            val status = when (match.verdict) {
                Verdict.PRESENT -> AuditStatus.PRESENT
                Verdict.WRONG_SIZE -> AuditStatus.WRONG_SIZE
                Verdict.WRONG_POSITION -> AuditStatus.WRONG_POSITION
            }
            val note = when (match.verdict) {
                Verdict.PRESENT -> "Seen on site as ${match.observed.describeSize}."
                Verdict.WRONG_SIZE -> buildString {
                    append("Measured ${match.observed.describeSize} on site")
                    requiredSizeMm(match.required)?.let { append(", drawing asks for ${it.toInt()} mm") }
                    append(
                        " (photographed at ${match.observed.tiltDeg.toInt()} degrees off-axis).",
                    )
                }
                Verdict.WRONG_POSITION ->
                    "Found ${match.offsetMm?.toInt() ?: 0} mm from its drawn position."
            }
            out += match.required to Update(status, note, match.observed.photoUri)
        }
        for (missing in result.missing) {
            out += missing to Update(
                AuditStatus.MISSING,
                "Not found in the photograph of this area.",
                null,
            )
        }
        return out
    }

    /** A status, a note and the photograph that justifies them. */
    data class Update(val status: AuditStatus, val note: String, val photoUri: String?)

    private const val PAIR = 0
    private const val SKIP_REQUIRED = 1
    private const val SKIP_OBSERVED = 2

    /** Agreement assumed when a size cannot be compared; must exceed the cost of two gaps. */
    private const val NEUTRAL_AGREEMENT = 0.70
}
