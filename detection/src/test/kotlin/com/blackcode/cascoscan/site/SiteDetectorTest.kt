package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.Pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The photographic detector, measured against a wall it cannot have memorised.
 *
 * The frame is 2 m of wall across 1200 px, so one pixel is 1.667 mm - a phone photo from a couple of
 * metres. A 110 mm sleeve is 66 px across at that scale, which is what these tests use.
 */
class SiteDetectorTest {

    private val mmPerPx = 2000.0 / 1200.0
    private val config = SiteConfig()
    private fun radiusFor(diameterMm: Double) = diameterMm / mmPerPx / 2.0

    private fun detect(photo: WallPhoto, calibrated: Boolean = true) =
        SiteDetector(config).detect(SitePhoto(photo.toImage(), if (calibrated) mmPerPx else null))

    private fun near(result: SitePhotoResult, x: Double, y: Double, tolerance: Double = 24.0) =
        result.observations.minByOrNull { it.center.distanceTo(Pt(x, y)) }
            ?.takeIf { it.center.distanceTo(Pt(x, y)) <= tolerance }

    @Test
    fun `a cored hole is found and measured`() {
        val wall = WallPhoto(1200, 900)
        wall.hole(600.0, 450.0, radiusFor(110.0), radiusFor(110.0))
        val result = detect(wall)

        val hole = assertNotNull(near(result, 600.0, 450.0), "no hole found; got ${describe(result)}")
        assertEquals(SiteKind.OPEN_HOLE, hole.kind)
        assertTrue(hole.confidence >= config.acceptThreshold, "confidence ${hole.confidence}")
        val size = assertNotNull(hole.sizeMm)
        assertTrue(size.diameter in 95.0..castUp(125.0), "measured ${size.diameter} mm, expected about 110")
        assertTrue(hole.tiltDeg < 12.0, "a face-on hole should read as face-on, got ${hole.tiltDeg}")
    }

    @Test
    fun `a shadow the same size and darkness as a hole is rejected`() {
        // This is the test that matters. Same place, same size, same darkness - only the edge differs.
        val wall = WallPhoto(1200, 900)
        wall.shadow(600.0, 450.0, radiusFor(110.0), radiusFor(110.0))
        val result = detect(wall)

        val shadow = near(result, 600.0, 450.0)
        assertTrue(
            shadow == null || shadow.confidence < config.acceptThreshold,
            "a shadow was accepted as a penetration: ${shadow?.confidence} [${shadow?.evidence?.summary(4)}]",
        )
    }

    @Test
    fun `a hole and a shadow side by side are told apart`() {
        val wall = WallPhoto(1400, 900)
        wall.hole(420.0, 450.0, radiusFor(110.0), radiusFor(110.0))
        wall.shadow(980.0, 450.0, radiusFor(110.0), radiusFor(110.0))
        val result = detect(wall)

        val hole = assertNotNull(near(result, 420.0, 450.0), "hole missed; ${describe(result)}")
        val shadow = near(result, 980.0, 450.0)
        assertTrue(hole.confidence >= config.acceptThreshold, "hole confidence ${hole.confidence}")
        assertTrue(
            shadow == null || shadow.confidence < hole.confidence - 0.2,
            "the shadow scored too close to the hole: ${shadow?.confidence} vs ${hole.confidence}",
        )
    }

    @Test
    fun `a hole photographed off-axis still reports its true diameter`() {
        // A 200 mm hole seen at 60 degrees: the minor axis is halved, the major axis is unchanged. The
        // major axis is therefore the diameter, which is the whole reason a usable size can be read from
        // a photo taken from wherever the auditor was standing.
        val wall = WallPhoto(1200, 900)
        val r = radiusFor(200.0)
        wall.hole(600.0, 450.0, r, r * 0.5, angleDeg = 20.0)
        val result = detect(wall)

        val hole = assertNotNull(near(result, 600.0, 450.0), "missed; ${describe(result)}")
        val size = assertNotNull(hole.sizeMm)
        assertTrue(size.diameter in 175.0..230.0, "measured ${size.diameter} mm, expected about 200")
        assertTrue(hole.tiltDeg in 48.0..72.0, "tilt read as ${hole.tiltDeg}, expected about 60")
        assertTrue(hole.sizeIsReliable(config), "60 degrees should still be within tolerance")
    }

    @Test
    fun `a construction joint is not a penetration`() {
        val wall = WallPhoto(1200, 900)
        wall.crack(200.0, 300.0, 1000.0, 340.0, thickness = 5.0)
        val result = detect(wall)
        val accepted = result.accepted
        assertTrue(accepted.isEmpty(), "a joint was reported as a penetration: ${describe(result)}")
    }

    @Test
    fun `a damp stain is not a penetration`() {
        val wall = WallPhoto(1200, 900)
        wall.stain(600.0, 450.0, radiusFor(260.0))
        val result = detect(wall)
        val stain = near(result, 600.0, 450.0, tolerance = 90.0)
        assertTrue(
            stain == null || stain.confidence < config.acceptThreshold,
            "a stain was accepted: ${stain?.confidence} [${stain?.evidence?.summary(4)}]",
        )
    }

