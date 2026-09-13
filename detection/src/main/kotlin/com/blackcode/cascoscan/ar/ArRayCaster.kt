package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.Pt

/** Pinhole parameters of the camera, in the pixels of the image they describe. */
data class CameraIntrinsics(
    val focalX: Double,
    val focalY: Double,
    val principalX: Double,
    val principalY: Double,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    /** The same camera, expressed in the pixels of an image reduced by [factor]. */
    fun scaled(factor: Int): CameraIntrinsics {
        require(factor >= 1)
        if (factor == 1) return this
        return CameraIntrinsics(
            focalX = focalX / factor,
            focalY = focalY / factor,
            principalX = principalX / factor,
            principalY = principalY / factor,
            imageWidth = imageWidth / factor,
            imageHeight = imageHeight / factor,
        )
    }
}

/** Where the camera was and which way it faced, as ARCore's pose axes. */
data class CameraPose(val position: Vec3, val right: Vec3, val up: Vec3, val forward: Vec3)

/**
 * Works out where in the room a pixel of the camera image was looking.
 *
 * This exists to keep the hard part off the GL thread and inside something testable. ARCore can answer
 * "what is under this pixel" directly with a hit test, but only against a live `Frame`, and only on the
 * thread that produced it - while detecting holes takes long enough that the frame is gone by the time
 * there is anything to ask about.
 *
 * So the frame is used for one thing only: a single hit test at the centre of the view, which gives the
 * surface being looked at. Everything after that is arithmetic - cast a ray through each detected hole and
 * intersect it with that surface - and arithmetic can be checked.
 *
 * The assumption that buys this is that the holes in one shot are on one surface, which is what a wall or
 * a slab is.
 */
object ArRayCaster {

    /**
     * Ray from the camera through an image pixel.
     *
     * The camera looks down its own -Z with +Y up, while image coordinates run +y *down*, hence the
     * negated vertical term - the sign error that would otherwise mirror every measurement about the
     * horizon and be very hard to spot on a device.
     */
    fun rayThrough(pixel: Pt, intrinsics: CameraIntrinsics, pose: CameraPose): Ray {
        val x = (pixel.x - intrinsics.principalX) / intrinsics.focalX
        val y = -(pixel.y - intrinsics.principalY) / intrinsics.focalY
        val direction = (pose.right * x + pose.up * y + pose.forward).normalised()
        return Ray(pose.position, direction)
    }

    /**
     * Where an image pixel meets a surface, or null when the ray misses it - which happens for a pixel
     * looking past the edge of the wall, and must not be turned into a position anyway.
     */
    fun worldPointOnPlane(
        pixel: Pt,
        intrinsics: CameraIntrinsics,
        pose: CameraPose,
        planePoint: Vec3,
        planeNormal: Vec3,
    ): Vec3? = rayThrough(pixel, intrinsics, pose).intersectPlane(planePoint, planeNormal)

    /**
     * How obliquely the camera is looking at the surface, in degrees from straight on.
     *
     * Worth reporting: a hole measured on a surface seen at a glancing angle is measured badly, in exactly
     * the way the ellipse's axis ratio already warns about, and the two should agree.
     */
    fun surfaceObliquityDeg(pose: CameraPose, planeNormal: Vec3): Double {
        val cosAngle = kotlin.math.abs(pose.forward.normalised() dot planeNormal.normalised()).coerceIn(0.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosAngle))
    }
}
