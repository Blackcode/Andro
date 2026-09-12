package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Mark what is missing" end to end: align two sheets, match what they have in common, and report
 * what the second one lacks.
 */
class ReconcileTest {

    private val config = SyntheticSheet.config()

    private fun penetration(id: String, x: Double, y: Double, size: Int = 20) = Penetration(
        id = id,
        pageIndex = 0,
        center = Pt(x, y),
        box = IBox.around(Pt(x, y), size / 2.0, size / 2.0),
        kind = SymbolKind.CIRCLE_CROSSED,
        confidence = 0.9,
        sizeMm = SizeMm(size * 6.35, size * 6.35),
    )

    @Test
    fun `alignment recovers a known transform from the penetration positions alone`() {
        val reference = listOf(
            penetration("a", 100.0, 100.0), penetration("b", 400.0, 120.0),
            penetration("c", 250.0, 500.0), penetration("d", 700.0, 480.0),
            penetration("e", 900.0, 200.0), penetration("f", 640.0, 760.0),
        )
        // The target sheet is plotted 1.25x larger and shifted: a different sheet of the same set.
        val truth = Similarity(1.25, 0.0, 60.0, -35.0)
        val target = reference.map { it.copy(center = truth.apply(it.center)) }

        val alignment = assertNotNull(
            PointSetAligner.align(
                from = reference.map { it.center },
                to = target.map { it.center },
                fromSizes = reference.map { it.box.width.toDouble() },
                toSizes = target.map { it.box.width.toDouble() },
            ),
        )
        assertTrue(alignment.isTrustworthy(), "inliers=${alignment.inliers}/${alignment.total}")
        assertEquals(reference.size, alignment.inliers)
        assertTrue(kotlin.math.abs(alignment.transform.scale - 1.25) < 0.02, "scale=${alignment.transform.scale}")
        assertTrue(alignment.meanErrorPx < 1.0, "meanError=${alignment.meanErrorPx}")
    }

    @Test
    fun `alignment is refused when the two sheets have nothing in common`() {
        val reference = (0 until 6).map { penetration("r$it", 100.0 + it * 90, 100.0 + it * 37) }
        val target = listOf(penetration("t0", 20.0, 900.0), penetration("t1", 40.0, 910.0))
        val alignment = PointSetAligner.align(reference.map { it.center }, target.map { it.center })
        assertTrue(alignment == null || !alignment.isTrustworthy(), "a coincidental 2-point fit is not an alignment")
    }

    @Test
    fun `the penetration absent from the second sheet is reported as missing`() {
        val reference = listOf(
            penetration("a", 100.0, 100.0), penetration("b", 400.0, 120.0),
            penetration("c", 250.0, 500.0), penetration("d", 700.0, 480.0),
            penetration("e", 900.0, 200.0), penetration("f", 640.0, 760.0),
        )
        val truth = Similarity(1.0, 0.0, 12.0, -8.0)
        // The builder formed every opening except "d".
        val target = reference.filter { it.id != "d" }
            .map { it.copy(id = "t-${it.id}", center = truth.apply(it.center)) }

        val result = assertNotNull(
            Reconciler.reconcileAutoAligned(reference, target, SyntheticSheet.scale),
            "auto-alignment should succeed on five shared penetrations",
        )
        assertEquals(5, result.matched.size)
        assertEquals(1, result.missingCount)
        assertEquals("d", result.missingInTarget.single().id)
        assertTrue(result.extraInTarget.isEmpty())
        assertTrue(result.coverage in 0.8..0.85, "coverage=${result.coverage}")
    }

    @Test
    fun `an unrequested opening is reported as extra`() {
        val reference = (0 until 5).map { penetration("r$it", 100.0 + it * 120, 300.0) }
        val target = reference.map { it.copy(id = "t$it") } + penetration("rogue", 800.0, 700.0)
        val result = assertNotNull(Reconciler.reconcileAutoAligned(reference, target, SyntheticSheet.scale))
        assertEquals(5, result.matched.size)
        assertEquals(0, result.missingCount)
        assertEquals("rogue", result.extraInTarget.single().id)
    }

