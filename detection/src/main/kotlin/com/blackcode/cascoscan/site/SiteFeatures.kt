package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.Component
import com.blackcode.cascoscan.detect.GrayImage
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.Pt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Measures one dark region against the photograph it came from. */
object SiteFeatureExtractor {

    // Rings are multiples of the fitted ellipse rather than fixed pixel widths, so they scale with the
    // hole. The far ring has to clear a sleeve collar - which is typically 1.2 to 1.4 times the bore -
    // or a collar fills both rings, they read the same, and the sleeve becomes invisible.
    private const val NEAR_RING_FROM = 1.02
    private const val NEAR_RING_TO = 1.18
    private const val FAR_RING_FROM = 1.50
    private const val FAR_RING_TO = 2.00

    fun extract(component: Component, photo: GrayImage): SiteFeatures? {
        val ellipse = Ellipse.fromMoments(component.pixels, component.imageWidth) ?: return null
        val box = component.box
        val w = photo.width
        val h = photo.height

        // Membership lookup over the region, for the overlap measures and the ring sampling.
        val inRegion = HashSet<Int>(component.pixels.size * 2)
        for (p in component.pixels) inRegion += p

        // --- Shape: overlap with the fitted ellipse, and with the oriented box. ---
        val search = box.expand(2, w, h)
        var ellipseOnly = 0
        var regionOnly = 0
        var both = 0
        for (y in search.top..search.bottom) {
            for (x in search.left..search.right) {
                val isRegion = (y * w + x) in inRegion
                val isEllipse = ellipse.normalisedRadius(Pt(x.toDouble(), y.toDouble())) <= 1.0
                when {
                    isRegion && isEllipse -> both++
                    isRegion -> regionOnly++
                    isEllipse -> ellipseOnly++
                }
            }
        }
        val ellipseIou = if (both + regionOnly + ellipseOnly == 0) {
            0.0
        } else {
            both.toDouble() / (both + regionOnly + ellipseOnly)
        }
        val boxIou = orientedBoxIou(component, ellipse, inRegion, w)

        // --- Solidity against the convex hull of the boundary. ---
        val boundary = boundaryPixels(component, inRegion, w)
        val hull = ConvexHull.of(boundary.map { Pt((it % w).toDouble(), (it / w).toDouble()) })
        val hullArea = ConvexHull.area(hull)
        val solidity = if (hullArea <= 0.0) 0.0 else (component.pixels.size / hullArea).coerceAtMost(1.0)

        // --- Brightness inside, just outside, and further out. ---
        var interiorSum = 0.0
        for (p in component.pixels) interiorSum += photo.pixels[p].toInt() and 0xFF
        val interiorMean = interiorSum / component.pixels.size

        val nearMean = ringMean(ellipse, photo, inRegion, NEAR_RING_FROM, NEAR_RING_TO)
        val farMean = ringMean(ellipse, photo, inRegion, FAR_RING_FROM, FAR_RING_TO)
        val surroundMean = if (farMean > 0.0) farMean else nearMean
        val ringContrast = if (nearMean > 0.0 && farMean > 0.0) abs(nearMean - farMean) else 0.0

        // --- Edge width, measured directly across the boundary. ---
        val edgeTransitionPx = EdgeProfile.widthPx(ellipse, photo)

        return SiteFeatures(
            box = box,
            pixelCount = component.pixels.size,
            ellipse = ellipse,
            ellipseIou = ellipseIou,
            boxIou = boxIou,
            solidity = solidity,
            interiorMean = interiorMean,
            surroundMean = surroundMean,
            edgeTransitionPx = edgeTransitionPx,
            ringContrast = ringContrast,
            touchesFrameEdge = box.left <= 1 || box.top <= 1 || box.right >= w - 2 || box.bottom >= h - 2,
        )
    }

    /**
     * Overlap between the region and the smallest box aligned with the ellipse's own axes. A solid
     * rectangle scores near 1 here and about 0.79 on [ellipseIou]; a solid ellipse does the reverse.
     */
    private fun orientedBoxIou(
        component: Component,
        ellipse: Ellipse,
        inRegion: Set<Int>,
        imageWidth: Int,
    ): Double {
        val cosA = cos(ellipse.angleRad)
        val sinA = sin(ellipse.angleRad)
        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (p in component.pixels) {
            val dx = (p % imageWidth) - ellipse.center.x
            val dy = (p / imageWidth) - ellipse.center.y
            val u = dx * cosA + dy * sinA
            val v = -dx * sinA + dy * cosA
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val boxArea = (maxU - minU + 1) * (maxV - minV + 1)
        if (boxArea <= 0.0) return 0.0
        // The region is entirely inside its own oriented box, so the intersection is the region.
        return (component.pixels.size / boxArea).coerceIn(0.0, 1.0)
    }

    private fun boundaryPixels(component: Component, inRegion: Set<Int>, imageWidth: Int): List<Int> {
        val out = ArrayList<Int>()
        for (p in component.pixels) {
            val isEdge = (p - 1) !in inRegion || (p + 1) !in inRegion ||
                (p - imageWidth) !in inRegion || (p + imageWidth) !in inRegion
            if (isEdge) out += p
        }
        return out
    }

    /**
     * Mean intensity of an elliptical annulus around the region, skipping anything that belongs to the
     * region itself, so a neighbouring hole in the ring does not drag the wall reference down.
     */
    private fun ringMean(
        ellipse: Ellipse,
        photo: GrayImage,
        inRegion: Set<Int>,
        from: Double,
        to: Double,
    ): Double {
        val reach = (ellipse.semiMajor * to).toInt() + 2
        val left = max(0, (ellipse.center.x - reach).toInt())
        val right = min(photo.width - 1, (ellipse.center.x + reach).toInt())
        val top = max(0, (ellipse.center.y - reach).toInt())
        val bottom = min(photo.height - 1, (ellipse.center.y + reach).toInt())
        var sum = 0.0
        var n = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * photo.width + x
                if (index in inRegion) continue
                val r = ellipse.normalisedRadius(Pt(x.toDouble(), y.toDouble()))
                if (r < from || r > to) continue
                sum += photo.pixels[index].toInt() and 0xFF
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }
}
