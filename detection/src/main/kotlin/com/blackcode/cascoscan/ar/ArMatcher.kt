package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.Origin
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.site.SiteMatcher
import kotlin.math.abs
import kotlin.math.max

/**
 * A penetration seen through the camera and placed in the room, by detecting it in the frame and asking
 * ARCore what surface that pixel lands on.
 */
data class ObservedInWorld(
    val id: String,
    val world: Vec3,
    /** Measured diameter, when the surface distance was known well enough to derive one. */
    val diameterMm: Double?,
    val confidence: Double,
    /** Where it was in the frame, for drawing it back over the camera image. */
    val screen: Pt,
    val photoUri: String? = null,
)

/**
 * Matches what the camera found in the room against what the drawing requires there.
 *
 * In augmented reality this is a far better-posed problem than it was from a photograph, and worth saying
 * why: once the drawing is registered to the room, both sides are in *the same metric space*. There is no
 * transform to fit, no scale to guess and no ordering to infer - a required penetration and an observed
 * hole either are in the same place to within a tolerance in centimetres, or they are not.
 *
 * The one asymmetry left is height. A plan cannot say how high up a wall a hole sits, so a wall target is
 * matched on its horizontal position alone; only slab targets are matched in all three dimensions.
 */
object ArMatcher {

    data class Params(
        /** How far a hole may be from where the drawing puts it and still be that hole. */
        val positionToleranceM: Double = 0.30,
        /** Beyond this it is found-but-displaced rather than simply present. */
        val positionWarnM: Double = 0.12,
        val sizeToleranceRatio: Double = 0.25,
        /** Detections below this are not evidence that a hole exists. */
        val minObservedConfidence: Double = 0.5,
        /** Required penetrations below this do not raise findings against the builder. */
        val minRequiredConfidence: Double = 0.5,
    )

    data class Match(
        val target: ExpectedTarget,
        val observed: ObservedInWorld,
        val offsetM: Double,
        val sizeDeltaMm: Double?,
        val verdict: SiteMatcher.Verdict,
    )

    data class Result(
        val matched: List<Match>,
        /** Required here, not found: the findings. */
        val missing: List<ExpectedTarget>,
        /** Found, not required: an unrequested opening is a structural change. */
        val unexpected: List<ObservedInWorld>,
    ) {
        val requiredCount: Int get() = matched.size + missing.size
        val deviations: List<Match> get() = matched.filter { it.verdict != SiteMatcher.Verdict.PRESENT }
    }

    fun match(
        targets: List<ExpectedTarget>,
        observed: List<ObservedInWorld>,
        placement: PlanPlacement,
        params: Params = Params(),
    ): Result {
        val usableTargets = targets.filter {
            it.penetration.confidence >= params.minRequiredConfidence ||
                it.penetration.origin != Origin.DETECTED
        }
        val usableObserved = observed.filter { it.confidence >= params.minObservedConfidence }

        // Shortest links first, each side used once. No transform to fit: both are already in metres in
        // the same frame, so this is simply nearest-neighbour with a tolerance.
        data class Link(val ti: Int, val oi: Int, val d: Double)
        val links = ArrayList<Link>()
        for (ti in usableTargets.indices) {
            for (oi in usableObserved.indices) {
                val d = separation(usableTargets[ti], usableObserved[oi], placement)
                if (d <= params.positionToleranceM) links += Link(ti, oi, d)
            }
        }
        links.sortBy { it.d }

        val usedTargets = HashSet<Int>()
        val usedObserved = HashSet<Int>()
        val matched = ArrayList<Match>()
        for (link in links) {
            if (link.ti in usedTargets || link.oi in usedObserved) continue
            usedTargets += link.ti
            usedObserved += link.oi
            matched += verdictFor(usableTargets[link.ti], usableObserved[link.oi], link.d, params)
        }

        return Result(
            matched = matched,
            missing = usableTargets.indices.filter { it !in usedTargets }.map { usableTargets[it] },
            unexpected = usableObserved.indices.filter { it !in usedObserved }.map { usableObserved[it] },
        )
    }

    /**
     * Distance between a requirement and an observation, ignoring height for a wall - because the plan
     * never knew the height, so disagreeing about it is not a disagreement.
     */
    private fun separation(
        target: ExpectedTarget,
        observed: ObservedInWorld,
        placement: PlanPlacement,
    ): Double = when (target.geometry) {
        TargetGeometry.ON_SLAB -> target.base.distanceTo(observed.world)
        TargetGeometry.ON_WALL -> {
            // Project both onto the floor plane and measure there.
            val fromBase = observed.world - target.base
            val vertical = placement.up * (fromBase dot placement.up)
            (fromBase - vertical).length
        }
    }

    private fun verdictFor(
        target: ExpectedTarget,
        observed: ObservedInWorld,
        offsetM: Double,
        params: Params,
    ): Match {
        val wanted = target.penetration.label?.declaredSizeMm ?: target.penetration.sizeMm?.long
        val seen = observed.diameterMm
        val delta = if (wanted != null && seen != null) seen - wanted else null
        val verdict = when {
            delta != null && abs(delta) / max(1.0, wanted!!) > params.sizeToleranceRatio ->
                SiteMatcher.Verdict.WRONG_SIZE
            offsetM > params.positionWarnM -> SiteMatcher.Verdict.WRONG_POSITION
            else -> SiteMatcher.Verdict.PRESENT
        }
        return Match(target, observed, offsetM, delta, verdict)
    }

    /** Turns a comparison into checklist updates, in the same shape the photographic flow produces. */
    fun toChecklistUpdates(result: Result): List<Pair<Penetration, SiteMatcher.Update>> {
        val out = ArrayList<Pair<Penetration, SiteMatcher.Update>>(result.requiredCount)
        for (match in result.matched) {
            val status = when (match.verdict) {
                SiteMatcher.Verdict.PRESENT -> AuditStatus.PRESENT
                SiteMatcher.Verdict.WRONG_SIZE -> AuditStatus.WRONG_SIZE
                SiteMatcher.Verdict.WRONG_POSITION -> AuditStatus.WRONG_POSITION
            }
            val note = when (match.verdict) {
                SiteMatcher.Verdict.PRESENT ->
                    "Seen in place, %.0f cm from its drawn position.".format(match.offsetM * 100)
                SiteMatcher.Verdict.WRONG_SIZE -> buildString {
                    append("Measured ")
                    append(match.observed.diameterMm?.toInt() ?: 0)
                    append(" mm on site")
                    match.target.penetration.label?.declaredSizeMm?.let { append(", drawing asks ${it.toInt()} mm") }
                    append('.')
                }
                SiteMatcher.Verdict.WRONG_POSITION ->
                    "Found %.0f cm from its drawn position.".format(match.offsetM * 100)
            }
            out += match.target.penetration to SiteMatcher.Update(status, note, match.observed.photoUri)
        }
        for (missing in result.missing) {
            out += missing.penetration to SiteMatcher.Update(
                AuditStatus.MISSING,
                "Looked for in place and not found.",
                null,
            )
        }
        return out
    }
}
