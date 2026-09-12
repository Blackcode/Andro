package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DetectorTest {

    private val config = SyntheticSheet.config()

    private fun nearest(result: PageResult, p: Pt): Penetration? =
        result.penetrations.minByOrNull { it.center.distanceTo(p) }?.takeIf { it.center.distanceTo(p) <= 18.0 }

    @Test
    fun `finds every penetration on a synthetic casco sheet`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val missed = SyntheticSheet.expected.filter { nearest(result, it.center) == null }
        assertTrue(
            missed.isEmpty(),
            "missed ${missed.map { "${it.name} (${it.note})" }}; found ${describe(result)}",
        )
    }

    @Test
    fun `accepts the real penetrations without accepting the clutter`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val accepted = result.accepted
        val spurious = accepted.filter { found ->
            SyntheticSheet.expected.none { it.center.distanceTo(found.center) <= 18.0 }
        }
        assertTrue(
            spurious.isEmpty(),
            "accepted ${spurious.size} false positives: " +
                spurious.joinToString { "${it.id} ${it.kind} at ${it.center} conf=%.2f [${it.evidence.summary()}]".format(it.confidence) },
        )
        // Every expected penetration should clear the accept threshold, not merely be found.
        val onlyReviewable = SyntheticSheet.expected.filter { expected ->
            accepted.none { it.center.distanceTo(expected.center) <= 18.0 }
        }
        assertTrue(onlyReviewable.isEmpty(), "not accepted outright: ${onlyReviewable.map { it.name }}")
    }

    @Test
    fun `a solid column of plausible size is queued for review, never reported as found`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val column = nearest(result, Pt(230.0, 740.0))
        if (column != null) {
            assertTrue(
                column.confidence < config.acceptThreshold,
                "a plain filled square must not be accepted on size alone (conf=${column.confidence})",
            )
        }
    }

    @Test
    fun `the title block is excluded on request`() {
        val withExclusion = PenetrationDetector(SyntheticSheet.config(excludeTitleBlock = true))
            .detect(SyntheticSheet.input())
        assertEquals(
            0,
            withExclusion.penetrations.count { SyntheticSheet.titleBlock.contains(it.center) },
            "nothing inside the title block may survive",
        )
    }

    @Test
    fun `a symbol drawn across a structural line is still found`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val onGridLine = assertNotNull(
            nearest(result, Pt(1000.0, 650.0)),
            "the ring drawn on the grid line was lost; only structural line removal can recover it",
        )
        assertTrue(onGridLine.kind.isRound, "expected a round symbol, got ${onGridLine.kind}")
    }

    @Test
    fun `an opening enclosed by wall faces is found as a void`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val opening = assertNotNull(nearest(result, Pt(1110.0, 315.0)), "the wall opening was not found")
        val size = assertNotNull(opening.sizeMm)
        // 60 x 30 px at 6.35 mm/px.
        assertTrue(size.width in 330.0..430.0, "width was ${size.width} mm")
        assertTrue(size.height in 140.0..240.0, "height was ${size.height} mm")
    }

    @Test
    fun `measured sizes and labels agree on the labelled ring`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val ring = assertNotNull(nearest(result, Pt(820.0, 315.0)))
        assertEquals(110.0, ring.label?.diameterMm, "the nearby label should be attached")
        val measured = assertNotNull(ring.sizeMm).diameter
        assertTrue(measured in 95.0..145.0, "measured diameter was $measured mm")
        assertTrue(ring.evidence.terms.containsKey("sizeMatch"), "size agreement should be part of the evidence")
    }

    @Test
    fun `ids run in reading order`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val ids = result.penetrations.map { it.id }
        assertEquals(ids.distinct(), ids, "ids must be unique")
        assertEquals("P1.1", ids.first())
        // Numbering follows the page, so the first row is above the last row.
        assertTrue(result.penetrations.first().center.y <= result.penetrations.last().center.y + 40)
    }

    @Test
    fun `without a scale the detector still finds the symbols and recovers the scale from labels`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input(withScale = false))
        val estimate = result.diagnostics.scaleEstimate
        // Only one round symbol carries a diameter label here, which is deliberately too few to trust.
        if (estimate != null) {
            assertTrue(estimate.samples < 3 || estimate.spread <= 0.2)
        }
        val found = SyntheticSheet.expected.count { nearest(result, it.center) != null }
        assertTrue(found >= 6, "expected most symbols to survive without a scale, found $found; ${describe(result)}")
    }

    @Test
    fun `diagnostics explain what happened`() {
        val result = PenetrationDetector(config).detect(SyntheticSheet.input())
        val d = result.diagnostics
        assertTrue(d.componentCount > 20, "componentCount=${d.componentCount}")
        assertTrue(d.structureCount >= 1, "the slab outline and walls are oversized blobs")
        assertTrue(d.labelsFound >= 4, "labelsFound=${d.labelsFound}")
        assertTrue(d.labelsMatched >= 1, "labelsMatched=${d.labelsMatched}")
        assertEquals("otsu", d.binarizeMethod, "a clean line drawing should take the global path")
        assertNotNull(d.scaleUsed)
    }

    @Test
    fun `confirming and rejecting symbols steers later pages`() {
        val detector = PenetrationDetector(config)
        val baseline = detector.detect(SyntheticSheet.input())
        val column = nearest(baseline, Pt(230.0, 740.0))

        val library = PrototypeLibrary()
        // The auditor rejects the column once; the same shape must then rank lower everywhere.
        if (column != null) {
            val features = baseline.penetrations.first { it.id == column.id }
            library.remember(
                PrototypeLibrary.Signature(
                    kind = features.kind,
                    boxFill = 1.0,
                    radialCv = 0.115,
                    inkRatio = 1.0,
                    hatchScore = 0.0,
                    diagonal = 1.0,
                    squareness = 1.0,
                    logSizeMm = kotlin.math.ln(260.0),
                ),
                wasPenetration = false,
            )
            val taught = detector.detect(SyntheticSheet.input(), prototypes = library)
            val after = nearest(taught, Pt(230.0, 740.0))
            assertTrue(
                after == null || after.confidence < column.confidence,
                "a rejected prototype must lower the score of the same shape",
            )
        }
    }

    private fun describe(result: PageResult): String =
        result.penetrations.joinToString(", ") { "${it.id}:${it.kind}@${it.center.x.toInt()},${it.center.y.toInt()}=%.2f".format(it.confidence) }
}

