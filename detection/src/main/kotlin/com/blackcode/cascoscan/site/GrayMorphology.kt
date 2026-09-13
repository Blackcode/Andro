package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.GrayImage
import kotlin.math.max

/**
 * Greyscale morphology, used to separate a hole in a wall from the lighting on that wall.
 *
 * This is the step that makes photographic detection possible at all. A cored hole is simply a patch
 * that is darker than the concrete around it - but so is the shaded half of the room, and a locally
 * adaptive threshold cannot help: with a window smaller than the hole, the middle of a 200 mm hole has
 * nothing bright nearby to be dark *relative to*, so the hole comes out hollow or vanishes.
 *
 * A morphological closing with a kernel larger than the biggest hole solves it properly. Closing fills
 * dark features smaller than its kernel, so the result is the illumination field - the wall as it would
 * look with no holes in it. Subtracting the photo from that field (a "black top-hat") leaves the holes
 * and nothing else, with the lighting gradient removed exactly rather than approximately.
 *
 * Both passes are separable and use a monotonic deque, so the cost is O(pixels) regardless of kernel
 * size - which matters, because the kernel here is deliberately large.
 */
object GrayMorphology {

    /** Local maximum over a (2*radius+1) square. */
    fun dilate(image: GrayImage, radius: Int): GrayImage =
        if (radius <= 0) image else separable(image, radius, maximum = true)

    /** Local minimum over a (2*radius+1) square. */
    fun erode(image: GrayImage, radius: Int): GrayImage =
        if (radius <= 0) image else separable(image, radius, maximum = false)

    /** Dilate then erode: removes dark features smaller than the kernel, keeping bright structure. */
    fun close(image: GrayImage, radius: Int): GrayImage = erode(dilate(image, radius), radius)

    /**
     * `close(image) - image`: how much darker each pixel is than the surface it sits on.
     *
     * Zero on plain wall however it is lit, large inside a hole. [radius] must exceed the radius of the
     * largest penetration to be found, or that penetration's own interior becomes part of the estimated
     * background and it disappears from the result.
     */
    fun blackTopHat(image: GrayImage, radius: Int): GrayImage {
        val background = close(image, radius)
        val out = ByteArray(image.pixels.size)
        for (i in out.indices) {
            val b = background.pixels[i].toInt() and 0xFF
            val v = image.pixels[i].toInt() and 0xFF
            out[i] = max(0, b - v).toByte()
        }
        return GrayImage(image.width, image.height, out)
    }

    private fun separable(image: GrayImage, radius: Int, maximum: Boolean): GrayImage {
        val w = image.width
        val h = image.height
        val tmp = ByteArray(w * h)
        val line = IntArray(max(w, h))
        val result = IntArray(max(w, h))

        for (y in 0 until h) {
            for (x in 0 until w) line[x] = image.pixels[y * w + x].toInt() and 0xFF
            slide(line, w, radius, maximum, result)
            for (x in 0 until w) tmp[y * w + x] = result[x].toByte()
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = tmp[y * w + x].toInt() and 0xFF
            slide(line, h, radius, maximum, result)
            for (y in 0 until h) out[y * w + x] = result[y].toByte()
        }
        return GrayImage(w, h, out)
    }

    /**
     * Sliding-window extremum in one pass, via a deque holding indices whose values are monotonic.
     * Out-of-range positions are treated as replicated edge pixels, so no dark border is invented.
     */
    private fun slide(values: IntArray, n: Int, radius: Int, maximum: Boolean, out: IntArray) {
        val deque = IntArray(n)
        var head = 0
        var tail = 0

        fun better(a: Int, b: Int) = if (maximum) a >= b else a <= b

        for (i in 0 until n) {
            // Drop values that can never win again.
            while (tail > head && better(values[i], values[deque[tail - 1]])) tail--
            deque[tail++] = i
            // Drop values that have left the window.
            val leftEdge = i - 2 * radius
            if (deque[head] < leftEdge) head++
            // The window centred at i-radius is complete once i has reached it.
            val centre = i - radius
            if (centre >= 0) out[centre] = values[deque[head]]
        }
        // Tail of the signal: the window keeps shrinking against the replicated edge.
        for (centre in max(0, n - radius) until n) {
            val leftEdge = centre - radius
            while (tail > head && deque[head] < leftEdge) head++
            out[centre] = if (tail > head) values[deque[head]] else values[n - 1]
        }
    }
}
