package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.ParsedLabel
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.SizeMm
import com.blackcode.cascoscan.detect.SymbolKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArTargetsTest {

    private val scale = DrawingScale.fromRatio(50.0, 200.0)
    private val metresPerPx = scale.mmPerPx / 1000.0

    /** Plan laid out along world x, floor at y = 0, origin at the world origin. */
    private val placement = run {
        val real = TestPlacement(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3.UP, metresPerPx)
        assertNotNull(
            PlanRegistration.fit(
                listOf(real.controlPoint(Pt(0.0, 0.0)), real.controlPoint(Pt(1000.0, 0.0))),
                scale,
            ),
        ).placement
    }

    private fun penetration(id: String, x: Double, y: Double, diameterMm: Double = 110.0) = Penetration(
        id = id,
        pageIndex = 0,
        center = Pt(x, y),
        box = IBox.around(Pt(x, y), 9.0, 9.0),
        kind = SymbolKind.CIRCLE_CROSSED,
        confidence = 0.9,
        sizeMm = SizeMm(diameterMm, diameterMm),
        label = ParsedLabel("Ø${diameterMm.toInt()}", IBox(0, 0, 8, 8), diameterMm = diameterMm),
    )

    @Test
    fun `a slab target in front of the camera lands near the middle of the screen`() {
        // The penetration is 3 m along world x; stand 3 m back from it looking straight at it.
        val target = penetration("P1.1", 3.0 / metresPerPx, 0.0)
        val targets = ArTargets.build(listOf(target), placement) { TargetGeometry.ON_SLAB }
        val world = targets.single().base
        val camera = cameraAt(eye = world + Vec3(0.0, 1.5, 3.0), target = world)

        val projected = ArTargets.project(targets, camera, placement)
        val single = assertNotNull(projected.singleOrNull(), "expected one marker, got ${projected.size}")
        assertTrue(
            abs(single.screen.x - camera.viewportWidth / 2.0) < 40.0,
            "marker at ${single.screen}, expected centred horizontally",
        )
        assertTrue(!single.heightUnknown, "a slab penetration's height is known")
        assertTrue(abs(single.distanceM - world.distanceTo(camera.position)) < 0.01)
    }

    @Test
    fun `a penetration behind the camera is not drawn`() {
        val target = penetration("P1.2", 3.0 / metresPerPx, 0.0)
        val targets = ArTargets.build(listOf(target), placement) { TargetGeometry.ON_SLAB }
        val world = targets.single().base
        // Stand at the hole and look the other way.
        val camera = cameraAt(eye = world + Vec3(0.0, 1.5, 0.0), target = world + Vec3(0.0, 1.5, 10.0))
        assertTrue(ArTargets.project(targets, camera, placement).isEmpty())
    }

    @Test
    fun `a penetration beyond range is not drawn`() {
        val target = penetration("P1.3", 40.0 / metresPerPx, 0.0)
        val targets = ArTargets.build(listOf(target), placement) { TargetGeometry.ON_SLAB }
        val world = targets.single().base
        val camera = cameraAt(eye = Vec3(0.0, 1.5, 0.0), target = world)
        assertTrue(ArTargets.project(targets, camera, placement, rangeM = 12.0).isEmpty())
        assertTrue(ArTargets.project(targets, camera, placement, rangeM = 60.0).isNotEmpty())
    }

    @Test
    fun `marker size falls off with distance, in proportion`() {
        val target = penetration("P1.4", 0.0, 0.0, diameterMm = 200.0)
        val targets = ArTargets.build(listOf(target), placement) { TargetGeometry.ON_SLAB }
        val world = targets.single().base

        val near = ArTargets.project(targets, cameraAt(world + Vec3(0.0, 0.0, 2.0), world), placement).single()
        val far = ArTargets.project(targets, cameraAt(world + Vec3(0.0, 0.0, 6.0), world), placement).single()
        // Three times the distance, a third of the size.
        val ratio = near.screenRadiusPx / far.screenRadiusPx
        assertTrue(ratio in 2.4..3.6, "radius ratio was $ratio, expected about 3")
    }

    @Test
    fun `a wall target is floated at the viewer's height and says the height is unknown`() {
        // A plan cannot say how high up a wall a sleeve is, and the overlay must not pretend otherwise.
        val target = penetration("P1.5", 4.0 / metresPerPx, 0.0)
        val targets = ArTargets.build(listOf(target), placement) { TargetGeometry.ON_WALL }
        val base = targets.single().base
        val eye = base + Vec3(0.0, 1.6, 3.0)
        val camera = cameraAt(eye = eye, target = base + Vec3(0.0, 1.6, 0.0))

        val projected = assertNotNull(ArTargets.project(targets, camera, placement).singleOrNull())
        assertTrue(projected.heightUnknown)
        // Floated at eye level, so it projects near the vertical centre rather than down at the floor.
        assertTrue(
            abs(projected.screen.y - camera.viewportHeight / 2.0) < 80.0,
            "wall marker at ${projected.screen}; expected near eye level",
        )
    }

    @Test
    fun `nearer markers are drawn last so they end up on top`() {
        val targets = ArTargets.build(
            listOf(
                penetration("near", 2.0 / metresPerPx, 0.0),
                penetration("far", 8.0 / metresPerPx, 0.0),
            ),
            placement,
        ) { TargetGeometry.ON_SLAB }
        val camera = cameraAt(eye = Vec3(0.0, 1.5, 0.0), target = Vec3(8.0, 0.0, 0.0))
        val projected = ArTargets.project(targets, camera, placement)
        assertEquals(listOf("far", "near"), projected.map { it.target.id })
    }

    @Test
    fun `the status travels with the target so the overlay can colour it`() {
        val missing = penetration("P1.6", 1.0 / metresPerPx, 0.0).copy(status = AuditStatus.MISSING)
        val built = ArTargets.build(listOf(missing), placement) { TargetGeometry.ON_WALL }.single()
        assertEquals(AuditStatus.MISSING, built.status)
        assertEquals(0.110, built.diameterM, 1e-9)
    }

    @Test
    fun `a point exactly at the camera is not projected`() {
        val target = penetration("P1.7", 0.0, 0.0)
        val targets = ArTargets.build(listOf(target), placement) { TargetGeometry.ON_SLAB }
        val world = targets.single().base
        val camera = cameraAt(eye = world, target = world + Vec3(1.0, 0.0, 0.0))
        assertTrue(ArTargets.project(targets, camera, placement).isEmpty())
    }

    @Test
    fun `projecting a point behind the camera returns nothing rather than a mirrored position`() {
        val camera = cameraAt(eye = Vec3.ZERO, target = Vec3(0.0, 0.0, -5.0))
        assertNull(ArTargets.projectToScreen(Vec3(0.0, 0.0, 5.0), camera), "a point behind must not project")
        assertNotNull(ArTargets.projectToScreen(Vec3(0.0, 0.0, -5.0), camera))
    }
}

