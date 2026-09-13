package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One thing the auditor identified twice: a point on the drawing, and the same point in the room. */
data class ControlPoint(val onDrawing: Pt, val inWorld: Vec3)

/**
 * Puts the drawing into the room.
 *
 * This is what makes the whole augmented-reality idea work, and it is a smaller problem than it looks
 * once two facts are used. The plot scale already fixes how many millimetres a drawing pixel is worth,
 * so scale is not a free parameter. And the phone knows which way is up, so the plan - which is a
 * horizontal section - can only be rotated about that axis. What is left is one angle and a translation:
 * four unknowns, which two tapped points determine exactly and three or more determine with a residual
 * worth reading.
 *
 * Orientation is handled rather than guessed. A plan has x to the right and **y increasing downwards**,
 * and it is drawn as seen from above. Take a right-handed world basis (e1, e2, up): looking down on it
 * along -up, e1 turns to e2 anticlockwise, while on the page x turns to y clockwise. So plan (x, y) maps
 * to (x, -y) in that basis, and the fit is then a pure rotation with no reflection to resolve - which
 * matters, because two control points cannot tell a reflection from a rotation, and a mirrored plan would
 * put every penetration on the wrong side of the room.
 */
object PlanRegistration {

    data class Result(
        val placement: PlanPlacement,
        /** Root-mean-square distance between where the control points landed and where they were tapped. */
        val residualM: Double,
        /**
         * Scale the control points imply, over the scale the plot declares. Meaningfully different from
         * 1.0 means the plot scale is wrong, the wrong two points were tapped, or the drawing is not of
         * this room - and it is the only check available from just two points.
         */
        val impliedScaleRatio: Double?,
        val controlPointCount: Int,
    ) {
        /** Below this the overlay is trustworthy enough to raise findings from. */
        fun isTrustworthy(maxResidualM: Double = 0.15, maxScaleError: Double = 0.12): Boolean {
            if (residualM > maxResidualM) return false
            val ratio = impliedScaleRatio ?: return true
            return abs(ratio - 1.0) <= maxScaleError
        }

        val warning: String?
            get() = when {
                controlPointCount < 2 -> "Two reference points are needed before the drawing can be placed."
                impliedScaleRatio != null && abs(impliedScaleRatio - 1.0) > 0.12 ->
                    "The two points are %.0f%% further apart in the room than the drawing says. Check the plot ".format(
                        (impliedScaleRatio - 1.0) * 100,
                    ) + "scale, or that you tapped the same two features."
                residualM > 0.15 ->
                    "The reference points fit to %.0f cm. Re-tap them, or add another, before trusting the overlay.".format(
                        residualM * 100,
                    )
                else -> null
            }
    }

