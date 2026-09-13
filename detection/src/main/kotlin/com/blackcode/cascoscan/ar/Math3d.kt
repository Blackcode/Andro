package com.blackcode.cascoscan.ar

import kotlin.math.abs
import kotlin.math.sqrt

/** A point or direction in AR world space, in metres. */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(f: Double) = Vec3(x * f, y * f, z * f)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val length: Double get() = sqrt(this dot this)

    fun normalised(): Vec3 {
        val l = length
        return if (l < 1e-9) ZERO else Vec3(x / l, y / l, z / l)
    }

    fun distanceTo(o: Vec3) = (this - o).length

    /** Any unit vector perpendicular to this one. Used to seed a horizontal basis from gravity. */
    fun anyPerpendicular(): Vec3 {
        // Cross with whichever cardinal axis is least aligned, so the result is never degenerate.
        val axis = if (abs(x) <= abs(y) && abs(x) <= abs(z)) {
            Vec3(1.0, 0.0, 0.0)
        } else if (abs(y) <= abs(z)) {
            Vec3(0.0, 1.0, 0.0)
        } else {
            Vec3(0.0, 0.0, 1.0)
        }
        return (this cross axis).normalised()
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)

        /** ARCore's world is y-up, so this is the default when gravity is not otherwise known. */
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}

/**
 * A 4x4 matrix in the column-major layout OpenGL and ARCore both use, so a matrix arriving from
 * `Camera.getViewMatrix` can be wrapped without rearranging anything.
 *
 * Element (row, col) lives at `m[col * 4 + row]`, which is the ordering people get wrong; every access
 * here goes through [get] so the convention is stated once.
 */
class Mat4(val m: DoubleArray) {

    init {
        require(m.size == 16) { "a 4x4 matrix needs 16 elements, got ${m.size}" }
    }

    operator fun get(row: Int, col: Int): Double = m[col * 4 + row]

    operator fun times(o: Mat4): Mat4 {
        val out = DoubleArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) sum += this[row, k] * o[k, col]
                out[col * 4 + row] = sum
            }
        }
        return Mat4(out)
    }

    /** Transforms a point, returning homogeneous (x, y, z, w) so the caller can see behind-camera cases. */
    fun transform(p: Vec3): DoubleArray = DoubleArray(4) { row ->
        this[row, 0] * p.x + this[row, 1] * p.y + this[row, 2] * p.z + this[row, 3]
    }

    companion object {
        fun identity() = Mat4(
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        /** Wraps the float arrays ARCore hands out, which are already column-major. */
        fun fromColumnMajor(values: FloatArray): Mat4 {
            require(values.size == 16) { "expected 16 floats, got ${values.size}" }
            return Mat4(DoubleArray(16) { values[it].toDouble() })
        }
    }
}

/** A ray from the camera through a screen point, for asking what the user tapped. */
data class Ray(val origin: Vec3, val direction: Vec3) {

    fun at(t: Double) = origin + direction * t

    /**
     * Where this ray meets the plane through [pointOnPlane] with normal [normal], or null when it runs
     * parallel to it or would meet it behind the camera.
     */
    fun intersectPlane(pointOnPlane: Vec3, normal: Vec3): Vec3? {
        val denominator = direction dot normal
        if (abs(denominator) < 1e-9) return null
        val t = ((pointOnPlane - origin) dot normal) / denominator
        return if (t <= 0.0) null else at(t)
    }
}

/**
 * A rotation as a unit quaternion, and the basis it turns the world axes into.
 *
 * Exists so the Android side never has to depend on which axis accessors a given ARCore version exposes:
 * a pose's rotation is always available as four numbers, and the three axis vectors follow from them by
 * arithmetic that can be tested here rather than discovered on a device.
 */
data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double) {

    /** The rotated X, Y and Z axes: the columns of the equivalent rotation matrix. */
    fun basis(): Triple<Vec3, Vec3, Vec3> {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        val axisX = Vec3(1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy))
        val axisY = Vec3(2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx))
        val axisZ = Vec3(2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy))
        return Triple(axisX, axisY, axisZ)
    }

    /**
     * The camera basis ARCore's convention implies: the pose's X and Y axes, and **forward as the negated
     * Z axis**, because an ARCore camera looks down its own -Z.
     */
    fun cameraAxes(): Triple<Vec3, Vec3, Vec3> {
        val (axisX, axisY, axisZ) = basis()
        return Triple(axisX, axisY, -axisZ)
    }

    companion object {
        val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)
    }
}
