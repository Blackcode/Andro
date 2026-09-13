package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.Pt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArRayCasterTest {

    // A 1440x1080 camera image with a 60-degree horizontal field of view.
    private val intrinsics = CameraIntrinsics(
        focalX = 1247.0,
        focalY = 1247.0,
        principalX = 720.0,
        principalY = 540.0,
        imageWidth = 1440,
        imageHeight = 1080,
    )

    /** Standing at the origin looking along -Z, which is how ARCore's camera is oriented. */
    private val pose = CameraPose(
        position = Vec3.ZERO,
        right = Vec3(1.0, 0.0, 0.0),
        up = Vec3(0.0, 1.0, 0.0),
        forward = Vec3(0.0, 0.0, -1.0),
    )

    @Test
    fun `the principal point looks straight ahead`() {
        val ray = ArRayCaster.rayThrough(Pt(720.0, 540.0), intrinsics, pose)
        assertTrue((ray.direction - pose.forward).length < 1e-9, "got ${ray.direction}")
    }

    @Test
    fun `a pixel one focal length to the right looks 45 degrees right`() {
        val ray = ArRayCaster.rayThrough(Pt(720.0 + 1247.0, 540.0), intrinsics, pose)
        val angle = Math.toDegrees(kotlin.math.acos((ray.direction dot pose.forward).coerceIn(-1.0, 1.0)))
        assertTrue(abs(angle - 45.0) < 0.01, "angle was $angle")
        assertTrue((ray.direction dot pose.right) > 0.0, "should look right, not left")
    }

    @Test
    fun `image y runs downwards while the camera's y runs up`() {
        // The sign error that would mirror every measurement about the horizon.
        val lower = ArRayCaster.rayThrough(Pt(720.0, 540.0 + 300.0), intrinsics, pose)
        assertTrue((lower.direction dot pose.up) < 0.0, "a pixel below centre must look downwards")
        val upper = ArRayCaster.rayThrough(Pt(720.0, 540.0 - 300.0), intrinsics, pose)
        assertTrue((upper.direction dot pose.up) > 0.0, "a pixel above centre must look upwards")
    }

    @Test
    fun `a pixel lands on the wall it is pointed at`() {
        // A wall 3 m ahead, facing the camera.
        val planePoint = Vec3(0.0, 0.0, -3.0)
        val normal = Vec3(0.0, 0.0, 1.0)

        val centre = assertNotNull(
            ArRayCaster.worldPointOnPlane(Pt(720.0, 540.0), intrinsics, pose, planePoint, normal),
        )
        assertTrue(centre.distanceTo(Vec3(0.0, 0.0, -3.0)) < 1e-6, "got $centre")

        // 1247 px right at 3 m is 3 m across, by similar triangles.
        val right = assertNotNull(
            ArRayCaster.worldPointOnPlane(Pt(720.0 + 1247.0, 540.0), intrinsics, pose, planePoint, normal),
        )
        assertTrue(abs(right.x - 3.0) < 0.01, "expected 3 m to the right, got ${right.x}")
        assertTrue(abs(right.z - (-3.0)) < 1e-6)
    }

    @Test
    fun `a ray that misses the surface produces nothing rather than a position`() {
        // A wall behind the camera cannot be what a forward-looking pixel is on.
        assertNull(
            ArRayCaster.worldPointOnPlane(
                Pt(720.0, 540.0),
                intrinsics,
                pose,
                planePoint = Vec3(0.0, 0.0, 3.0),
                planeNormal = Vec3(0.0, 0.0, 1.0),
            ),
        )
        // A surface exactly edge-on is never met.
        assertNull(
            ArRayCaster.worldPointOnPlane(
                Pt(720.0, 540.0),
                intrinsics,
                pose,
                planePoint = Vec3(0.0, 0.0, -3.0),
                planeNormal = Vec3(0.0, 1.0, 0.0),
            ),
        )
    }

    @Test
    fun `intrinsics scale with the reduced image detection measures`() {
        val reduced = intrinsics.scaled(2)
        assertTrue(abs(reduced.focalX - 623.5) < 1e-9)
        assertTrue(abs(reduced.principalX - 360.0) < 1e-9)
        assertTrue(reduced.imageWidth == 720)

        // The same physical direction, whether computed at full or reduced resolution.
        val full = ArRayCaster.rayThrough(Pt(1000.0, 700.0), intrinsics, pose)
        val half = ArRayCaster.rayThrough(Pt(500.0, 350.0), reduced, pose)
        assertTrue((full.direction - half.direction).length < 1e-9, "${full.direction} vs ${half.direction}")
    }

    @Test
    fun `obliquity reports how far off square the camera is`() {
        // Straight at a wall.
        assertTrue(abs(ArRayCaster.surfaceObliquityDeg(pose, Vec3(0.0, 0.0, 1.0))) < 1e-9)
        // Looking along a wall's face.
        assertTrue(abs(ArRayCaster.surfaceObliquityDeg(pose, Vec3(0.0, 1.0, 0.0)) - 90.0) < 1e-9)
        // 60 degrees off square.
        val tilted = CameraPose(
            Vec3.ZERO,
            Vec3(1.0, 0.0, 0.0),
            Vec3(0.0, 1.0, 0.0),
            Vec3(kotlin.math.sin(Math.toRadians(60.0)), 0.0, -kotlin.math.cos(Math.toRadians(60.0))),
        )
        assertTrue(abs(ArRayCaster.surfaceObliquityDeg(tilted, Vec3(0.0, 0.0, 1.0)) - 60.0) < 0.01)
    }

    @Test
    fun `a pose that is not axis aligned still works`() {
        // Rotated 30 degrees to the left about up.
        val angle = Math.toRadians(30.0)
        val rotated = CameraPose(
            position = Vec3(1.0, 1.5, 2.0),
            right = Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)),
            up = Vec3(0.0, 1.0, 0.0),
            forward = Vec3(kotlin.math.sin(angle), 0.0, -kotlin.math.cos(angle)),
        )
        val ray = ArRayCaster.rayThrough(Pt(720.0, 540.0), intrinsics, rotated)
        assertTrue((ray.direction - rotated.forward).length < 1e-9)
        assertTrue(ray.origin.distanceTo(rotated.position) < 1e-9)
    }
}

