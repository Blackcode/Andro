package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ellipse that best describes a blob, and what it tells you about the hole that cast it.
 *
 * The geometric insight this whole measurement rests on: a circular hole photographed off-axis projects
 * to an ellipse whose **major axis is still the true diameter**. Tilting the camera foreshortens the
 * hole along one direction only, so the minor axis shrinks by cos(tilt) while the major axis is
 * untouched. That means a usable diameter can be read from a photo taken from wherever the auditor
 * happened to be standing, and the ratio of the axes recovers how far off-axis they were - which is
 * itself worth knowing, because past about sixty degrees the reading stops being trustworthy.
 *
 * (Strictly this holds for a parallel projection. Across something as small as a sleeve at normal
 * standing distance the perspective error is far below the draughting tolerance we compare against.)
 */
data class Ellipse(
    val center: Pt,
    /** Semi-major axis in pixels. */
    val semiMajor: Double,
    /** Semi-minor axis in pixels. */
    val semiMinor: Double,
    /** Orientation of the major axis, radians, measured from the +x axis. */
    val angleRad: Double,
) {
    val area: Double get() = PI * semiMajor * semiMinor

    /** 1.0 face-on, falling towards 0 as the camera moves off-axis. */
    val axisRatio: Double get() = if (semiMajor <= 0.0) 0.0 else semiMinor / semiMajor

    /** How far off the hole's axis the camera was, in degrees, assuming the hole is round. */
    val tiltDeg: Double get() = Math.toDegrees(acos(axisRatio.coerceIn(0.0, 1.0)))

    /** True diameter of a round hole, in pixels: the major axis, unaffected by the viewing angle. */
    val diameterPx: Double get() = 2.0 * semiMajor

    /** Point on the ellipse at parameter [t] in radians. */
    fun pointAt(t: Double): Pt {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val x = semiMajor * cos(t)
        val y = semiMinor * sin(t)
        return Pt(center.x + x * cosA - y * sinA, center.y + x * sinA + y * cosA)
    }

    /**
     * Algebraic distance of a point from the ellipse: 0 on the curve, <1 inside, >1 outside. Used to
     * measure how well a blob's boundary actually follows the fitted ellipse.
     */
    fun normalisedRadius(p: Pt): Double {
        if (semiMajor <= 0.0 || semiMinor <= 0.0) return Double.MAX_VALUE
        val dx = p.x - center.x
        val dy = p.y - center.y
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val u = (dx * cosA + dy * sinA) / semiMajor
        val v = (-dx * sinA + dy * cosA) / semiMinor
        return sqrt(u * u + v * v)
    }

    companion object {

        /**
         * The equivalent ellipse of inertia: the uniform ellipse with the same area and the same
         * second moments as the region.
         *
         * For a solid ellipse with semi-axes a and b the central moments are mu20 = a^2/4 and
         * mu02 = b^2/4, so the eigenvalues of the covariance matrix give the axes back as 2*sqrt(lambda).
         * Moments use every pixel rather than just the outline, which makes this far steadier on a
         * ragged, noisy boundary than fitting a conic to edge points would be.
         */
        fun fromMoments(pixels: IntArray, imageWidth: Int): Ellipse? {
            if (pixels.size < 5) return null
            var sumX = 0.0
            var sumY = 0.0
            for (p in pixels) {
                sumX += (p % imageWidth).toDouble()
                sumY += (p / imageWidth).toDouble()
            }
            val n = pixels.size.toDouble()
            val cx = sumX / n
            val cy = sumY / n

            var mu20 = 0.0
            var mu02 = 0.0
            var mu11 = 0.0
            for (p in pixels) {
                val dx = (p % imageWidth) - cx
                val dy = (p / imageWidth) - cy
                mu20 += dx * dx
                mu02 += dy * dy
                mu11 += dx * dy
            }
            mu20 /= n
            mu02 /= n
            mu11 /= n

            // A single pixel row has zero spread on one axis; +1/12 is the variance of a unit square,
            // which keeps a thin region from producing a degenerate (zero-width) ellipse.
            mu20 += 1.0 / 12.0
            mu02 += 1.0 / 12.0

            val common = sqrt((mu20 - mu02) * (mu20 - mu02) + 4.0 * mu11 * mu11)
            val lambda1 = (mu20 + mu02 + common) / 2.0
            val lambda2 = (mu20 + mu02 - common) / 2.0
            if (lambda1 <= 0.0) return null
            val a = 2.0 * sqrt(lambda1)
            val b = 2.0 * sqrt(lambda2.coerceAtLeast(0.0))
            val angle = 0.5 * atan2(2.0 * mu11, mu20 - mu02)
            return Ellipse(Pt(cx, cy), a, b, angle)
        }
    }
}

/** Convex-hull utilities, for telling a convex hole from a ragged stain of the same size. */
object ConvexHull {

    /** Andrew's monotone chain. Returns the hull in counter-clockwise order. */
    fun of(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<Pt>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0.0) {
                lower.removeAt(lower.size - 1)
            }
            lower += p
        }
        val upper = ArrayList<Pt>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0.0) {
                upper.removeAt(upper.size - 1)
            }
            upper += p
        }
        // Both chains include the two extreme points; drop the duplicates.
        return lower.dropLast(1) + upper.dropLast(1)
    }

    fun area(polygon: List<Pt>): Double {
        if (polygon.size < 3) return 0.0
        var sum = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    private fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}
