package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.max

/**
 * Answers the question the app exists for: *which required penetrations are not there?*
 *
 * One sheet states the requirement (the MEP or penetration-request drawing) and another shows what
 * is being built (the structural sheet, or a marked-up as-built). After aligning the two, every
 * required penetration that has no counterpart within tolerance is a finding.
 *
 * The same machinery answers the reverse question - openings in the structure that nobody asked for -
 * which on a casco audit is just as reportable, because an unrequested hole is a structural change.
 */
object Reconciler {

    enum class MatchVerdict {
        OK,

        /** Found, but the drawn size differs beyond tolerance. */
        SIZE_MISMATCH,

        /** Found, but displaced further than the positional tolerance allows. */
        POSITION_SHIFTED,
    }

    data class MatchPair(
        val reference: Penetration,
        val target: Penetration,
        val offsetMm: Double?,
        val offsetPx: Double,
        val sizeDeltaMm: Double?,
        val verdict: MatchVerdict,
    )

    data class Params(
        /** Two penetrations this close (in real mm) are the same one. */
        val positionToleranceMm: Double = 150.0,
        /** Beyond this the match is reported as shifted rather than clean. */
        val positionWarnMm: Double = 60.0,
        /** Relative size difference tolerated before a match is reported as a size mismatch. */
        val sizeToleranceRatio: Double = 0.25,
        /** Fallback when no scale is known for the target page. */
        val positionTolerancePxFallback: Double = 30.0,
        /** Reference penetrations below this confidence are not used to raise findings. */
        val minReferenceConfidence: Double = 0.5,
    )

    data class Result(
        val referencePageIndex: Int,
        val targetPageIndex: Int,
        val transform: Similarity,
        val matched: List<MatchPair>,
        /** Required by the reference sheet, absent from the target: the findings. */
        val missingInTarget: List<Penetration>,
        /** Present in the target, not required by the reference sheet. */
        val extraInTarget: List<Penetration>,
        val alignment: PointSetAligner.Result?,
    ) {
        val requiredCount: Int get() = matched.size + missingInTarget.size
        val missingCount: Int get() = missingInTarget.size
        val coverage: Double get() = if (requiredCount == 0) 1.0 else matched.size.toDouble() / requiredCount

        /** Matches that were found but are wrong in size or position. */
        val deviations: List<MatchPair> get() = matched.filter { it.verdict != MatchVerdict.OK }
    }

    /**
     * Compares two pages using an explicit [transform] (from tapped control points, or from
     * [PointSetAligner]).
     *
     * @param targetScale needed to express tolerances in millimetres; without it the pixel fallback
     *   applies and the tolerances are only as good as the assumption that both sheets share a scale.
     */
    fun reconcile(
        reference: List<Penetration>,
        target: List<Penetration>,
        transform: Similarity,
        targetScale: DrawingScale?,
        params: Params = Params(),
        alignment: PointSetAligner.Result? = null,
        referencePageIndex: Int = reference.firstOrNull()?.pageIndex ?: -1,
        targetPageIndex: Int = target.firstOrNull()?.pageIndex ?: -1,
    ): Result {
        val tolerancePx = targetScale?.mmToPx(params.positionToleranceMm) ?: params.positionTolerancePxFallback
        val usable = reference.filter { it.confidence >= params.minReferenceConfidence || it.origin != Origin.DETECTED }

        // Greedy mutual-nearest assignment: shortest links first, each penetration used once. Simple,
        // and stable enough that re-running an audit reproduces the same pairing.
        data class Link(val ri: Int, val ti: Int, val d: Double)

        val mappedCenters = usable.map { transform.apply(it.center) }
        val index = SpatialIndex(target.map { it.center }, tolerancePx)
        val links = ArrayList<Link>()
        for (ri in usable.indices) {
            // Collect every target within tolerance, not just the nearest, so that a slightly closer
            // rival cannot steal a match that belongs to someone else.
            for ((ti, d) in index.within(mappedCenters[ri], tolerancePx)) {
                links += Link(ri, ti, d)
            }
        }
        links.sortBy { it.d }

        val usedReference = HashSet<Int>()
        val usedTarget = HashSet<Int>()
        val matched = ArrayList<MatchPair>()
        for (link in links) {
            if (link.ri in usedReference || link.ti in usedTarget) continue
            usedReference += link.ri
            usedTarget += link.ti
            matched += buildPair(usable[link.ri], target[link.ti], link.d, targetScale, params)
        }

        val missing = usable.indices.filter { it !in usedReference }.map { usable[it] }
        val extra = target.indices.filter { it !in usedTarget }.map { target[it] }

        return Result(
            referencePageIndex = referencePageIndex,
            targetPageIndex = targetPageIndex,
            transform = transform,
            matched = matched,
            missingInTarget = missing,
            extraInTarget = extra,
            alignment = alignment,
        )
    }

