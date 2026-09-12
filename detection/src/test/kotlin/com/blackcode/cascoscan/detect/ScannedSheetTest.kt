package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same sheet, as a scan.
 *
 * Construction projects hand over paper as often as they hand over CAD exports, and a scan differs in
 * three ways that each attack a different part of the pipeline: uneven illumination defeats a global
 * threshold, grain breaks thin outlines, and the softness of the optics blurs small symbols together.
 * There is also no text layer, so label evidence, scale recovery and the strongest defence against
 * annotation all disappear at once.
 *
 * The numbers asserted here are the measured ones, not aspirations: a scan is genuinely harder and the
 * point of the test is to pin down how much harder, so a change that quietly makes it worse is caught.
 */
class ScannedSheetTest {

    private val config = SyntheticSheet.config()

    /**
     * Illumination gradient, film grain, and a 3x3 blur, applied in that order - the order a scanner
     * would apply them.
     */
    private fun scanned(seed: Int = 20240917): GrayImage {
        val clean = SyntheticSheet.render()
        val w = clean.width
        val h = clean.height
        val shaded = ByteArray(w * h)
        var state = seed
        fun noise(): Int {
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            // Roughly +/- 12 levels, which is heavier grain than a decent office scanner.
            return (state % 25) - 12
        }
        for (i in 0 until w * h) {
            val x = i % w
            val y = i / w
            val isInk = (clean.pixels[i].toInt() and 0xFF) < 128
            // A broad corner-to-corner falloff: the classic result of scanning an A1 sheet in halves.
            val illumination = 150 + (x * 60 / w) + (y * 25 / h)
            val value = if (isInk) 55 + noise() / 2 else illumination + noise()
            shaded[i] = value.coerceIn(0, 255).toByte()
        }

        val blurred = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        sum += shaded[yy * w + xx].toInt() and 0xFF
                        n++
                    }
                }
                blurred[y * w + x] = (sum / n).toByte()
            }
        }
        return GrayImage(w, h, blurred)
    }

    private fun detect(withText: Boolean) = PenetrationDetector(config).detect(
        PageInput(
            pageIndex = 0,
            image = scanned(),
            scale = SyntheticSheet.scale,
            text = if (withText) SyntheticSheet.text() else emptyList(),
        ),
    )

    @Test
    fun `a shaded noisy scan takes the local threshold`() {
        val result = detect(withText = false)
        assertTrue(
            result.diagnostics.binarizeMethod.startsWith("sauvola"),
            "a scan must not be thresholded globally, got ${result.diagnostics.binarizeMethod}",
        )
    }

    @Test
    fun `most penetrations survive a scan with no text layer at all`() {
        val result = detect(withText = false)
        val found = SyntheticSheet.expected.filter { expected ->
            result.penetrations.any { it.center.distanceTo(expected.center) <= 20.0 }
        }
        val missed = SyntheticSheet.expected - found.toSet()
        assertTrue(
            found.size >= 7,
            "expected at least 7 of 8 from a scan, got ${found.size}; missed ${missed.map { it.name }}",
        )
        val accepted = result.accepted.filter { candidate ->
            SyntheticSheet.expected.none { it.center.distanceTo(candidate.center) <= 20.0 }
        }
        // Without a text layer, annotation is only ruled out geometrically, so a stricter bound than
        // this would be a promise the pipeline cannot keep.
        assertTrue(
            accepted.size <= 2,
            "too much clutter accepted from a scan: " +
                accepted.joinToString { "${it.kind}@${it.center.x.toInt()},${it.center.y.toInt()}=%.2f".format(it.confidence) },
        )
    }

    @Test
    fun `a text layer recovers what the scan cost`() {
        // OCR on a scan yields the same boxes a CAD export would have given, so this is what the
        // pipeline should manage once text is available again.
        val withText = detect(withText = true)
        val found = SyntheticSheet.expected.count { expected ->
            withText.accepted.any { it.center.distanceTo(expected.center) <= 20.0 }
        }
        assertEquals(SyntheticSheet.expected.size, found, "with text, a scan should lose nothing")
        val spurious = withText.accepted.filter { candidate ->
            SyntheticSheet.expected.none { it.center.distanceTo(candidate.center) <= 20.0 }
        }
        assertTrue(spurious.isEmpty(), "accepted clutter: ${spurious.map { it.center }}")
    }

    @Test
    fun `grain does not fragment a symbol into several findings`() {
        val result = detect(withText = true)
        for (expected in SyntheticSheet.expected) {
            val hits = result.penetrations.count { it.center.distanceTo(expected.center) <= 20.0 }
            assertTrue(hits <= 1, "${expected.name} was reported $hits times on a noisy scan")
        }
    }
}
