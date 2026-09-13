package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.GrayImage
import com.blackcode.cascoscan.detect.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How wide the light-to-dark transition at a region's boundary actually is, in pixels.
 *
 * This one number decides whether the detector is usable, because a shadow on a wall is the same shape,
 * the same size and - under a hard work lamp - very nearly the same darkness as a hole. What it cannot
 * be is *abrupt*: the edge of a void resolves in a pixel or two, while a shadow cast by an area source
 * fades over eight pixels or more.
 *
 * It is measured the way edge width is defined in optics rather than inferred: sample the intensity
 * along the outward normal at many points around the boundary, and record the distance over which it
 * rises from 20% to 80% of the local range. Estimating it instead as contrast divided by gradient - the
 * obvious shortcut - understates a soft edge badly, because the ring used for the "outside" reference
 * still sits inside the penumbra, so a twelve-pixel shadow edge measures as four.
 *
 * The median across the samples is taken, not the mean, so one occluded or overlapping neighbour cannot
 * drag the reading.
 */
object EdgeProfile {

    /** Returned when too few usable profiles were found; scoring treats it as no evidence either way. */
    const val UNKNOWN = -1.0

    private const val SAMPLES = 48
    private const val STEP = 0.5

    /** Minimum brightness range across a profile for it to say anything about edge width. */
    private const val MIN_RANGE = 12.0

    fun widthPx(ellipse: Ellipse, photo: GrayImage): Double {
        if (ellipse.semiMinor < 2.0) return UNKNOWN
        // Reach far enough to contain a soft edge, but not so far that the profile leaves the hole or
        // runs into whatever is next to it.
        val span = min(22.0, max(7.0, ellipse.semiMinor * 0.9))
        val widths = ArrayList<Double>(SAMPLES)

        for (s in 0 until SAMPLES) {
            val t = 2.0 * PI * s / SAMPLES
            val point = ellipse.pointAt(t)
            val normal = outwardNormal(ellipse, t) ?: continue
            val width = profileWidth(photo, point, normal, span)
            if (width != null) widths += width
        }
        if (widths.size < 6) return UNKNOWN
        widths.sort()
        return widths[widths.size / 2]
    }

    /**
     * Unit outward normal at ellipse parameter [t]. The gradient of the ellipse's implicit form in local
     * coordinates is proportional to (cos t / a, sin t / b), which is then rotated into image space -
     * note this is not the same direction as the radius from the centre unless the ellipse is a circle.
     */
    private fun outwardNormal(ellipse: Ellipse, t: Double): Pt? {
        if (ellipse.semiMajor <= 0.0 || ellipse.semiMinor <= 0.0) return null
        val nx = cos(t) / ellipse.semiMajor
        val ny = sin(t) / ellipse.semiMinor
        val cosA = cos(ellipse.angleRad)
        val sinA = sin(ellipse.angleRad)
        val gx = nx * cosA - ny * sinA
        val gy = nx * sinA + ny * cosA
        val length = hypot(gx, gy)
        if (length < 1e-9) return null
        return Pt(gx / length, gy / length)
    }

    /** 20%-to-80% rise distance along one normal, or null if the profile is not a usable edge. */
    private fun profileWidth(photo: GrayImage, at: Pt, normal: Pt, span: Double): Double? {
        val count = (2.0 * span / STEP).toInt() + 1
        val profile = DoubleArray(count)
        for (i in 0 until count) {
            val d = -span + i * STEP
            profile[i] = sample(photo, at.x + normal.x * d, at.y + normal.y * d) ?: return null
        }

        val edge = count / 4
        var inner = 0.0
        var outer = 0.0
        for (i in 0 until edge) inner += profile[i]
        for (i in count - edge until count) outer += profile[i]
        inner /= edge
        outer /= edge

        val range = outer - inner
        // Only a dark-inside, bright-outside step says anything here.
        if (range < MIN_RANGE) return null

        val low = inner + 0.2 * range
        val high = inner + 0.8 * range
        val lowAt = firstCrossing(profile, low) ?: return null
        val highAt = firstCrossing(profile, high) ?: return null
        if (highAt <= lowAt) return null
        return (highAt - lowAt) * STEP
    }

    /** Sub-sample index where the profile first reaches [level], linearly interpolated. */
    private fun firstCrossing(profile: DoubleArray, level: Double): Double? {
        for (i in 1 until profile.size) {
            val previous = profile[i - 1]
            val current = profile[i]
            if (previous < level && current >= level) {
                val span = current - previous
                return if (abs(span) < 1e-9) i.toDouble() else (i - 1) + (level - previous) / span
            }
        }
        return null
    }

    private fun sample(photo: GrayImage, x: Double, y: Double): Double? {
        if (x < 0.0 || y < 0.0 || x > photo.width - 2.0 || y > photo.height - 2.0) return null
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val w = photo.width
        fun at(px: Int, py: Int) = (photo.pixels[py * w + px].toInt() and 0xFF).toDouble()
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }
}