/**
 * Measuring from depth rather than from a calibration tap. This is what makes the AR flow able to report
 * a diameter without asking the auditor for anything.
 */
class ArPhotogrammetryTest {

    @Test
    fun `a sleeve measures correctly from its distance and the focal length`() {
        // A phone at 1080 px wide with a 60-degree horizontal field of view has fx of about 935 px.
        // A 110 mm sleeve 2 m away should then be about 51 px across.
        val fx = 935.0
        val expectedPixels = 0.110 * fx / 2.0
        assertTrue(abs(expectedPixels - 51.4) < 1.0, "sanity: $expectedPixels")

        val mm = assertNotNull(ArPhotogrammetry.sizeMm(expectedPixels, distanceM = 2.0, focalLengthPx = fx))
        assertTrue(abs(mm - 110.0) < 1.0, "measured $mm mm, expected 110")
    }

    @Test
    fun `the same hole measures the same from twice the distance`() {
        val fx = 935.0
        val near = assertNotNull(ArPhotogrammetry.sizeMm(100.0, distanceM = 1.0, focalLengthPx = fx))
        val far = assertNotNull(ArPhotogrammetry.sizeMm(50.0, distanceM = 2.0, focalLengthPx = fx))
        assertTrue(abs(near - far) < 0.01, "$near vs $far")
    }

    @Test
    fun `the focal length must be scaled to the image the measurement was made in`() {
        // Detection runs on a reduced copy; using the full-resolution focal length would understate every
        // diameter by exactly the reduction factor.
        val full = 1870.0
        val reduced = ArPhotogrammetry.focalLengthFor(full, downsampleFactor = 2)
        assertTrue(abs(reduced - 935.0) < 1e-9)

        val measuredOnReduced = 51.4
        val right = assertNotNull(ArPhotogrammetry.sizeMm(measuredOnReduced, 2.0, reduced))
        val wrong = assertNotNull(ArPhotogrammetry.sizeMm(measuredOnReduced, 2.0, full))
        assertTrue(abs(right - 110.0) < 1.5, "correct scaling gave $right")
        assertTrue(abs(wrong - 55.0) < 1.5, "the mistake halves it: $wrong")
    }

    @Test
    fun `nonsense inputs produce no measurement rather than a wrong one`() {
        assertNull(ArPhotogrammetry.sizeMm(0.0, 2.0, 935.0))
        assertNull(ArPhotogrammetry.sizeMm(50.0, 0.0, 935.0))
        assertNull(ArPhotogrammetry.sizeMm(50.0, 2.0, 0.0))
    }

    @Test
    fun `depth error carries straight into size error`() {
        // Linear, so it is worth telling the user: 10 cm of depth uncertainty at 2 m is 5% on the diameter.
        assertTrue(abs(ArPhotogrammetry.relativeSizeErrorFor(2.0, 0.10) - 0.05) < 1e-9)
        assertTrue(ArPhotogrammetry.relativeSizeErrorFor(0.0, 0.10) == 1.0)
    }
}
