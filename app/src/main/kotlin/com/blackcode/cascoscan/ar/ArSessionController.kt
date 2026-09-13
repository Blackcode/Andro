package com.blackcode.cascoscan.ar

import android.content.Context
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.blackcode.cascoscan.ar.ArCamera as EngineCamera
import com.blackcode.cascoscan.detect.GrayImage
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What one tap on the world produced: a point on a tracked surface, if there was one. */
data class SurfaceHit(val point: Vec3, val normal: Vec3, val isPlane: Boolean)

/**
 * Everything a single "check this view" needs, gathered while the frame is still alive.
 *
 * Assembled on the GL thread and then handed off, because an ARCore [Frame] is only valid there and only
 * until the next one. Detection takes far longer than a frame, so nothing downstream may depend on it.
 */
data class CaptureBundle(
    /** Luminance of the camera image. YUV's Y plane *is* a greyscale image, so this costs a copy. */
    val gray: GrayImage,
    val intrinsics: CameraIntrinsics,
    val pose: CameraPose,
    /** The surface being looked at, from one hit test at the centre of the view. */
    val surface: SurfaceHit?,
    val camera: EngineCamera,
)

/**
 * Owns the ARCore session and the render loop.
 *
 * The division of labour is deliberate: this class does the things that can only be done on the GL thread
 * with a live frame - painting the camera, reading the pose, one hit test - and hands everything else out
 * as plain values. The geometry that turns those values into positions in the room lives in the engine,
 * where it is tested.
 */
class ArSessionController(private val context: Context) {

    private var session: Session? = null
    private val background = CameraBackgroundRenderer()
    private val captureRequested = AtomicBoolean(false)
    private val controlPointRequested = AtomicBoolean(false)

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var displayRotation = 0
    private var geometryDirty = true

    /** Called every tracked frame, on the GL thread. Keep the work in it trivial. */
    var onTracking: ((EngineCamera) -> Unit)? = null
    var onTrackingStateChanged: ((TrackingState, String?) -> Unit)? = null
    var onCapture: ((CaptureBundle) -> Unit)? = null
    var onControlPoint: ((SurfaceHit?) -> Unit)? = null

    /** True once a session exists; false means this device or install cannot do AR. */
    fun ensureSession(): Boolean {
        session?.let { return true }
        return try {
            val created = Session(context)
            created.configure(
                Config(created).apply {
                    // The newest image every frame: the auditor is moving, and a stale frame would put
                    // markers where the wall was a moment ago.
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    // Walls and slabs, which is the whole subject matter.
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                },
            )
            session = created
            true
        } catch (t: Throwable) {
            Log.w(TAG, "no AR session: ${t.message}")
            onTrackingStateChanged?.invoke(TrackingState.STOPPED, arFailureMessage(t))
            false
        }
    }

    fun resume() {
        runCatching { session?.resume() }
            .onFailure { onTrackingStateChanged?.invoke(TrackingState.STOPPED, arFailureMessage(it)) }
    }

    fun pause() {
        runCatching { session?.pause() }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        displayRotation = rotation
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        geometryDirty = true
    }

    /** Ask for the next tracked frame to be measured. Consumed once. */
    fun requestCapture() = captureRequested.set(true)

    /** Ask for a world point under the centre of the view, for setting a reference point. */
    fun requestControlPoint() = controlPointRequested.set(true)

