package com.blackcode.cascoscan.detect

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ink/paper separation.
 *
 * Vector PDFs exported from CAD are near-bitonal, so a global Otsu cut is both fastest and safest.
 * Scans and photographed drawings carry shading and JPEG mush, where a local (Sauvola) threshold
 * keeps thin lines that a global cut would erase. [Binarize.auto] picks between them by measuring
 * how bimodal the page histogram actually is.
 */
object Binarize {

    data class Result(val mask: BinaryImage, val method: String, val threshold: Int)

    fun histogram(image: GrayImage): IntArray {
        val h = IntArray(256)
        for (b in image.pixels) h[b.toInt() and 0xFF]++
        return h
    }

    /** Classic Otsu: the threshold maximising between-class variance. */
    fun otsuThreshold(image: GrayImage): Int {
        val hist = histogram(image)
        val total = image.pixels.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    fun global(image: GrayImage, threshold: Int): BinaryImage {
        val ink = BooleanArray(image.pixels.size)
        for (i in ink.indices) ink[i] = (image.pixels[i].toInt() and 0xFF) <= threshold
        return BinaryImage(image.width, image.height, ink)
    }

    /**
     * Sauvola local threshold: `t(x,y) = mean * (1 + k * (sd/r - 1))`, computed in O(1) per pixel
     * from integral images. [window] is forced odd so the window is centred.
     */
    fun sauvola(image: GrayImage, window: Int, k: Double = 0.2, r: Double = 128.0): BinaryImage {
        val w = image.width
        val h = image.height
        val win = (if (window % 2 == 0) window + 1 else window).coerceIn(3, max(3, min(w, h)))
        val rad = win / 2

        // Integral images are (w+1)x(h+1) so the inclusive-box sum needs no bounds special-casing.
        val sum = LongArray((w + 1) * (h + 1))
        val sumSq = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            var rowSumSq = 0L
            for (x in 0 until w) {
                val v = (image.pixels[y * w + x].toInt() and 0xFF).toLong()
                rowSum += v
                rowSumSq += v * v
                sum[(y + 1) * (w + 1) + (x + 1)] = sum[y * (w + 1) + (x + 1)] + rowSum
                sumSq[(y + 1) * (w + 1) + (x + 1)] = sumSq[y * (w + 1) + (x + 1)] + rowSumSq
            }
        }

        fun boxSum(acc: LongArray, x0: Int, y0: Int, x1: Int, y1: Int): Long {
            val stride = w + 1
            return acc[(y1 + 1) * stride + (x1 + 1)] - acc[y0 * stride + (x1 + 1)] -
                acc[(y1 + 1) * stride + x0] + acc[y0 * stride + x0]
        }

        val ink = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - rad)
            val y1 = min(h - 1, y + rad)
            for (x in 0 until w) {
                val x0 = max(0, x - rad)
                val x1 = min(w - 1, x + rad)
                val n = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                val s = boxSum(sum, x0, y0, x1, y1).toDouble()
                val sq = boxSum(sumSq, x0, y0, x1, y1).toDouble()
                val mean = s / n
                val variance = (sq / n - mean * mean).coerceAtLeast(0.0)
                val sd = sqrt(variance)
                val t = mean * (1.0 + k * (sd / r - 1.0))
                ink[y * w + x] = (image.pixels[y * w + x].toInt() and 0xFF) <= t
            }
        }
        return BinaryImage(w, h, ink)
    }

    /**
     * Chooses a method from the page histogram. A CAD export puts nearly all its mass in the two
     * extreme bins; a scan spreads it out. The midtone fraction is the discriminator.
     */
    fun auto(image: GrayImage): Result {
        val hist = histogram(image)
        val total = image.pixels.size.toDouble()
        var midtones = 0L
        for (v in 40..215) midtones += hist[v]
        val midFraction = midtones / total
        val otsu = otsuThreshold(image)
        return if (midFraction < 0.06) {
            Result(global(image, otsu), "otsu", otsu)
        } else {
            val window = (min(image.width, image.height) / 24).coerceIn(15, 81)
            Result(sauvola(image, window), "sauvola(w=$window)", otsu)
        }
    }
}
