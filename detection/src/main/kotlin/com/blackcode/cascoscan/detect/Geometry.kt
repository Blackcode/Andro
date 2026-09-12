package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point in page-pixel space (origin top-left, y down), the coordinate system of a rendered page. */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(f: Double) = Pt(x * f, y * f)
    fun distanceTo(o: Pt) = hypot(x - o.x, y - o.y)
    val length: Double get() = hypot(x, y)
}

/** Inclusive integer bounding box in pixel space. */
data class IBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left && bottom >= top) { "degenerate box $this" }
    }

    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
    val area: Int get() = width * height
    val longSide: Int get() = max(width, height)
    val shortSide: Int get() = min(width, height)
    val center: Pt get() = Pt(left + (width - 1) / 2.0, top + (height - 1) / 2.0)

    /** min(w,h)/max(w,h) in 0..1; 1.0 is square. */
    val squareness: Double get() = if (longSide == 0) 1.0 else shortSide.toDouble() / longSide

    operator fun contains(p: Pt): Boolean =
        p.x >= left && p.x <= right && p.y >= top && p.y <= bottom

    fun contains(other: IBox): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    fun intersects(other: IBox): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom

    fun intersection(other: IBox): IBox? {
        if (!intersects(other)) return null
        return IBox(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))
    }

    fun intersectionOverUnion(other: IBox): Double {
        val inter = intersection(other)?.area ?: return 0.0
        return inter.toDouble() / (area + other.area - inter)
    }

    fun expand(by: Int, clampW: Int = Int.MAX_VALUE, clampH: Int = Int.MAX_VALUE): IBox = IBox(
        max(0, left - by),
        max(0, top - by),
        min(clampW - 1, right + by).coerceAtLeast(max(0, left - by)),
        min(clampH - 1, bottom + by).coerceAtLeast(max(0, top - by)),
    )

    fun union(other: IBox) = IBox(
        min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom),
    )

    companion object {
        fun around(center: Pt, halfWidth: Double, halfHeight: Double): IBox = IBox(
            (center.x - halfWidth).roundToInt(),
            (center.y - halfHeight).roundToInt(),
            (center.x + halfWidth).roundToInt(),
            (center.y + halfHeight).roundToInt(),
        )
    }
}

/**
 * A similarity transform (uniform scale + rotation + translation) mapping one page's pixel space
 * onto another's. Used to overlay drawings of different disciplines or scales on each other.
 *
 * Stored in the "a/b" form so that composing and inverting stays exact:
 *   x' = a*x - b*y + tx
 *   y' = b*x + a*y + ty
 */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {

    val scale: Double get() = hypot(a, b)
    val rotationRad: Double get() = kotlin.math.atan2(b, a)

    fun apply(p: Pt) = Pt(a * p.x - b * p.y + tx, b * p.x + a * p.y + ty)

    fun inverse(): Similarity {
        val det = a * a + b * b
        require(det > 1e-12) { "non-invertible transform $this" }
        val ia = a / det
        val ib = -b / det
        return Similarity(ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty))
    }

    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0, 0.0)

        /**
         * Least-squares similarity fit from corresponding point pairs (closed form, exact for 2 pairs).
         * Returns null when the source points are coincident.
         */
        fun fit(from: List<Pt>, to: List<Pt>): Similarity? {
            require(from.size == to.size) { "mismatched correspondence lists" }
            if (from.size < 2) return null
            val n = from.size
            val cf = Pt(from.sumOf { it.x } / n, from.sumOf { it.y } / n)
            val ct = Pt(to.sumOf { it.x } / n, to.sumOf { it.y } / n)
            var sxx = 0.0
            var sxy = 0.0
            var norm = 0.0
            for (i in 0 until n) {
                val f = from[i] - cf
                val t = to[i] - ct
                sxx += f.x * t.x + f.y * t.y
                sxy += f.x * t.y - f.y * t.x
                norm += f.x * f.x + f.y * f.y
            }
            if (norm < 1e-9) return null
            val a = sxx / norm
            val b = sxy / norm
            if (abs(a) < 1e-12 && abs(b) < 1e-12) return null
            return Similarity(a, b, ct.x - (a * cf.x - b * cf.y), ct.y - (b * cf.x + a * cf.y))
        }
    }
}
