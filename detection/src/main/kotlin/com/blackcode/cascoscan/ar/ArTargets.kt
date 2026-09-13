package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import kotlin.math.abs
import kotlin.math.max

/**
 * Where a penetration lives in the room, as far as a plan can say.
 *
 * The distinction is forced by what a drawing is. A plan is a horizontal section: it fixes where on the
 * floor something is and says **nothing** about how high up a wall it sits. So a slab penetration is a
 * point, and a wall penetration is a vertical line - and the overlay has to draw it as one, because
 * pinning a ghost circle to a specific height would be inventing information the drawing does not carry.
 */
enum class TargetGeometry {
    /** Through the floor or ceiling slab: the plan gives its position completely. */
    ON_SLAB,

    /** Through a wall: the plan gives its position along the wall, and nothing about its height. */
    ON_WALL,
}

/** One penetration the drawing requires, placed in the room. */
data class ExpectedTarget(
    val penetration: Penetration,
    val geometry: TargetGeometry,
    /** Position at floor level. For a wall target this is the foot of the vertical line. */
    val base: Vec3,
    val diameterM: Double,
) {
    val id: String get() = penetration.id
    val status: AuditStatus get() = penetration.status
}

/** The camera as ARCore reports it, in the terms projection needs. */
data class ArCamera(
    val position: Vec3,
    /** Unit axes of the camera pose. ARCore looks down its own -Z, so [forward] is the negated Z axis. */
    val right: Vec3,
    val up: Vec3,
    val forward: Vec3,
    val viewProjection: Mat4,
    val viewportWidth: Int,
    val viewportHeight: Int,
)

/** An expected target worked out into something drawable this frame. */
data class ProjectedTarget(
    val target: ExpectedTarget,
    /** Where to draw it, in viewport pixels. */
    val screen: Pt,
    /** Radius to draw, in viewport pixels, from the penetration's real diameter at its real distance. */
    val screenRadiusPx: Double,
    val distanceM: Double,
    /**
     * True when the drawing cannot say how high this one is, so the marker is placed at the viewer's own
     * height and drawn with a vertical guide rather than as a fixed point.
     */
    val heightUnknown: Boolean,
)

object ArTargets {

    /** Ignore anything further away than this: beyond it the overlay is clutter, not guidance. */
    const val DEFAULT_RANGE_M = 12.0

    /**
     * Places the penetrations a drawing requires into the room.
     *
     * @param geometry whether these are slab or wall penetrations. Taken from the auditor rather than
     *   guessed: a plan can carry both, and getting it wrong puts every marker at the wrong height.
     */
    fun build(
        penetrations: List<Penetration>,
        placement: PlanPlacement,
        geometry: (Penetration) -> TargetGeometry,
    ): List<ExpectedTarget> = penetrations.map { penetration ->
        val diameterMm = penetration.label?.declaredSizeMm ?: penetration.sizeMm?.long ?: DEFAULT_DIAMETER_MM
        ExpectedTarget(
            penetration = penetration,
            geometry = geometry(penetration),
            base = placement.toWorld(penetration.center),
            diameterM = placement.mmToMetres(diameterMm),
        )
    }

