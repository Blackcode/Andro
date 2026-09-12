package com.blackcode.cascoscan.detect

import kotlin.math.max
import kotlin.math.min

/**
 * 8-bit greyscale page raster, 0 = black ink, 255 = white paper.
 *
 * The Android side renders a PDF page into a Bitmap and converts it here; the engine itself never
 * touches an Android type, which is what keeps it testable on a plain JVM.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    fun getOrWhite(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 255 else this[x, y]

    fun crop(box: IBox): GrayImage {
        val l = box.left.coerceIn(0, width - 1)
        val t = box.top.coerceIn(0, height - 1)
        val r = box.right.coerceIn(l, width - 1)
        val b = box.bottom.coerceIn(t, height - 1)
        val w = r - l + 1
        val h = b - t + 1
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(pixels, (t + y) * width + l, out, y * w, w)
        }
        return GrayImage(w, h, out)
    }

    /** Box-average downsample by an integer factor; used for the coarse structural-line pass. */
    fun downsample(factor: Int): GrayImage {
        require(factor >= 1)
        if (factor == 1) return this
        val w = max(1, width / factor)
        val h = max(1, height / factor)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (dy in 0 until factor) {
                    val sy = y * factor + dy
                    if (sy >= height) break
                    for (dx in 0 until factor) {
                        val sx = x * factor + dx
                        if (sx >= width) break
                        sum += this[sx, sy]
                        n++
                    }
                }
                out[y * w + x] = (sum / max(1, n)).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    companion object {
        /** Converts packed ARGB (the layout of `Bitmap.getPixels`) to luminance. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            require(argb.size >= width * height) { "pixel array too small" }
            val out = ByteArray(width * height)
            for (i in 0 until width * height) {
                val p = argb[i]
                val a = (p ushr 24) and 0xFF
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                // Rec. 601 luma; transparent pixels are paper, not ink.
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                out[i] = (if (a < 16) 255 else lum).toByte()
            }
            return GrayImage(width, height, out)
        }

        fun white(width: Int, height: Int) = GrayImage(width, height, ByteArray(width * height) { -1 })
    }
}

/** Ink mask: `true` where the page has ink. */
class BinaryImage(val width: Int, val height: Int, val ink: BooleanArray) {

    init {
        require(ink.size == width * height) { "expected ${width * height} pixels, got ${ink.size}" }
    }

    operator fun get(x: Int, y: Int): Boolean = ink[y * width + x]

    fun isInk(x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && ink[y * width + x]

    val inkCount: Int get() = ink.count { it }

    fun inkFraction(): Double = inkCount.toDouble() / ink.size

    /** Grows the mask by [radius] using the (separable) Chebyshev structuring element. */
    fun dilate(radius: Int): BinaryImage {
        if (radius <= 0) return this
        val tmp = BooleanArray(ink.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                val lo = max(0, x - radius)
                val hi = min(width - 1, x + radius)
                for (xx in lo..hi) tmp[y * width + xx] = true
            }
        }
        val out = BooleanArray(ink.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!tmp[y * width + x]) continue
                val lo = max(0, y - radius)
                val hi = min(height - 1, y + radius)
                for (yy in lo..hi) out[yy * width + x] = true
            }
        }
        return BinaryImage(width, height, out)
    }

    /** Shrinks the mask by [radius]; pixels outside the image count as unset. */
    fun erode(radius: Int): BinaryImage {
        if (radius <= 0) return this
        val tmp = BooleanArray(ink.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var all = true
                var xx = x - radius
                while (xx <= x + radius) {
                    if (xx < 0 || xx >= width || !ink[y * width + xx]) {
                        all = false
                        break
                    }
                    xx++
                }
                tmp[y * width + x] = all
            }
        }
        val out = BooleanArray(ink.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var all = true
                var yy = y - radius
                while (yy <= y + radius) {
                    if (yy < 0 || yy >= height || !tmp[yy * width + x]) {
                        all = false
                        break
                    }
                    yy++
                }
                out[y * width + x] = all
            }
        }
        return BinaryImage(width, height, out)
    }

    fun or(other: BinaryImage): BinaryImage {
        require(other.width == width && other.height == height)
        return BinaryImage(width, height, BooleanArray(ink.size) { ink[it] || other.ink[it] })
    }

    fun andNot(other: BinaryImage): BinaryImage {
        require(other.width == width && other.height == height)
        return BinaryImage(width, height, BooleanArray(ink.size) { ink[it] && !other.ink[it] })
    }
}