/**
 * The signature carried on each record is what makes a correction outlive the session that made it,
 * so it has to be there and it has to describe the shape that was actually found.
 */
class SignatureTest {

    @Test
    fun `every detection carries the measurements a correction will need`() {
        val result = PenetrationDetector(SyntheticSheet.config()).detect(SyntheticSheet.input())
        assertTrue(result.penetrations.isNotEmpty())
        for (penetration in result.penetrations) {
            val signature = assertNotNull(penetration.signature, "${penetration.id} has no signature")
            assertEquals(penetration.kind, signature.kind)
            assertTrue(signature.boxFill > 0.0 && signature.boxFill <= 1.05, "boxFill=${signature.boxFill}")
            assertTrue(signature.squareness > 0.0)
            assertNotNull(signature.logSizeMm, "the scale was known, so the size should be too")
        }
    }

    @Test
    fun `rejecting a detection by its stored signature lowers the same shape later`() {
        val result = PenetrationDetector(SyntheticSheet.config()).detect(SyntheticSheet.input())
        val ring = assertNotNull(result.penetrations.firstOrNull { it.kind == SymbolKind.CIRCLE_CROSSED })
        val library = PrototypeLibrary()
        library.remember(assertNotNull(ring.signature), wasPenetration = false)

        val taught = PenetrationDetector(SyntheticSheet.config())
            .detect(SyntheticSheet.input(), prototypes = library)
        val same = assertNotNull(taught.penetrations.minByOrNull { it.center.distanceTo(ring.center) })
        assertTrue(
            same.confidence < ring.confidence,
            "a rejected signature must pull the same shape down (${same.confidence} vs ${ring.confidence})",
        )
    }
}