/**
 * Deriving the camera's axes from its rotation, so the Android side depends on four numbers rather than on
 * which axis accessors a given ARCore version happens to expose.
 */
class QuaternionTest {

    private fun assertClose(expected: Vec3, actual: Vec3, what: String) {
        assertTrue((expected - actual).length < 1e-9, "$what: expected $expected, got $actual")
    }

    @Test
    fun `the identity rotation leaves the axes alone`() {
        val (x, y, z) = Quaternion.IDENTITY.basis()
        assertClose(Vec3(1.0, 0.0, 0.0), x, "x")
        assertClose(Vec3(0.0, 1.0, 0.0), y, "y")
        assertClose(Vec3(0.0, 0.0, 1.0), z, "z")
    }

    @Test
    fun `a quarter turn about up takes x to minus z`() {
        val half = Math.toRadians(90.0) / 2
        val q = Quaternion(0.0, kotlin.math.sin(half), 0.0, kotlin.math.cos(half))
        val (x, y, z) = q.basis()
        assertClose(Vec3(0.0, 0.0, -1.0), x, "x")
        assertClose(Vec3(0.0, 1.0, 0.0), y, "y")
        assertClose(Vec3(1.0, 0.0, 0.0), z, "z")
    }

    @Test
    fun `the basis stays right handed`() {
        val q = Quaternion(0.183, 0.365, 0.548, 0.730).let { raw ->
            val length = kotlin.math.sqrt(raw.x * raw.x + raw.y * raw.y + raw.z * raw.z + raw.w * raw.w)
            Quaternion(raw.x / length, raw.y / length, raw.z / length, raw.w / length)
        }
        val (x, y, z) = q.basis()
        assertTrue(abs(x.length - 1.0) < 1e-9, "x not unit: ${x.length}")
        assertTrue(abs(y.length - 1.0) < 1e-9, "y not unit: ${y.length}")
        assertTrue(abs(z.length - 1.0) < 1e-9, "z not unit: ${z.length}")
        assertTrue(abs(x dot y) < 1e-9, "x and y not perpendicular")
        assertClose(z, x cross y, "right-handedness")
    }

    @Test
    fun `the camera looks down its own negated z`() {
        // ARCore's convention, and the sign that would otherwise point every marker behind the viewer.
        val (right, up, forward) = Quaternion.IDENTITY.cameraAxes()
        assertClose(Vec3(1.0, 0.0, 0.0), right, "right")
        assertClose(Vec3(0.0, 1.0, 0.0), up, "up")
        assertClose(Vec3(0.0, 0.0, -1.0), forward, "forward")
    }
}
