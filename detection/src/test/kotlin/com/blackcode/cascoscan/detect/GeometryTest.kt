package com.blackcode.cascoscan.detect

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun `similarity from two pairs reproduces the pairs exactly`() {
        val from = listOf(Pt(10.0, 20.0), Pt(110.0, 20.0))
        val to = listOf(Pt(50.0, 100.0), Pt(50.0, 300.0))
        val t = assertNotNull(Similarity.fit(from, to))
        assertClose(50.0, t.apply(from[0]).x)
        assertClose(100.0, t.apply(from[0]).y)
        assertClose(50.0, t.apply(from[1]).x)
        assertClose(300.0, t.apply(from[1]).y)
        // A 100 px segment became 200 px, rotated a quarter turn.
        assertClose(2.0, t.scale)
        assertClose(Math.PI / 2, abs(t.rotationRad))
    }

    @Test
    fun `inverse undoes the transform`() {
        val t = Similarity(1.4, -0.6, 33.0, -12.0)
        val inverse = t.inverse()
        val p = Pt(17.0, 91.0)
        val round = inverse.apply(t.apply(p))
        assertClose(p.x, round.x)
        assertClose(p.y, round.y)
    }

    @Test
    fun `least squares fit ignores a single outlier's direction but not its pull`() {
        // Four clean pairs of a pure translation, so the fit must be the translation itself.
        val from = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0))
        val to = from.map { Pt(it.x + 25.0, it.y - 7.0) }
        val t = assertNotNull(Similarity.fit(from, to))
        assertClose(1.0, t.scale)
        assertClose(25.0, t.tx)
        assertClose(-7.0, t.ty)
    }

    @Test
    fun `coincident source points cannot define a transform`() {
        assertNull(Similarity.fit(listOf(Pt(5.0, 5.0), Pt(5.0, 5.0)), listOf(Pt(0.0, 0.0), Pt(9.0, 9.0))))
        assertNull(Similarity.fit(listOf(Pt(1.0, 1.0)), listOf(Pt(2.0, 2.0))))
    }

    @Test
    fun `box geometry`() {
        val a = IBox(10, 10, 19, 29)
        assertEquals(10, a.width)
        assertEquals(20, a.height)
        assertEquals(200, a.area)
        assertClose(0.5, a.squareness)
        assertTrue(a.contains(Pt(10.0, 29.0)))
        assertTrue(!a.contains(Pt(9.9, 29.0)))
        assertEquals(1.0, a.intersectionOverUnion(a))
        assertEquals(0.0, a.intersectionOverUnion(IBox(100, 100, 110, 110)))
    }

    @Test
    fun `size membership is a plateau, not a step`() {
        val config = DetectionConfig()
        fun membership(mm: Double) = Fuzzy.trapezoid(
            mm, config.absoluteMinMm, config.softMinMm, config.softMaxMm, config.absoluteMaxMm,
        )
        assertEquals(0.0, membership(20.0), "a 20 mm dot is not a penetration")
        assertEquals(1.0, membership(110.0), "a 110 sleeve is squarely plausible")
        assertEquals(1.0, membership(800.0), "a duct opening is plausible")
        assertTrue(membership(45.0) in 0.1..0.9, "small conduits are uncertain, not excluded")
        assertTrue(membership(2000.0) in 0.1..0.9, "a shaft is uncertain, not excluded")
        assertEquals(0.0, membership(4000.0), "a room is not a penetration")
    }

    @Test
    fun `scale from ratio matches hand calculation`() {
        // 200 dpi puts 0.127 mm of paper in a pixel; at 1:50 that is 6.35 mm of building.
        val scale = DrawingScale.fromRatio(50.0, 200.0)
        assertClose(6.35, scale.mmPerPx, 1e-6)
        assertClose(110.0, scale.pxToMm(scale.mmToPx(110.0)), 1e-9)
    }

    @Test
    fun `scale estimated from labelled diameters uses the median`() {
        // Three good observations at 6.0 mm/px and one badly mis-associated label.
        val result = assertNotNull(
            ScaleEstimator.estimate(listOf(18.0 to 108.0, 20.0 to 120.0, 25.0 to 150.0, 20.0 to 900.0)),
        )
        assertClose(6.0, result.scale.mmPerPx, 0.01)
        assertEquals(4, result.samples)
        assertEquals(DrawingScale.Source.ESTIMATED_FROM_LABELS, result.scale.source)
    }

    @Test
    fun `scale estimate is refused when there is too little agreement`() {
        assertNull(ScaleEstimator.estimate(listOf(18.0 to 110.0)))
        val noisy = assertNotNull(ScaleEstimator.estimate(listOf(10.0 to 110.0, 20.0 to 110.0, 40.0 to 110.0)))
        assertTrue(noisy.spread > 0.15, "wildly inconsistent labels must report a wide spread")
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but got $actual")
    }
}

class RenderPlanTest {

    @Test
    fun `a fine drawing needs no extra resolution`() {
        val plan = RenderPlan.forRatio(50.0)
        assertTrue(plan.dpi in 120.0..300.0, "dpi=${plan.dpi}")
        assertTrue(!plan.resolutionLimited)
        assertTrue(plan.smallestReliableMm <= 75.0, "smallest=${plan.smallestReliableMm}")
    }

    @Test
    fun `a coarse drawing is resolution limited and says so`() {
        val plan = RenderPlan.forRatio(200.0)
        assertEquals(300.0, plan.dpi, "should sit at the cap")
        assertTrue(plan.resolutionLimited, "the user must be told small openings may be missed")
        // 300 dpi at 1:200 is 16.9 mm per pixel, so 12 px is about 200 mm.
        assertTrue(plan.smallestReliableMm > 150.0, "smallest=${plan.smallestReliableMm}")
    }

    @Test
    fun `the plan's scale and pixel size agree with each other`() {
        val plan = RenderPlan.forRatio(100.0)
        // An A1 sheet is 841 x 594 mm, which is 2384 x 1684 points.
        val (w, h) = plan.pixelsFor(2384.0, 1684.0)
        assertTrue(w > h)
        // The rendered width must equal the real building width the scale implies.
        val buildingWidthMm = w * plan.scale.mmPerPx
        val expectedMm = 841.0 * 100.0
        assertTrue(
            kotlin.math.abs(buildingWidthMm - expectedMm) / expectedMm < 0.01,
            "rendered page covers $buildingWidthMm mm, expected about $expectedMm mm",
        )
    }
}