    val renderer: GLSurfaceView.Renderer = object : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            background.createOnGlThread()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            setDisplayGeometry(displayRotation, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val active = session ?: return
            try {
                if (geometryDirty) {
                    active.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                    geometryDirty = false
                }
                active.setCameraTextureName(background.textureId)
                val frame = active.update()
                background.draw(frame)

                val camera = frame.camera
                if (camera.trackingState != TrackingState.TRACKING) {
                    onTrackingStateChanged?.invoke(camera.trackingState, null)
                    // Drop any pending request: a measurement taken while tracking is lost is worthless.
                    captureRequested.set(false)
                    controlPointRequested.set(false)
                    return
                }

                val engineCamera = toEngineCamera(frame)
                onTracking?.invoke(engineCamera)

                if (controlPointRequested.compareAndSet(true, false)) {
                    onControlPoint?.invoke(hitCentre(frame))
                }
                if (captureRequested.compareAndSet(true, false)) {
                    onCapture?.invoke(capture(frame, engineCamera))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "frame failed: ${t.message}")
            }
        }
    }

    private fun toEngineCamera(frame: Frame): EngineCamera {
        val camera = frame.camera
        val view = FloatArray(16)
        val projection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, NEAR_M, FAR_M)
        val pose = camera.pose
        val x = FloatArray(3).also { pose.getXAxis(it, 0) }
        val y = FloatArray(3).also { pose.getYAxis(it, 0) }
        val z = FloatArray(3).also { pose.getZAxis(it, 0) }
        val t = FloatArray(3).also { pose.getTranslation(it, 0) }
        return EngineCamera(
            position = Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
            right = Vec3(x[0].toDouble(), x[1].toDouble(), x[2].toDouble()),
            up = Vec3(y[0].toDouble(), y[1].toDouble(), y[2].toDouble()),
            // ARCore's camera looks down its own -Z.
            forward = Vec3(-z[0].toDouble(), -z[1].toDouble(), -z[2].toDouble()),
            viewProjection = Mat4.fromColumnMajor(projection) * Mat4.fromColumnMajor(view),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }

    /** One hit test down the middle of the view. Prefers a plane, since that is a real surface. */
    private fun hitCentre(frame: Frame): SurfaceHit? {
        val hits = runCatching {
            frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
        }.getOrDefault(emptyList())
        val planeHit = hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
        }
        val chosen = planeHit ?: hits.firstOrNull() ?: return null
        val pose = chosen.hitPose
        val t = FloatArray(3).also { pose.getTranslation(it, 0) }
        // A plane's pose has its +Y along the surface normal.
        val n = FloatArray(3).also { pose.getYAxis(it, 0) }
        return SurfaceHit(
            point = Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
            normal = Vec3(n[0].toDouble(), n[1].toDouble(), n[2].toDouble()).normalised(),
            isPlane = planeHit != null,
        )
    }

    private fun capture(frame: Frame, camera: EngineCamera): CaptureBundle {
        val image = frame.acquireCameraImage()
        val gray = try {
            luminanceOf(image)
        } finally {
            image.close()
        }
        val focal = FloatArray(2)
        val principal = FloatArray(2)
        val dimensions = frame.camera.imageIntrinsics.imageDimensions
        frame.camera.imageIntrinsics.getFocalLength(focal, 0)
        frame.camera.imageIntrinsics.getPrincipalPoint(principal, 0)

        val pose = frame.camera.pose
        val x = FloatArray(3).also { pose.getXAxis(it, 0) }
        val y = FloatArray(3).also { pose.getYAxis(it, 0) }
        val z = FloatArray(3).also { pose.getZAxis(it, 0) }
        val t = FloatArray(3).also { pose.getTranslation(it, 0) }

        return CaptureBundle(
            gray = gray,
            intrinsics = CameraIntrinsics(
                focalX = focal[0].toDouble(),
                focalY = focal[1].toDouble(),
                principalX = principal[0].toDouble(),
                principalY = principal[1].toDouble(),
                imageWidth = dimensions[0],
                imageHeight = dimensions[1],
            ),
            pose = CameraPose(
                position = Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
                right = Vec3(x[0].toDouble(), x[1].toDouble(), x[2].toDouble()),
                up = Vec3(y[0].toDouble(), y[1].toDouble(), y[2].toDouble()),
                forward = Vec3(-z[0].toDouble(), -z[1].toDouble(), -z[2].toDouble()),
            ),
            surface = hitCentre(frame),
            camera = camera,
        )
    }

    /**
     * The Y plane of a YUV_420_888 frame, which is luminance already - so the detector's input costs one
     * row-wise copy rather than a colour conversion.
     */
    private fun luminanceOf(image: Image): GrayImage {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val available = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, available)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, y * width, minOf(width, available))
            } else {
                var source = 0
                for (x in 0 until width) {
                    if (source >= available) break
                    out[y * width + x] = row[source]
                    source += pixelStride
                }
            }
        }
        return GrayImage(width, height, out)
    }

    private fun arFailureMessage(t: Throwable): String = when (t::class.java.simpleName) {
        "UnavailableArcoreNotInstalledException", "UnavailableUserDeclinedInstallationException" ->
            "Google Play Services for AR is not installed. Install it to use the augmented reality view."
        "UnavailableDeviceNotCompatibleException" ->
            "This device does not support augmented reality. You can still review the drawing and tick " +
                "penetrations off by hand."
        "UnavailableApkTooOldException" -> "Google Play Services for AR is out of date; update it."
        "UnavailableSdkTooOldException" -> "This app is too old for the installed AR services."
        "SecurityException" -> "Camera permission is needed for the augmented reality view."
        else -> t.message ?: "Augmented reality could not start on this device."
    }

    private companion object {
        const val TAG = "CascoScan/AR"

        /** Near and far planes, in metres. A room is small; there is no need for a long view. */
        const val NEAR_M = 0.05f
        const val FAR_M = 40f
    }
}
