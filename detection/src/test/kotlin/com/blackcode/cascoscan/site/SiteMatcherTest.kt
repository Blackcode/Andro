package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.Evidence
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.ParsedLabel
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.Similarity
import com.blackcode.cascoscan.detect.SizeMm
import com.blackcode.cascoscan.detect.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SiteMatcherTest {

    private val drawingScale = DrawingScale.fromRatio(50.0, 200.0)   // 6.35 mm per drawing pixel
    private val photoMmPerPx = 2000.0 / 1200.0                       // 1.667 mm per photo pixel
    private val siteConfig = SiteConfig()

    /** A penetration the drawing asks for, labelled with its nominal diameter. */
    private fun required(id: String, x: Double, y: Double, diameterMm: Double): Penetration {
        val px = diameterMm / drawingScale.mmPerPx
        return Penetration(
            id = id,
            pageIndex = 0,
            center = Pt(x, y),
            box = IBox.around(Pt(x, y), px / 2, px / 2),
            kind = SymbolKind.CIRCLE_CROSSED,
            confidence = 0.9,
            sizeMm = SizeMm(diameterMm, diameterMm),
            label = ParsedLabel("Ø${diameterMm.toInt()}", IBox(0, 0, 10, 10), diameterMm = diameterMm),
        )
    }

    /** A hole seen in a photograph. [tiltDeg] lets a test make the measurement unreliable on purpose. */
    private fun observed(
        id: String,
        x: Double,
        y: Double,
        diameterMm: Double,
        tiltDeg: Double = 0.0,
        confidence: Double = 0.9,
    ): SiteObservation {
        val semiMajor = diameterMm / photoMmPerPx / 2.0
        val semiMinor = semiMajor * kotlin.math.cos(Math.toRadians(tiltDeg))
        val ellipse = Ellipse(Pt(x, y), semiMajor, semiMinor, 0.0)
        val features = SiteFeatures(
            box = IBox.around(Pt(x, y), semiMajor, semiMinor.coerceAtLeast(1.0)),
            pixelCount = (Math.PI * semiMajor * semiMinor).toInt().coerceAtLeast(1),
            ellipse = ellipse,
            ellipseIou = 0.97,
            boxIou = 0.77,
            solidity = 0.99,
            interiorMean = 28.0,
            surroundMean = 158.0,
            edgeTransitionPx = 1.1,
            ringContrast = 2.0,
            touchesFrameEdge = false,
        )
        return SiteObservation(
            id = id,
            kind = SiteKind.OPEN_HOLE,
            confidence = confidence,
            features = features,
            evidence = Evidence.EMPTY,
            sizeMm = SizeMm(diameterMm, diameterMm),
            photoUri = "file:///photo/$id.jpg",
        )
    }

    @Test
    fun `a slab opening that was never formed is reported missing`() {
        // Four openings on the drawing; the photograph shows three of them. Positions are what identifies
        // which one is absent, so this is the planar case.
        val need = listOf(
            required("P1.1", 200.0, 300.0, 110.0),
            required("P1.2", 260.0, 300.0, 160.0),
            required("P1.3", 320.0, 360.0, 110.0),
            required("P1.4", 200.0, 420.0, 200.0),
        )
        // Photograph maps to the drawing by scale 0.2625 (1.667/6.35), a small rotation and an offset.
        val toDrawing = Similarity(0.2625, 0.02, 120.0, 180.0)
        val fromDrawing = toDrawing.inverse()
        val seen = need.filter { it.id != "P1.3" }.mapIndexed { i, p ->
            val photoPoint = fromDrawing.apply(p.center)
            observed("S${i + 1}", photoPoint.x, photoPoint.y, p.sizeMm!!.diameter)
        }

        val result = SiteMatcher.match(
            required = need,
            observed = seen,
            drawingScale = drawingScale,
            photoMmPerPx = photoMmPerPx,
            strategy = SiteMatcher.Strategy.PLANAR,
            siteConfig = siteConfig,
        )
        assertEquals(SiteMatcher.Strategy.PLANAR, result.strategy, "should have registered the photograph")
        assertEquals(3, result.matched.size, "three holes are there")
        assertEquals(listOf("P1.3"), result.missing.map { it.id })
        assertTrue(result.unexpected.isEmpty())
        assertTrue(result.matched.all { it.verdict == SiteMatcher.Verdict.PRESENT }, "${result.matched}")
        assertNotNull(result.transform)
    }

    @Test
    fun `a wall of differently sized sleeves identifies the one not drilled`() {
        // The plan shows this wall as a line, so there is no transform to fit; the sizes and their order
        // along the wall are what carry the match.
        val need = listOf(
            required("P2.1", 400.0, 500.0, 110.0),
            required("P2.2", 460.0, 500.0, 160.0),
            required("P2.3", 520.0, 500.0, 200.0),
            required("P2.4", 580.0, 500.0, 110.0),
        )
        val seen = listOf(
            observed("S1", 150.0, 400.0, 110.0),
            observed("S2", 700.0, 410.0, 200.0),
            observed("S3", 1050.0, 405.0, 110.0),
        )
        val result = SiteMatcher.match(need, seen, drawingScale, photoMmPerPx, siteConfig = siteConfig)
        assertEquals(SiteMatcher.Strategy.ORDER_AND_SIZE, result.strategy)
        assertEquals(listOf("P2.2"), result.missing.map { it.id }, "the 160 was not drilled")
        assertEquals(3, result.matched.size)
        assertTrue(result.matched.all { it.verdict == SiteMatcher.Verdict.PRESENT })
    }

    @Test
    fun `a hole drilled to the wrong size is a size finding, not a missing hole`() {
        val need = listOf(required("P3.1", 400.0, 500.0, 110.0))
        val seen = listOf(observed("S1", 600.0, 450.0, 160.0))
        val result = SiteMatcher.match(need, seen, drawingScale, photoMmPerPx, siteConfig = siteConfig)
        assertTrue(result.missing.isEmpty(), "the hole exists; it is just the wrong bore")
        val match = result.matched.single()
        assertEquals(SiteMatcher.Verdict.WRONG_SIZE, match.verdict)
        assertEquals(50.0, match.sizeDeltaMm)
    }

    @Test
    fun `a steeply angled photograph does not raise a size finding`() {
        // 75 degrees off-axis: the diameter is a guess, and a guess must not accuse anyone.
        val need = listOf(required("P4.1", 400.0, 500.0, 110.0))
        val seen = listOf(observed("S1", 600.0, 450.0, 160.0, tiltDeg = 75.0))
        val result = SiteMatcher.match(need, seen, drawingScale, photoMmPerPx, siteConfig = siteConfig)
        val match = result.matched.single()
        assertEquals(SiteMatcher.Verdict.PRESENT, match.verdict, "size was not measurable, so no finding")
        assertTrue(!seen[0].sizeIsReliable(siteConfig))
    }

    @Test
    fun `a hole nobody asked for is reported as unexpected`() {
        val need = listOf(required("P5.1", 400.0, 500.0, 110.0))
        val seen = listOf(observed("S1", 300.0, 450.0, 110.0), observed("S2", 900.0, 450.0, 250.0))
        val result = SiteMatcher.match(need, seen, drawingScale, photoMmPerPx, siteConfig = siteConfig)
        assertEquals(1, result.matched.size)
        assertEquals(listOf("S2"), result.unexpected.map { it.id })
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun `a photograph with nothing in it warns instead of silently condemning the wall`() {
        val need = (1..3).map { required("P6.$it", 400.0 + it * 60, 500.0, 110.0) }
        val result = SiteMatcher.match(need, emptyList(), drawingScale, photoMmPerPx, siteConfig = siteConfig)
        assertEquals(3, result.missing.size)
        assertNotNull(result.warning, "reporting three findings off an empty photo must carry a warning")
    }

    @Test
    fun `low confidence detections do not accuse the builder`() {
        val need = listOf(required("P7.1", 400.0, 500.0, 110.0))
        val faint = listOf(observed("S1", 600.0, 450.0, 110.0, confidence = 0.35))
        val result = SiteMatcher.match(need, faint, drawingScale, photoMmPerPx, siteConfig = siteConfig)
        // The hole is reported missing rather than present, but the warning says why the photo was thin.
        assertEquals(1, result.missing.size)
        assertTrue(result.unexpected.isEmpty(), "a detection too faint to trust is not an extra hole either")
    }

    @Test
    fun `the search is narrowed to the area photographed`() {
        val all = listOf(
            required("near1", 500.0, 500.0, 110.0),
            required("near2", 520.0, 510.0, 110.0),
            required("far", 3000.0, 3000.0, 110.0),
        )
        // 3 m radius at 6.35 mm per pixel is about 472 px.
        val near = SiteMatcher.requiredNear(all, Pt(510.0, 505.0), radiusMm = 3000.0, drawingScale = drawingScale)
        assertEquals(listOf("near1", "near2"), near.map { it.id })
    }

    @Test
    fun `checklist updates carry the verdict, the reason and the photograph`() {
        val need = listOf(
            required("P8.1", 400.0, 500.0, 110.0),
            required("P8.2", 460.0, 500.0, 200.0),
        )
        val seen = listOf(observed("S1", 300.0, 450.0, 110.0))
        val result = SiteMatcher.match(need, seen, drawingScale, photoMmPerPx, siteConfig = siteConfig)
        val updates = SiteMatcher.toChecklistUpdates(result).toMap()

        val present = assertNotNull(updates[need.first { it.id == "P8.1" }])
        assertEquals(AuditStatus.PRESENT, present.status)
        assertEquals("file:///photo/S1.jpg", present.photoUri, "the evidence must travel with the verdict")
        assertTrue(present.note.contains("110"))

        val missing = assertNotNull(updates[need.first { it.id == "P8.2" }])
        assertEquals(AuditStatus.MISSING, missing.status)
        assertTrue(missing.note.isNotBlank())
    }
}
