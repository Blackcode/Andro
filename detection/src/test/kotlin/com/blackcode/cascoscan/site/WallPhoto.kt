package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.GrayImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A synthetic photograph of a casco wall, with the things that fool a hole detector.
 *
 * Photographs of real buildings cannot go in a repository, so the suite paints its own. What matters is
 * that it contains the *confusions* rather than just the targets: a shadow the same size, darkness and
 * shape as a hole, a construction joint, a damp stain, and lighting that falls off across the frame.
 * A detector that only ever sees holes on flat grey will pass any test and fail on site.
 *
 * The one parameter that decides whether the test is honest is the edge transition width. A hole's edge
 * is not a step - a lens softens it to a pixel or two - so holes are drawn with a 1.4 px transition, and
 * shadows with 12 px, which is the difference the detector is supposed to key on.
 */
class WallPhoto(val width: Int, val height: Int, seed: Int = 20260913) {

    /** Working buffer in floating point, so repeated shading does not quantise. */
    private val pixels = DoubleArray(width * height)
    private var state = seed

    init {
        val texture = correlatedNoise()
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Concrete grey, with the light falling off towards one corner the way a single work
                // lamp or a window actually lights a shell.
                val across = x.toDouble() / width
                val down = y.toDouble() / height
                val illumination = 196.0 - 54.0 * across - 26.0 * down
                pixels[y * width + x] = (illumination + texture[y * width + x]).coerceIn(0.0, 255.0)
            }
        }
    }

    fun toImage(): GrayImage {
        val out = ByteArray(width * height)
        for (i in out.indices) out[i] = pixels[i].toInt().coerceIn(0, 255).toByte()
        return GrayImage(width, height, out)
    }

    /** A cored or cast hole: dark, hard-edged. [rx] is the semi-axis along the hole's own direction. */
    fun hole(cx: Double, cy: Double, rx: Double, ry: Double, angleDeg: Double = 0.0, interior: Double = 26.0) {
        paintEllipse(cx, cy, rx, ry, angleDeg, transition = HARD_EDGE) { base, cover ->
            base * (1 - cover) + interior * cover
        }
    }

    /** A hole with a pale sleeve collar around its mouth. */
    fun sleevedHole(cx: Double, cy: Double, rx: Double, angleDeg: Double = 0.0) {
        // Collar first, then the bore, so the dark centre sits inside the bright ring.
        paintEllipse(cx, cy, rx * 1.28, rx * 1.28, angleDeg, transition = HARD_EDGE) { base, cover ->
            base * (1 - cover) + 226.0 * cover
        }
        hole(cx, cy, rx, rx, angleDeg, interior = 22.0)
    }

    /** A rectangular opening, hard-edged, optionally rotated. */
    fun rectOpening(cx: Double, cy: Double, w: Double, h: Double, angleDeg: Double = 0.0, interior: Double = 26.0) {
        paintRect(cx, cy, w, h, angleDeg, transition = HARD_EDGE) { base, cover ->
            base * (1 - cover) + interior * cover
        }
    }

    /**
     * A shadow: the same shape and darkness as a hole, but its edge is soft and it *multiplies* the wall
     * rather than replacing it, so the texture still shows through. Both are true of real shadows and
     * both are what should give it away.
     */
    fun shadow(cx: Double, cy: Double, rx: Double, ry: Double, angleDeg: Double = 0.0, strength: Double = 0.80) {
        paintEllipse(cx, cy, rx, ry, angleDeg, transition = SOFT_EDGE) { base, cover ->
            base * (1.0 - strength * cover)
        }
    }

    /** A construction joint or crack: long, thin, dark, hard-edged. */
    fun crack(x0: Double, y0: Double, x1: Double, y1: Double, thickness: Double = 3.0) {
        val steps = (hypot(x1 - x0, y1 - y0) * 2).toInt().coerceAtLeast(2)
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            paintEllipse(x, y, thickness / 2, thickness / 2, 0.0, transition = HARD_EDGE) { base, cover ->
                base * (1 - cover) + 42.0 * cover
            }
        }
    }

    /** A damp patch: soft-edged and lumpy, so neither convex nor sharp. */
    fun stain(cx: Double, cy: Double, radius: Double, strength: Double = 0.42) {
        // A handful of overlapping soft blobs, which is what gives it a ragged outline.
        for (i in 0 until 7) {
            val angle = nextDouble() * 2 * PI
            val distance = radius * (0.35 + nextDouble() * 0.75)
            paintEllipse(
                cx + cos(angle) * distance,
                cy + sin(angle) * distance,
                radius * (0.45 + nextDouble() * 0.4),
                radius * (0.45 + nextDouble() * 0.4),
                nextDouble() * 180,
                transition = SOFT_EDGE,
            ) { base, cover -> base * (1.0 - strength * cover) }
        }
    }

    private inline fun paintEllipse(
        cx: Double,
        cy: Double,
        rx: Double,
        ry: Double,
        angleDeg: Double,
        transition: Double,
        blend: (base: Double, cover: Double) -> Double,
    ) {
        val angle = Math.toRadians(angleDeg)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val reach = max(rx, ry) + transition + 2
        val left = max(0, (cx - reach).toInt())
        val right = min(width - 1, (cx + reach).toInt())
        val top = max(0, (cy - reach).toInt())
        val bottom = min(height - 1, (cy + reach).toInt())
        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x - cx
                val dy = y - cy
                val u = (dx * cosA + dy * sinA) / rx
                val v = (-dx * sinA + dy * cosA) / ry
                val r = hypot(u, v)
                // Convert the normalised radius into an approximate pixel distance from the boundary,
                // so the transition width means the same thing at any size.
                val edgeDistance = (1.0 - r) * min(rx, ry)
                val cover = smoothStep(edgeDistance, transition)
                if (cover <= 0.0) continue
                val index = y * width + x
                pixels[index] = blend(pixels[index], cover).coerceIn(0.0, 255.0)
            }
        }
    }

    private inline fun paintRect(
        cx: Double,
        cy: Double,
        w: Double,
        h: Double,
        angleDeg: Double,
        transition: Double,
        blend: (base: Double, cover: Double) -> Double,
    ) {
        val angle = Math.toRadians(angleDeg)
        val cosA = cos(angle)
        val sinA = sin(angle)
        val reach = hypot(w, h) / 2 + transition + 2
        val left = max(0, (cx - reach).toInt())
        val right = min(width - 1, (cx + reach).toInt())
        val top = max(0, (cy - reach).toInt())
        val bottom = min(height - 1, (cy + reach).toInt())
        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x - cx
                val dy = y - cy
                val u = abs(dx * cosA + dy * sinA)
                val v = abs(-dx * sinA + dy * cosA)
                val edgeDistance = min(w / 2 - u, h / 2 - v)
                val cover = smoothStep(edgeDistance, transition)
                if (cover <= 0.0) continue
                val index = y * width + x
                pixels[index] = blend(pixels[index], cover).coerceIn(0.0, 255.0)
            }
        }
    }

    /** 0 outside, 1 well inside, smooth across a band of [transition] pixels centred on the boundary. */
    private fun smoothStep(edgeDistance: Double, transition: Double): Double {
        if (transition <= 0.0) return if (edgeDistance >= 0) 1.0 else 0.0
        val t = ((edgeDistance + transition / 2) / transition).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Spatially correlated grain: white noise blurred twice, which is what concrete looks like. */
    private fun correlatedNoise(): DoubleArray {
        var field = DoubleArray(width * height) { (nextDouble() - 0.5) * 30.0 }
        repeat(2) { field = blur(field) }
        // Blurring removes most of the amplitude; restore some and add fine grain on top.
        return DoubleArray(width * height) { field[it] * 3.2 + (nextDouble() - 0.5) * 5.0 }
    }

    private fun blur(source: DoubleArray): DoubleArray {
        val out = DoubleArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0
                var n = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue
                        sum += source[yy * width + xx]
                        n++
                    }
                }
                out[y * width + x] = sum / n
            }
        }
        return out
    }

    private fun nextDouble(): Double {
        state = (state * 1103515245 + 12345) and 0x7FFFFFFF
        return state / 0x7FFFFFFF.toDouble()
    }

    companion object {
        /** Edge of a void, softened only by the lens. */
        const val HARD_EDGE = 1.4

        /** Edge of a shadow from an area light source. */
        const val SOFT_EDGE = 12.0
    }
}