    @Test
    fun `a rectangular opening is recognised as rectangular`() {
        val wall = WallPhoto(1200, 900)
        wall.rectOpening(600.0, 450.0, 300.0 / mmPerPx, 200.0 / mmPerPx, angleDeg = 8.0)
        val result = detect(wall)
        val opening = assertNotNull(near(result, 600.0, 450.0, tolerance = 30.0), describe(result))
        assertEquals(SiteKind.RECT_OPENING, opening.kind)
        val size = assertNotNull(opening.sizeMm)
        assertTrue(size.long in 250.0..350.0, "long side ${size.long}, expected about 300")
    }

    @Test
    fun `a sleeved hole is recognised by its collar`() {
        val wall = WallPhoto(1200, 900)
        wall.sleevedHole(600.0, 450.0, radiusFor(125.0))
        val result = detect(wall)
        val sleeve = assertNotNull(near(result, 600.0, 450.0), describe(result))
        assertEquals(SiteKind.SLEEVED_HOLE, sleeve.kind)
        assertTrue(sleeve.confidence >= config.acceptThreshold)
    }

    @Test
    fun `a wall of several penetrations amid clutter comes out clean`() {
        val wall = WallPhoto(1400, 1000)
        val expected = listOf(
            Triple(260.0, 300.0, 110.0),
            Triple(540.0, 300.0, 110.0),
            Triple(820.0, 300.0, 160.0),
            Triple(1120.0, 640.0, 200.0),
        )
        for ((x, y, d) in expected) wall.hole(x, y, radiusFor(d), radiusFor(d))
        // Clutter: a soft shadow under a beam, a joint, a damp patch.
        wall.shadow(300.0, 760.0, radiusFor(300.0), radiusFor(180.0))
        wall.crack(60.0, 520.0, 1340.0, 560.0, thickness = 6.0)
        wall.stain(700.0, 830.0, radiusFor(240.0))

        val result = detect(wall)
        for ((x, y, d) in expected) {
            val found = near(result, x, y)
            assertNotNull(found, "missed the ${d.toInt()} mm hole at $x,$y; ${describe(result)}")
            assertTrue(found.confidence >= config.acceptThreshold, "${d.toInt()} mm scored ${found.confidence}")
        }
        val spurious = result.accepted.filter { obs ->
            expected.none { (x, y, _) -> obs.center.distanceTo(Pt(x, y)) <= 24.0 }
        }
        assertTrue(spurious.isEmpty(), "clutter accepted: " + spurious.joinToString { "${it.kind}@${it.center.x.toInt()},${it.center.y.toInt()}=%.2f [${it.evidence.summary(3)}]".format(it.confidence) })
    }

    @Test
    fun `uneven lighting neither invents nor hides holes`() {
        // The generator already lights the wall unevenly; this asserts the dark corner behaves like the
        // bright one, which is what the morphological background removal is for.
        val wall = WallPhoto(1400, 1000)
        wall.hole(150.0, 120.0, radiusFor(110.0), radiusFor(110.0))
        wall.hole(1250.0, 880.0, radiusFor(110.0), radiusFor(110.0))
        val result = detect(wall)
        val bright = assertNotNull(near(result, 150.0, 120.0), "missed the hole in the lit corner")
        val dark = assertNotNull(near(result, 1250.0, 880.0), "missed the hole in the shaded corner")
        assertTrue(bright.confidence >= config.acceptThreshold)
        assertTrue(dark.confidence >= config.acceptThreshold)
        val sizes = listOf(assertNotNull(bright.sizeMm).diameter, assertNotNull(dark.sizeMm).diameter)
        assertTrue(
            kotlin.math.abs(sizes[0] - sizes[1]) < 20.0,
            "the same hole measured differently depending on the light: $sizes",
        )
    }

    @Test
    fun `without calibration the hole is still found but no size is claimed`() {
        val wall = WallPhoto(1200, 900)
        wall.hole(600.0, 450.0, radiusFor(110.0), radiusFor(110.0))
        val result = detect(wall, calibrated = false)
        val hole = assertNotNull(near(result, 600.0, 450.0), describe(result))
        assertTrue(hole.sizeMm == null, "an uncalibrated photo must not report a size")
        assertEquals("size unknown", hole.describeSize)
        assertTrue(!result.diagnostics.calibrated)
    }

    @Test
    fun `an empty wall reports nothing`() {
        val result = detect(WallPhoto(1200, 900))
        assertTrue(result.accepted.isEmpty(), "found something on a blank wall: ${describe(result)}")
    }

    private fun castUp(v: Double) = v
    private fun describe(result: SitePhotoResult) =
        result.observations.joinToString { "${it.id}:${it.kind}@${it.center.x.toInt()},${it.center.y.toInt()}=%.2f".format(it.confidence) } +
            " | ${result.diagnostics}"
}
