package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A tiny rasteriser for building synthetic drawing sheets in tests.
 *
 * Real casco drawings cannot be committed to a repository (they are client property and they are
 * huge), so the test suite draws its own: walls as long filled bars, sleeves as rings, openings as
 * hatched rectangles, plus the kinds of clutter that produce false positives. Because the generator
 * knows where it put everything, the tests can assert both recall and precision.
 */
class DrawingCanvas(val width: Int, val height: Int) {

    private val pixels = ByteArray(width * height) { -1 } // 0xFF = white paper

    fun toImage() = GrayImage(width, height, pixels.copyOf())

    private fun put(x: Int, y: Int, value: Int = 0) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = value.toByte()
    }

    fun fillRect(left: Int, top: Int, right: Int, bottom: Int, value: Int = 0) {
        for (y in max(0, top)..min(height - 1, bottom)) {
            for (x in max(0, left)..min(width - 1, right)) put(x, y, value)
        }
    }

    fun strokeRect(left: Int, top: Int, right: Int, bottom: Int, thickness: Int = 1) {
        for (t in 0 until thickness) {
            for (x in left..right) {
                put(x, top + t)
                put(x, bottom - t)
            }
            for (y in top..bottom) {
                put(left + t, y)
                put(right - t, y)
            }
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int = 1) {
        // Bresenham, thickened by stamping a square brush.
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        val half = thickness / 2
        while (true) {
            for (by in -half..half) for (bx in -half..half) put(x + bx, y + by)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
        }
    }

    /** Ring of radius [r], stroke [thickness], centred on ([cx],[cy]). */
    fun circle(cx: Int, cy: Int, r: Int, thickness: Int = 1) {
        val outer = r + thickness / 2.0
        val inner = r - thickness / 2.0 - (if (thickness == 1) 0.5 else 0.0)
        for (y in (cy - r - thickness)..(cy + r + thickness)) {
            for (x in (cx - r - thickness)..(cx + r + thickness)) {
                val d = hypot((x - cx).toDouble(), (y - cy).toDouble())
                if (d <= outer && d >= inner) put(x, y)
            }
        }
    }

    fun disc(cx: Int, cy: Int, r: Int) {
        for (y in (cy - r)..(cy + r)) {
            for (x in (cx - r)..(cx + r)) {
                if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r) put(x, y)
            }
        }
    }

    /** Parallel 45-degree hatching clipped to a rectangle, [spacing] pixels apart. */
    fun hatchRect(left: Int, top: Int, right: Int, bottom: Int, spacing: Int = 5) {
        var c = left - (bottom - top)
        while (c <= right) {
            var x = c
            var y = top
            while (x <= right && y <= bottom) {
                if (x >= left) put(x, y)
                x++
                y++
            }
            c += spacing
        }
    }

    /** A ring with an X through it: the common "sparing" symbol. */
    fun crossedCircle(cx: Int, cy: Int, r: Int, thickness: Int = 1) {
        circle(cx, cy, r, thickness)
        val d = (r / kotlin.math.sqrt(2.0)).roundToInt()
        line(cx - d, cy - d, cx + d, cy + d, thickness)
        line(cx - d, cy + d, cx + d, cy - d, thickness)
    }

    fun crossedRect(left: Int, top: Int, right: Int, bottom: Int, thickness: Int = 1) {
        strokeRect(left, top, right, bottom, thickness)
        line(left, top, right, bottom, thickness)
        line(left, bottom, right, top, thickness)
    }

    /**
     * Text-like clutter: irregular vertical strokes of about [heightPx], the shape of drawing
     * annotation as far as the detector can tell. Used to check that dimension text and room names
     * are not reported as penetrations.
     */
    fun glyphRun(x: Int, y: Int, count: Int, heightPx: Int = 10, seed: Int = 7) {
        var state = seed
        fun next(bound: Int): Int {
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            return state % bound
        }
        var cx = x
        for (i in 0 until count) {
            val w = 3 + next(3)
            val h = heightPx - next(3)
            when (next(4)) {
                0 -> { line(cx, y, cx, y + h); line(cx, y, cx + w, y) }
                1 -> { line(cx, y, cx, y + h); line(cx, y + h / 2, cx + w, y + h / 2) }
                2 -> { line(cx, y, cx + w, y + h); line(cx + w, y, cx, y + h) }
                else -> { strokeRect(cx, y, cx + w, y + h) }
            }
            cx += w + 2 + next(2)
        }
    }
}
