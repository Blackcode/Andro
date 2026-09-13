package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.ParsedLabel
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.SizeMm
import com.blackcode.cascoscan.detect.SymbolKind
import com.blackcode.cascoscan.site.SiteMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In augmented reality the comparison is far better posed than it was from a photograph: once the drawing
 * is registered, both sides are in the same metric space, so there is no transform to fit, no scale to
 * guess and no ordering to infer.
 */
class ArMatcherTest {

    private val scale = DrawingScale.fromRatio(50.0, 200.0)
    private val metresPerPx = scale.mmPerPx / 1000.0

    private val placement = run {
        val real = TestPlacement(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3.UP, metresPerPx)
        assertNotNull(
            PlanRegistration.fit(
                listOf(real.controlPoint(Pt(0.0, 0.0)), real.controlPoint(Pt(1000.0, 0.0))),
                scale,
            ),
        ).placement
    }

    private fun penetration(id: String, metresAlongX: Double, diameterMm: Double = 110.0) = Penetration(
        id = id,
        pageIndex = 0,
        center = Pt(metresAlongX / metresPerPx, 0.0),
        box = IBox.around(Pt(metresAlongX / metresPerPx, 0.0), 9.0, 9.0),
        kind = SymbolKind.CIRCLE_CROSSED,
        confidence = 0.9,
        sizeMm = SizeMm(diameterMm, diameterMm),
        label = ParsedLabel("Ø${diameterMm.toInt()}", IBox(0, 0, 8, 8), diameterMm = diameterMm),
    )

    private fun seen(id: String, world: Vec3, diameterMm: Double? = 110.0, confidence: Double = 0.9) =
        ObservedInWorld(id, world, diameterMm, confidence, Pt(0.0, 0.0), "file:///f/$id.jpg")

    private fun targets(vararg penetrations: Penetration, geometry: TargetGeometry) =
        ArTargets.build(penetrations.toList(), placement) { geometry }

    @Test
    fun `a slab opening that was never formed is reported missing`() {
        val required = targets(
            penetration("P1.1", 1.0),
            penetration("P1.2", 2.0),
            penetration("P1.3", 3.0),
            geometry = TargetGeometry.ON_SLAB,
        )
        // Two of the three are there, a few centimetres off as real work is.
        val observed = listOf(
            seen("S1", required[0].base + Vec3(0.03, 0.0, 0.02)),
            seen("S3", required[2].base + Vec3(-0.04, 0.0, 0.01)),
        )
        val result = ArMatcher.match(required, observed, placement)
        assertEquals(2, result.matched.size)
        assertEquals(listOf("P1.2"), result.missing.map { it.id })
        assertTrue(result.unexpected.isEmpty())
        assertTrue(result.matched.all { it.verdict == SiteMatcher.Verdict.PRESENT }, "${result.matched}")
    }

    @Test
    fun `a wall hole matches whatever height it is at`() {
        // The plan never knew the height, so disagreeing about it is not a disagreement.
        val required = targets(penetration("P2.1", 2.0), geometry = TargetGeometry.ON_WALL)
        val highUp = seen("S1", required[0].base + Vec3(0.02, 2.3, 0.0))
        val result = ArMatcher.match(required, listOf(highUp), placement)
        assertEquals(1, result.matched.size)
        assertEquals(SiteMatcher.Verdict.PRESENT, result.matched.single().verdict)
        assertTrue(result.matched.single().offsetM < 0.05, "height must not count as an offset")
    }

    @Test
    fun `on a slab, height does count`() {
        val required = targets(penetration("P3.1", 2.0), geometry = TargetGeometry.ON_SLAB)
        val ceiling = seen("S1", required[0].base + Vec3(0.0, 2.6, 0.0))
        val result = ArMatcher.match(required, listOf(ceiling), placement)
        assertTrue(result.matched.isEmpty(), "a hole 2.6 m above a slab opening is not that opening")
        assertEquals(1, result.missing.size)
        assertEquals(1, result.unexpected.size)
    }

    @Test
    fun `a hole bored to the wrong size is a size finding, not a missing hole`() {
        val required = targets(penetration("P4.1", 2.0, diameterMm = 110.0), geometry = TargetGeometry.ON_WALL)
        val wrong = seen("S1", required[0].base + Vec3(0.02, 1.2, 0.0), diameterMm = 160.0)
        val result = ArMatcher.match(required, listOf(wrong), placement)
        assertTrue(result.missing.isEmpty())
        val match = result.matched.single()
        assertEquals(SiteMatcher.Verdict.WRONG_SIZE, match.verdict)
        assertEquals(50.0, match.sizeDeltaMm)
    }

    @Test
    fun `a hole well away from its drawn position is reported as displaced`() {
        val required = targets(penetration("P5.1", 2.0), geometry = TargetGeometry.ON_WALL)
        val shifted = seen("S1", required[0].base + Vec3(0.22, 1.2, 0.0))
        val result = ArMatcher.match(required, listOf(shifted), placement)
        val match = result.matched.single()
        assertEquals(SiteMatcher.Verdict.WRONG_POSITION, match.verdict)
        assertTrue(match.offsetM > 0.2)
    }

    @Test
    fun `an opening nobody asked for is reported`() {
        val required = targets(penetration("P6.1", 2.0), geometry = TargetGeometry.ON_WALL)
        val observed = listOf(
            seen("S1", required[0].base + Vec3(0.01, 1.2, 0.0)),
            seen("S2", required[0].base + Vec3(1.8, 1.2, 0.0), diameterMm = 250.0),
        )
        val result = ArMatcher.match(required, observed, placement)
        assertEquals(1, result.matched.size)
        assertEquals(listOf("S2"), result.unexpected.map { it.id })
    }

    @Test
    fun `a detection too faint to trust is not evidence of a hole`() {
        val required = targets(penetration("P7.1", 2.0), geometry = TargetGeometry.ON_WALL)
        val faint = seen("S1", required[0].base + Vec3(0.01, 1.2, 0.0), confidence = 0.3)
        val result = ArMatcher.match(required, listOf(faint), placement)
        assertEquals(1, result.missing.size)
        assertTrue(result.unexpected.isEmpty())
    }

    @Test
    fun `an unmeasurable hole is still matched, with no size claimed`() {
        val required = targets(penetration("P8.1", 2.0), geometry = TargetGeometry.ON_WALL)
        val unmeasured = seen("S1", required[0].base + Vec3(0.02, 1.2, 0.0), diameterMm = null)
        val result = ArMatcher.match(required, listOf(unmeasured), placement)
        val match = result.matched.single()
        assertEquals(SiteMatcher.Verdict.PRESENT, match.verdict)
        assertTrue(match.sizeDeltaMm == null)
    }

    @Test
    fun `checklist updates carry the verdict, the reason and the evidence`() {
        val required = targets(
            penetration("P9.1", 1.0),
            penetration("P9.2", 2.0),
            geometry = TargetGeometry.ON_WALL,
        )
        val observed = listOf(seen("S1", required[0].base + Vec3(0.02, 1.2, 0.0)))
        val result = ArMatcher.match(required, observed, placement)
        val updates = ArMatcher.toChecklistUpdates(result).toMap()

        val present = assertNotNull(updates[required[0].penetration])
        assertEquals(AuditStatus.PRESENT, present.status)
        assertEquals("file:///f/S1.jpg", present.photoUri)

        val missing = assertNotNull(updates[required[1].penetration])
        assertEquals(AuditStatus.MISSING, missing.status)
        assertTrue(missing.note.isNotBlank())
    }
}