    /** Convenience overload that aligns the two pages automatically before comparing. */
    fun reconcileAutoAligned(
        reference: List<Penetration>,
        target: List<Penetration>,
        targetScale: DrawingScale?,
        params: Params = Params(),
        alignParams: PointSetAligner.Params = PointSetAligner.Params(),
    ): Result? {
        val alignment = PointSetAligner.align(
            from = reference.map { it.center },
            to = target.map { it.center },
            fromSizes = reference.map { max(it.box.width, it.box.height).toDouble() },
            toSizes = target.map { max(it.box.width, it.box.height).toDouble() },
            params = alignParams,
        ) ?: return null
        if (!alignment.isTrustworthy()) return null
        return reconcile(reference, target, alignment.transform, targetScale, params, alignment)
    }

    private fun buildPair(
        reference: Penetration,
        target: Penetration,
        distancePx: Double,
        targetScale: DrawingScale?,
        params: Params,
    ): MatchPair {
        val offsetMm = targetScale?.pxToMm(distancePx)
        val sizes = comparableSizes(reference, target)
        val sizeDelta = sizes?.let { (ref, tgt) -> tgt - ref }
        val verdict = when {
            sizes != null && abs(sizeDelta!!) / max(1.0, sizes.first) > params.sizeToleranceRatio ->
                MatchVerdict.SIZE_MISMATCH
            offsetMm != null && offsetMm > params.positionWarnMm -> MatchVerdict.POSITION_SHIFTED
            else -> MatchVerdict.OK
        }
        return MatchPair(reference, target, offsetMm, distancePx, sizeDelta, verdict)
    }

    /**
     * Reference and target size on the same footing, or null when they cannot be compared.
     *
     * Mixing the two kinds of size produces phantom findings. A label declares the *clear* opening
     * ("300x250"); a measurement reads the *drawn outline*, which includes the line weight and any
     * draughting slack, and on a small symbol that gap alone exceeds the tolerance. So declared is
     * compared with declared, measured with measured, and never one against the other.
     */
    private fun comparableSizes(reference: Penetration, target: Penetration): Pair<Double, Double>? {
        val declaredRef = reference.label?.declaredSizeMm
        val declaredTarget = target.label?.declaredSizeMm
        if (declaredRef != null && declaredTarget != null) return declaredRef to declaredTarget
        val measuredRef = reference.sizeMm?.long
        val measuredTarget = target.sizeMm?.long
        if (measuredRef != null && measuredTarget != null) return measuredRef to measuredTarget
        return null
    }

    /**
     * Turns a reconciliation into checklist rows on the *target* sheet, so the auditor walks the
     * building with one list: every required penetration positioned where it should have been, the
     * absent ones already marked as findings.
     */
    fun toChecklist(result: Result, targetPageIndex: Int = result.targetPageIndex): List<Penetration> {
        val rows = ArrayList<Penetration>(result.requiredCount)
        for (pair in result.matched) {
            val status = when (pair.verdict) {
                MatchVerdict.OK -> AuditStatus.PENDING
                MatchVerdict.SIZE_MISMATCH -> AuditStatus.WRONG_SIZE
                MatchVerdict.POSITION_SHIFTED -> AuditStatus.WRONG_POSITION
            }
            rows += pair.target.copy(
                status = status,
                origin = Origin.RECONCILED,
                requiredByPageIndex = pair.reference.pageIndex,
                label = pair.target.label ?: pair.reference.label,
                note = buildNote(pair),
            )
        }
        for (missing in result.missingInTarget) {
            val mapped = result.transform.apply(missing.center)
            val halfWidth = missing.box.width / 2.0 * result.transform.scale
            val halfHeight = missing.box.height / 2.0 * result.transform.scale
            rows += missing.copy(
                id = "${missing.id}!",
                pageIndex = targetPageIndex,
                center = mapped,
                box = IBox.around(mapped, halfWidth.coerceAtLeast(2.0), halfHeight.coerceAtLeast(2.0)),
                status = AuditStatus.MISSING,
                origin = Origin.RECONCILED,
                requiredByPageIndex = missing.pageIndex,
                note = "Required on sheet ${missing.pageIndex + 1} (${missing.id}); no matching opening found here.",
            )
        }
        return rows
    }

    private fun buildNote(pair: MatchPair): String? = when (pair.verdict) {
        MatchVerdict.OK -> null
        MatchVerdict.SIZE_MISMATCH -> {
            val delta = pair.sizeDeltaMm
            "Size differs from sheet ${pair.reference.pageIndex + 1}" +
                (delta?.let { " by ${if (it > 0) "+" else ""}${it.toInt()} mm" } ?: "")
        }
        MatchVerdict.POSITION_SHIFTED ->
            "Offset ${pair.offsetMm?.toInt() ?: pair.offsetPx.toInt()} mm from the position on sheet " +
                "${pair.reference.pageIndex + 1}"
    }
}
