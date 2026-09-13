package com.blackcode.cascoscan.ar

import com.blackcode.cascoscan.detect.Pt
import kotlin.math.tan

/**
 * An **independent** way of placing a drawing in the room, written out longhand so the tests are not
 * simply asking [PlanPlacement] to agree with itself.
 *
 * The orientation convention is the thing worth checking, and it is checkable: a plan has y increasing
 * downwards and is seen from above, so going from page x to page y turns clockwise when viewed from
 * above - which in vector terms is `dirX cross dirY = -up`. Taking `dirY = dirX cross up` satisfies that,
 * and a registration that quietly mirrored the plan would disagree with points built this way.
 */
class TestPlacement(
    val origin: Vec3,
    dirX: Vec3,
    val up: Vec3 = Vec3.UP,
    val metresPerPx: Double,
) {
    val dirX: Vec3 = dirX.normalised()
    val dirY: Vec3 = (dirX.normalised() cross up.normalised()).normalised()

    fun world(onDrawing: Pt, heightAboveFloor: Double = 0.0): Vec3 =
        origin +
            dirX * (onDrawing.x * metresPerPx) +
            dirY * (onDrawing.y * metresPerPx) +
            up.normalised() * heightAboveFloor

    fun controlPoint(onDrawing: Pt) = ControlPoint(onDrawing, world(onDrawing))
}

/** Column-major perspective projection, the same convention ARCore hands out. */
fun perspective(fovYDeg: Double, aspect: Double, near: Double, far: Double): Mat4 {
    val f = 1.0 / tan(Math.toRadians(fovYDeg) / 2.0)
    val m = DoubleArray(16)
    m[0] = f / aspect                       // (0,0)
    m[5] = f                                // (1,1)
    m[10] = (far + near) / (near - far)     // (2,2)
    m[11] = -1.0                            // (3,2)
    m[14] = 2.0 * far * near / (near - far) // (2,3)
    return Mat4(m)
}

/** Column-major view matrix looking from [eye] towards [target]. */
fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3.UP): Mat4 {
    val back = (eye - target).normalised()
    val right = (up cross back).normalised()
    val trueUp = (back cross right).normalised()
    val m = DoubleArray(16)
    fun set(row: Int, col: Int, value: Double) {
        m[col * 4 + row] = value
    }
    set(0, 0, right.x); set(0, 1, right.y); set(0, 2, right.z); set(0, 3, -(right dot eye))
    set(1, 0, trueUp.x); set(1, 1, trueUp.y); set(1, 2, trueUp.z); set(1, 3, -(trueUp dot eye))
    set(2, 0, back.x); set(2, 1, back.y); set(2, 2, back.z); set(2, 3, -(back dot eye))
    set(3, 3, 1.0)
    return Mat4(m)
}

/** An [ArCamera] standing at [eye] looking at [target], with a phone-like field of view. */
fun cameraAt(
    eye: Vec3,
    target: Vec3,
    viewportWidth: Int = 1080,
    viewportHeight: Int = 1920,
    fovYDeg: Double = 60.0,
): ArCamera {
    val view = lookAt(eye, target)
    val projection = perspective(fovYDeg, viewportWidth.toDouble() / viewportHeight, 0.05, 50.0)
    val back = (eye - target).normalised()
    val right = (Vec3.UP cross back).normalised()
    return ArCamera(
        position = eye,
        right = right,
        up = (back cross right).normalised(),
        forward = -back,
        viewProjection = projection * view,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    )
}