    /**
     * The targets worth drawing this frame, nearest last so the closest ends up painted on top.
     *
     * A wall target is drawn at the viewer's own height. That is not a fudge for want of a better idea:
     * the plan genuinely does not say how high the hole is, so putting the marker at eye level on the
     * correct spot along the wall shows everything that is known and nothing that is not.
     */
    fun project(
        targets: List<ExpectedTarget>,
        camera: ArCamera,
        placement: PlanPlacement,
        rangeM: Double = DEFAULT_RANGE_M,
        screenMarginPx: Double = 80.0,
    ): List<ProjectedTarget> {
        val viewerHeight = placement.heightAboveFloor(camera.position)
        val out = ArrayList<ProjectedTarget>(targets.size)

        for (target in targets) {
            val heightUnknown = target.geometry == TargetGeometry.ON_WALL
            val anchor = if (heightUnknown) {
                target.base + placement.up * viewerHeight.coerceIn(MIN_ANCHOR_HEIGHT_M, MAX_ANCHOR_HEIGHT_M)
            } else {
                target.base
            }

            val distance = anchor.distanceTo(camera.position)
            if (distance > rangeM || distance < 0.12) continue
            // Behind the camera: projecting it would put a marker on screen for a hole at your back.
            if ((anchor - camera.position) dot camera.forward <= 0.0) continue

            val screen = projectToScreen(anchor, camera) ?: continue
            if (screen.x < -screenMarginPx || screen.y < -screenMarginPx ||
                screen.x > camera.viewportWidth + screenMarginPx ||
                screen.y > camera.viewportHeight + screenMarginPx
            ) {
                continue
            }

            // Size on screen comes from the real diameter at the real distance: offset the anchor by the
            // radius across the view and measure how far that moved in pixels.
            val edge = anchor + camera.right * (target.diameterM / 2.0)
            val edgeScreen = projectToScreen(edge, camera)
            val radiusPx = edgeScreen?.let { abs(it.x - screen.x).coerceAtLeast(6.0) } ?: 12.0

            out += ProjectedTarget(target, screen, radiusPx, distance, heightUnknown)
        }
        return out.sortedByDescending { it.distanceM }
    }

    /** World point to viewport pixels, or null when it falls behind the camera. */
    fun projectToScreen(point: Vec3, camera: ArCamera): Pt? {
        val clip = camera.viewProjection.transform(point)
        val w = clip[3]
        if (w <= 1e-6) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        return Pt(
            (ndcX * 0.5 + 0.5) * camera.viewportWidth,
            // Normalised device coordinates put +y at the top; screens put it at the bottom.
            (1.0 - (ndcY * 0.5 + 0.5)) * camera.viewportHeight,
        )
    }

    private const val DEFAULT_DIAMETER_MM = 110.0

    /** Bounds on where a wall marker is floated, so a phone held low or overhead still reads sensibly. */
    private const val MIN_ANCHOR_HEIGHT_M = 0.4
    private const val MAX_ANCHOR_HEIGHT_M = 2.4

    /** Largest sensible on-screen radius, to keep a marker a metre from the lens from filling the view. */
    internal fun clampRadius(radiusPx: Double, viewportWidth: Int) =
        radiusPx.coerceAtMost(max(24.0, viewportWidth * 0.4))
}

/**
 * Turning pixels into millimetres using the depth augmented reality already knows.
 *
 * This is the quiet advantage of doing the job in AR rather than from a photograph. Measuring a hole in a
 * still picture needs the scale supplied by hand - two taps on something of known length, once per shot,
 * because millimetres per pixel depends entirely on how far away you were standing. In a tracked session
 * that distance is already known: ARCore reports where the surface is, the camera reports its own focal
 * length in pixels, and the rest is similar triangles. No calibration step, and it re-derives itself
 * correctly every time the auditor moves.
 */
object ArPhotogrammetry {

    /**
     * Real size of something [pixels] across, on a surface [distanceM] away, seen through a lens whose
     * focal length is [focalLengthPx] **in the same pixels the measurement was made in**.
     *
     * The pinhole relation is `size / distance = pixels / focalLength`. The caller must scale the focal
     * length to the image the measurement came from: detection works on a reduced copy, and using the
     * full-resolution focal length against reduced pixels would understate every diameter by the
     * reduction factor.
     */
    fun sizeMm(pixels: Double, distanceM: Double, focalLengthPx: Double): Double? {
        if (pixels <= 0.0 || distanceM <= 0.0 || focalLengthPx <= 0.0) return null
        return pixels * distanceM / focalLengthPx * 1000.0
    }

    /** Focal length expressed in the pixels of an image reduced by [downsampleFactor]. */
    fun focalLengthFor(fullImageFocalLengthPx: Double, downsampleFactor: Int): Double {
        require(downsampleFactor >= 1)
        return fullImageFocalLengthPx / downsampleFactor
    }

    /**
     * How much a millimetre of error in the surface distance costs, as a fraction: the relation is linear,
     * so a 5% error in depth is a 5% error in diameter. Used to decide whether a size is worth reporting.
     */
    fun relativeSizeErrorFor(distanceM: Double, depthErrorM: Double): Double =
        if (distanceM <= 0.0) 1.0 else (depthErrorM / distanceM).coerceIn(0.0, 1.0)
}
