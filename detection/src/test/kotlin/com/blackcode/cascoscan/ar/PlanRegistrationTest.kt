package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.DrawingScale
import com.blackcode.cascoscan.detect.Pt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registration is the load-bearing piece of the augmented reality: every marker's position in the room
 * comes from it, so an error here puts every ghost on the wrong wall.
 */
class PlanRegistrationTest {

    // 1:50 at 200 dpi is 6.35 mm per drawing pixel, so 157 px is about a metre.
    private val scale = DrawingScale.fromRatio(50.0, 200.0)
    private val metresPerPx = scale.mmPerPx / 1000.0

    private fun truth(dirX: Vec3, origin: Vec3 = Vec3(3.0, 0.0, -2.0)) =
        TestPlacement(origin = origin, dirX = dirX, metresPerPx = metresPerPx)

    @Test
    fun `two reference points place every other point correctly`() {
        // The room is rotated 35 degrees relative to the sheet, and offset.
        val heading = Math.toRadians(35.0)
        val dirX = Vec3(kotlin.math.cos(heading), 0.0, kotlin.math.sin(heading))
        val real = truth(dirX)

        val a = Pt(400.0, 600.0)
        val b = Pt(1500.0, 900.0)
        val result = assertNotNull(
            PlanRegistration.fit(listOf(real.controlPoint(a), real.controlPoint(b)), scale),
        )
        assertTrue(result.isTrustworthy(), "residual ${result.residualM}, ratio ${result.impliedScaleRatio}")

        // A point nowhere near either reference must still land where the independent model puts it.
        for (test in listOf(Pt(200.0, 200.0), Pt(2000.0, 1400.0), Pt(900.0, 50.0))) {
            val expected = real.world(test)
            val actual = result.placement.toWorld(test)
            assertTrue(
                expected.distanceTo(actual) < 0.01,
                "drawing $test placed at $actual, expected $expected",
            )
        }
    }

    @Test
    fun `the plan is never mirrored`() {
        // The trap: two control points cannot tell a rotation from a reflection, and a mirrored plan puts
        // every penetration on the wrong side of the room while fitting both references perfectly.
        val real = truth(Vec3(1.0, 0.0, 0.0))
        val a = Pt(300.0, 300.0)
        val b = Pt(1200.0, 300.0)
        val result = assertNotNull(PlanRegistration.fit(listOf(real.controlPoint(a), real.controlPoint(b)), scale))

        // A point below the line a-b on the page must stay on the same side in the room.
        val below = Pt(750.0, 900.0)
        val expected = real.world(below)
        val actual = result.placement.toWorld(below)
        assertTrue(
            expected.distanceTo(actual) < 0.01,
            "mirrored placement: $below landed at $actual instead of $expected",
        )
        // And explicitly: going down the page must not go up the page in the room.
        val pageDown = real.world(Pt(750.0, 900.0)) - real.world(Pt(750.0, 300.0))
        val fitDown = result.placement.toWorld(Pt(750.0, 900.0)) - result.placement.toWorld(Pt(750.0, 300.0))
        assertTrue((pageDown dot fitDown) > 0.0, "the page's y direction was flipped")
    }

    @Test
    fun `drawing to world and back is a round trip`() {
        val real = truth(Vec3(0.3, 0.0, 0.9))
        val result = assertNotNull(
            PlanRegistration.fit(
                listOf(real.controlPoint(Pt(500.0, 400.0)), real.controlPoint(Pt(1600.0, 1100.0))),
                scale,
            ),
        )
        for (test in listOf(Pt(0.0, 0.0), Pt(123.0, 987.0), Pt(2400.0, 30.0))) {
            val back = result.placement.toDrawing(result.placement.toWorld(test))
            assertTrue(back.distanceTo(test) < 0.5, "round trip of $test came back as $back")
        }
    }