    /**
     * @param up world up, from the device's own gravity estimate. ARCore's world is y-up, so
     *   [Vec3.UP] is the right default, but passing the measured vector keeps a tilted session honest.
     */
    fun fit(
        controlPoints: List<ControlPoint>,
        drawingScale: DrawingScale,
        up: Vec3 = Vec3.UP,
    ): Result? {
        if (controlPoints.size < 2) return null
        val u = up.normalised()
        if (u.length < 0.5) return null
        val e1 = u.anyPerpendicular()
        val e2 = (u cross e1).normalised()

        // Plan coordinates in metres, with y negated so the page's orientation survives - see the class note.
        val metresPerPx = drawingScale.mmPerPx / 1000.0
        val plan = controlPoints.map { Pt(it.onDrawing.x * metresPerPx, -it.onDrawing.y * metresPerPx) }
        val world = controlPoints.map { Pt(it.inWorld dot e1, it.inWorld dot e2) }

        val planCentre = Pt(plan.sumOf { it.x } / plan.size, plan.sumOf { it.y } / plan.size)
        val worldCentre = Pt(world.sumOf { it.x } / world.size, world.sumOf { it.y } / world.size)

        // Procrustes rotation with the scale held at 1: the plot scale already set it.
        var sumCross = 0.0
        var sumDot = 0.0
        for (i in plan.indices) {
            val p = Pt(plan[i].x - planCentre.x, plan[i].y - planCentre.y)
            val w = Pt(world[i].x - worldCentre.x, world[i].y - worldCentre.y)
            sumCross += p.x * w.y - p.y * w.x
            sumDot += p.x * w.x + p.y * w.y
        }
        if (hypot(sumCross, sumDot) < 1e-9) return null
        val theta = atan2(sumCross, sumDot)

        val cosT = cos(theta)
        val sinT = sin(theta)
        val translation = Pt(
            worldCentre.x - (planCentre.x * cosT - planCentre.y * sinT),
            worldCentre.y - (planCentre.x * sinT + planCentre.y * cosT),
        )
        val height = controlPoints.sumOf { it.inWorld dot u } / controlPoints.size

        val placement = PlanPlacement(
            e1 = e1,
            e2 = e2,
            up = u,
            cosTheta = cosT,
            sinTheta = sinT,
            translation = translation,
            floorHeight = height,
            metresPerPx = metresPerPx,
        )

        var squareSum = 0.0
        for (point in controlPoints) {
            squareSum += placement.toWorld(point.onDrawing).distanceTo(point.inWorld).let { it * it }
        }
        val residual = kotlin.math.sqrt(squareSum / controlPoints.size)

        // With exactly two points the fit is exact, so the residual says nothing; the ratio of the two
        // separations is the only independent check there is.
        val impliedRatio = if (controlPoints.size == 2) {
            val drawn = controlPoints[0].onDrawing.distanceTo(controlPoints[1].onDrawing) * metresPerPx
            val measured = controlPoints[0].inWorld.distanceTo(controlPoints[1].inWorld)
            if (drawn > 1e-6) measured / drawn else null
        } else {
            null
        }

        return Result(placement, residual, impliedRatio, controlPoints.size)
    }
}

/**
 * The drawing, placed in the room: converts a point on the sheet into a point on the floor, and back.
 *
 * Height deserves its own note. A plan is a horizontal section, so it fixes *where* on the floor a
 * penetration is and says nothing at all about how high up a wall it sits. The placement therefore only
 * ever produces floor-level positions, and anything to do with height is left to the caller - which is
 * why an expected wall penetration is modelled as a vertical line rather than a point.
 */
data class PlanPlacement(
    val e1: Vec3,
    val e2: Vec3,
    val up: Vec3,
    val cosTheta: Double,
    val sinTheta: Double,
    val translation: Pt,
    /** Height of the floor the plan was registered against, along [up]. */
    val floorHeight: Double,
    val metresPerPx: Double,
) {

    /** Rotation of the plan about [up], in degrees, for display. */
    val headingDeg: Double get() = Math.toDegrees(atan2(sinTheta, cosTheta))

    /** A point on the sheet, as a point in the room at [heightAboveFloor] metres. */
    fun toWorld(onDrawing: Pt, heightAboveFloor: Double = 0.0): Vec3 {
        val px = onDrawing.x * metresPerPx
        val py = -onDrawing.y * metresPerPx
        val a = px * cosTheta - py * sinTheta + translation.x
        val b = px * sinTheta + py * cosTheta + translation.y
        return e1 * a + e2 * b + up * (floorHeight + heightAboveFloor)
    }

    /** A point in the room, as a point on the sheet. Height is discarded, since the plan has none. */
    fun toDrawing(inWorld: Vec3): Pt {
        val a = (inWorld dot e1) - translation.x
        val b = (inWorld dot e2) - translation.y
        val px = a * cosTheta + b * sinTheta
        val py = -a * sinTheta + b * cosTheta
        return Pt(px / metresPerPx, -py / metresPerPx)
    }

    /** Height of a world point above the registered floor, in metres. */
    fun heightAboveFloor(inWorld: Vec3): Double = (inWorld dot up) - floorHeight

    fun mmToMetres(mm: Double) = mm / 1000.0
}