    @Test
    fun `a match that is the wrong size or badly displaced is not reported as clean`() {
        val reference = (0 until 5).map { penetration("r$it", 100.0 + it * 150, 300.0, size = 20) }
        val target = reference.mapIndexed { i, p ->
            when (i) {
                // 20 px -> 40 px is 127 mm -> 254 mm: double the requested diameter.
                1 -> p.copy(id = "t1", box = IBox.around(p.center, 20.0, 20.0), sizeMm = SizeMm(254.0, 254.0))
                // Displaced by 16 px, which is about 100 mm: found, but not where it was asked for.
                2 -> p.copy(id = "t2", center = Pt(p.center.x + 16.0, p.center.y))
                else -> p.copy(id = "t$i")
            }
        }
        val result = Reconciler.reconcile(
            reference, target, Similarity.IDENTITY, SyntheticSheet.scale,
        )
        assertEquals(5, result.matched.size, "all five are found; three of them are simply correct")
        val verdicts = result.matched.associate { it.reference.id to it.verdict }
        assertEquals(Reconciler.MatchVerdict.SIZE_MISMATCH, verdicts["r1"])
        assertEquals(Reconciler.MatchVerdict.POSITION_SHIFTED, verdicts["r2"])
        assertEquals(Reconciler.MatchVerdict.OK, verdicts["r0"])
        assertEquals(2, result.deviations.size)
    }

    @Test
    fun `the checklist carries findings onto the sheet being audited`() {
        val reference = (0 until 4).map { penetration("r$it", 100.0 + it * 150, 300.0) }
        val target = reference.filter { it.id != "r2" }.map { it.copy(id = "t-${it.id}", pageIndex = 3) }
        val result = Reconciler.reconcile(
            reference, target, Similarity.IDENTITY, SyntheticSheet.scale,
            referencePageIndex = 0, targetPageIndex = 3,
        )
        val checklist = Reconciler.toChecklist(result)
        assertEquals(4, checklist.size, "every required penetration becomes a row")

        val missing = checklist.single { it.status == AuditStatus.MISSING }
        assertEquals(3, missing.pageIndex, "the finding belongs on the sheet being audited")
        assertEquals(0, missing.requiredByPageIndex)
        assertEquals(Origin.RECONCILED, missing.origin)
        assertTrue(missing.center.distanceTo(Pt(400.0, 300.0)) < 1.0, "positioned where it should have been")
        assertNotNull(missing.note)
        assertTrue(checklist.count { it.status == AuditStatus.PENDING } == 3)
    }

    @Test
    fun `reconciling two renderings of the same sheet finds the opening that was never formed`() {
        // The full integration: detect on the drawing that requires the openings, detect on a sheet
        // where one was not formed, and let the app work out which one.
        val detector = PenetrationDetector(config)
        val required = detector.detect(SyntheticSheet.input(pageIndex = 0))
        val built = detector.detect(SyntheticSheet.input(pageIndex = 1, omit = setOf("crossed circle B")))

        val result = Reconciler.reconcile(
            reference = required.accepted,
            target = built.accepted,
            transform = Similarity.IDENTITY,
            targetScale = SyntheticSheet.scale,
            referencePageIndex = 0,
            targetPageIndex = 1,
        )
        assertEquals(1, result.missingCount, "exactly one opening is absent, got ${result.missingInTarget.map { it.id }}")
        val missing = result.missingInTarget.single()
        assertTrue(
            missing.center.distanceTo(Pt(420.0, 450.0)) < 18.0,
            "the missing opening should be the one at 420,450 but was at ${missing.center}",
        )
        assertTrue(result.extraInTarget.isEmpty(), "extras: ${result.extraInTarget.map { it.id }}")
        assertTrue(result.deviations.isEmpty(), "the remaining openings are unchanged")
    }

    @Test
    fun `a reference penetration the detector is unsure about does not raise a finding`() {
        val shaky = penetration("weak", 500.0, 500.0).copy(confidence = 0.3)
        val reference = (0 until 4).map { penetration("r$it", 100.0 + it * 150, 300.0) } + shaky
        val target = reference.filter { it.id != "weak" }.map { it.copy(id = "t-${it.id}") }
        val result = Reconciler.reconcile(reference, target, Similarity.IDENTITY, SyntheticSheet.scale)
        assertEquals(0, result.missingCount, "an unreviewed low-confidence detection must not accuse the builder")
        assertNull(result.missingInTarget.firstOrNull())
    }
}