    @Test
    fun `height above the registered floor is reported`() {
        val real = truth(Vec3(1.0, 0.0, 0.0), origin = Vec3(0.0, 1.5, 0.0))
        val result = assertNotNull(
            PlanRegistration.fit(
                listOf(real.controlPoint(Pt(100.0, 100.0)), real.controlPoint(Pt(900.0, 500.0))),
                scale,
            ),
        )
        val head = real.world(Pt(400.0, 300.0), heightAboveFloor = 1.7)
        assertTrue(abs(result.placement.heightAboveFloor(head) - 1.7) < 0.01)
        // The plan itself has no height, so a plan point is always at floor level.
        assertTrue(abs(result.placement.heightAboveFloor(result.placement.toWorld(Pt(400.0, 300.0)))) < 0.01)
    }

    @Test
    fun `a wrong plot scale shows up as a scale mismatch`() {
        // The room is real; the drawing was registered believing 1:100 when it is plotted 1:50.
        val real = truth(Vec3(1.0, 0.0, 0.0))
        val wrongScale = DrawingScale.fromRatio(100.0, 200.0)
        val result = assertNotNull(
            PlanRegistration.fit(
                listOf(real.controlPoint(Pt(200.0, 200.0)), real.controlPoint(Pt(1800.0, 200.0))),
                wrongScale,
            ),
        )
        val ratio = assertNotNull(result.impliedScaleRatio)
        assertTrue(abs(ratio - 0.5) < 0.02, "implied ratio was $ratio, expected about 0.5")
        assertTrue(!result.isTrustworthy(), "a 2x scale error must not be called trustworthy")
        assertNotNull(result.warning)
    }

    @Test
    fun `a badly tapped third point shows up as a residual`() {
        val real = truth(Vec3(1.0, 0.0, 0.0))
        val good = listOf(real.controlPoint(Pt(200.0, 200.0)), real.controlPoint(Pt(1600.0, 300.0)))
        val sloppy = ControlPoint(Pt(800.0, 1200.0), real.world(Pt(800.0, 1200.0)) + Vec3(0.0, 0.0, 0.8))

        val clean = assertNotNull(PlanRegistration.fit(good + real.controlPoint(Pt(800.0, 1200.0)), scale))
        assertTrue(clean.residualM < 0.01, "three good points should fit tightly, got ${clean.residualM}")

        val dirty = assertNotNull(PlanRegistration.fit(good + sloppy, scale))
        assertTrue(dirty.residualM > 0.1, "an 80 cm mis-tap should show, got ${dirty.residualM}")
        assertTrue(!dirty.isTrustworthy())
        assertNotNull(dirty.warning)
    }

    @Test
    fun `one point is not enough and coincident points are refused`() {
        val real = truth(Vec3(1.0, 0.0, 0.0))
        assertNull(PlanRegistration.fit(listOf(real.controlPoint(Pt(100.0, 100.0))), scale))
        val same = Pt(500.0, 500.0)
        assertNull(PlanRegistration.fit(listOf(real.controlPoint(same), real.controlPoint(same)), scale))
    }

    @Test
    fun `a tilted up vector still produces a horizontal plan`() {
        // The device's gravity estimate is never exactly the world axis; the plan must still lie flat
        // relative to whatever up actually is.
        val up = Vec3(0.05, 1.0, -0.03).normalised()
        val real = TestPlacement(Vec3(1.0, 0.2, 1.0), Vec3(1.0, 0.0, 0.2), up, metresPerPx)
        val result = assertNotNull(
            PlanRegistration.fit(
                listOf(real.controlPoint(Pt(300.0, 300.0)), real.controlPoint(Pt(1400.0, 800.0))),
                scale,
                up = up,
            ),
        )
        val a = result.placement.toWorld(Pt(300.0, 300.0))
        val b = result.placement.toWorld(Pt(1400.0, 1500.0))
        // Both plan points sit at the same height along the real up axis.
        assertTrue(abs((a dot up) - (b dot up)) < 0.01, "the placed plan is not flat")
    }
}
