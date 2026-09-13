package com.blackcode.cascoscan.ar

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.Mappers
import com.blackcode.cascoscan.data.SheetEntity
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.pdf.PdfPageSource
import com.blackcode.cascoscan.site.SiteConfig
import com.blackcode.cascoscan.site.SiteDetector
import com.blackcode.cascoscan.site.SitePhoto
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the augmented reality inspection: pin the drawing to the room, then walk it.
 *
 * Registration comes first because nothing can be drawn before it. The auditor aims at a feature they can
 * also find on the sheet - a column face, a door reveal, a grid intersection - taps to fix it in the room,
 * then taps the same feature on the drawing. Twice is enough, because the plot scale already fixes the
 * scale and gravity fixes the tilt, leaving only a heading and an offset.
 *
 * After that the overlay is live, and "check this view" measures what the camera can see.
 */
class ArScanViewModel(
    private val context: Context,
    private val projectId: String,
    private val sheetId: String,
    private val repository: AuditRepository,
    private val siteConfig: SiteConfig,
) : ViewModel() {

    enum class Step { REGISTER, OVERLAY, RESULT }

    val sheet: StateFlow<SheetEntity?> =
        repository.sheet(sheetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val required: StateFlow<List<Penetration>> =
        repository.penetrations(sheetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _step = MutableStateFlow(Step.REGISTER)
    val step: StateFlow<Step> = _step

    private val _sheetPreview = MutableStateFlow<ImageBitmap?>(null)
    val sheetPreview: StateFlow<ImageBitmap?> = _sheetPreview

    /** A world point fixed by aiming, waiting for the auditor to say where it is on the drawing. */
    private val _pendingWorldPoint = MutableStateFlow<Vec3?>(null)
    val pendingWorldPoint: StateFlow<Vec3?> = _pendingWorldPoint

    private val _controlPoints = MutableStateFlow<List<ControlPoint>>(emptyList())
    val controlPoints: StateFlow<List<ControlPoint>> = _controlPoints

    private val _registration = MutableStateFlow<PlanRegistration.Result?>(null)
    val registration: StateFlow<PlanRegistration.Result?> = _registration

    private val _geometry = MutableStateFlow(TargetGeometry.ON_WALL)
    val geometry: StateFlow<TargetGeometry> = _geometry

    private val _targets = MutableStateFlow<List<ExpectedTarget>>(emptyList())
    val targets: StateFlow<List<ExpectedTarget>> = _targets

    private val _match = MutableStateFlow<ArMatcher.Result?>(null)
    val match: StateFlow<ArMatcher.Result?> = _match

    private val _tracking = MutableStateFlow<String?>("Move the phone slowly to let it find the room.")
    val tracking: StateFlow<String?> = _tracking

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * The live camera, held as Compose state rather than a flow on purpose.
     *
     * It changes every frame. Read inside a `Canvas` draw lambda it costs a redraw and no recomposition,
     * which is what keeps a sixty-times-a-second overlay affordable; the same value in a `StateFlow`
     * collected by the composable would recompose the whole subtree instead.
     */
    val liveCamera: MutableState<ArCamera?> = mutableStateOf(null)

    init {
        viewModelScope.launch { renderSheet() }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setGeometry(geometry: TargetGeometry) {
        _geometry.value = geometry
        rebuildTargets()
    }

    // --- Registration -------------------------------------------------------------------------

    /** Called from the render loop when the auditor fixes a reference point by aiming at it. */
    fun onControlPointHit(hit: SurfaceHit?) {
        if (hit == null) {
            _message.value = "Nothing was found under the crosshair. Aim at a surface and try again."
            return
        }
        _pendingWorldPoint.value = hit.point
        _message.value = "Now tap that same feature on the drawing."
    }

    /** The drawing half of a reference point. */
    fun tapDrawing(onDrawing: Pt) {
        val world = _pendingWorldPoint.value
        if (world == null) {
            _message.value = "Aim at the feature and press Fix point first."
            return
        }
        _pendingWorldPoint.value = null
        _controlPoints.value = _controlPoints.value + ControlPoint(onDrawing, world)
        fitRegistration()
    }

    fun clearControlPoints() {
        _controlPoints.value = emptyList()
        _pendingWorldPoint.value = null
        _registration.value = null
        _targets.value = emptyList()
        _step.value = Step.REGISTER
    }

    private fun fitRegistration() {
        viewModelScope.launch {
            val sheetEntity = repository.findSheet(sheetId) ?: return@launch
            val scale = Mappers.scaleOf(sheetEntity)
            if (scale == null) {
                _message.value = "Set the plot scale for this drawing set first; without it the overlay " +
                    "cannot be placed."
                return@launch
            }
            val result = PlanRegistration.fit(_controlPoints.value, scale)
            _registration.value = result
            if (result != null) {
                rebuildTargets()
                _step.value = Step.OVERLAY
                result.warning?.let { _message.value = it }
            }
        }
    }

    private fun rebuildTargets() {
        val placement = _registration.value?.placement ?: return
        viewModelScope.launch {
            val all = required.first()
            val geometry = _geometry.value
            _targets.value = ArTargets.build(all, placement) { geometry }
        }
    }

    // --- The live overlay ---------------------------------------------------------------------

    fun onTracking(camera: ArCamera) {
        liveCamera.value = camera
        if (_tracking.value != null) _tracking.value = null
    }

    fun onTrackingLost(state: TrackingState, reason: String?) {
        liveCamera.value = null
        _tracking.value = reason ?: when (state) {
            TrackingState.PAUSED -> "Tracking paused. Move the phone slowly, and give it more light or more texture to look at."
            TrackingState.STOPPED -> "Tracking stopped."
            else -> null
        }
    }

    /** Targets worth drawing this frame. Cheap enough to run per frame for a sheet's worth of holes. */
    fun projectedTargets(camera: ArCamera): List<ProjectedTarget> {
        val placement = _registration.value?.placement ?: return emptyList()
        return ArTargets.project(_targets.value, camera, placement)
    }

    /**
     * The holes found by the last measurement, projected for this frame.
     *
     * They are drawn from their world positions rather than from where they sat in the captured image, so
     * they stay on the wall as the phone moves instead of floating at fixed screen positions.
     */
    fun projectedObservations(camera: ArCamera): List<Pair<ObservedInWorld, Pt>> {
        val observed = _match.value ?: return emptyList()
        val all = observed.matched.map { it.observed } + observed.unexpected
        return all.mapNotNull { observation ->
            ArTargets.projectToScreen(observation.world, camera)?.let { observation to it }
        }
    }

    // --- Measuring what is there --------------------------------------------------------------

    /**
     * Called from the render loop with everything a measurement needs, gathered while the frame was alive.
     *
     * Detection runs off the render thread; the positions come from casting rays through the detections
     * onto the one surface the frame's hit test found, and the diameters from that surface's distance and
     * the camera's own focal length - so no calibration is asked of anyone.
     */
    fun onCapture(bundle: CaptureBundle) {
        val placement = _registration.value?.placement ?: return
        _busy.value = true
        viewModelScope.launch {
            val surface = bundle.surface
            if (surface == null) {
                _busy.value = false
                _message.value = "No surface was found under the crosshair. Aim at the wall and try again."
                return@launch
            }

            val outcome = withContext(Dispatchers.Default) {
                val detection = SiteDetector(siteConfig).detect(SitePhoto(bundle.gray, null, null))
                val intrinsics = bundle.intrinsics.scaled(detection.downsampleFactor)
                val observed = detection.accepted.mapNotNull { observation ->
                    val world = ArRayCaster.worldPointOnPlane(
                        observation.center,
                        intrinsics,
                        bundle.pose,
                        surface.point,
                        surface.normal,
                    ) ?: return@mapNotNull null
                    val distance = world.distanceTo(bundle.pose.position)
                    ObservedInWorld(
                        id = observation.id,
                        world = world,
                        diameterMm = ArPhotogrammetry.sizeMm(
                            observation.features.ellipse.diameterPx,
                            distance,
                            intrinsics.focalX,
                        ),
                        confidence = observation.confidence,
                        screen = observation.center,
                        photoUri = null,
                    )
                }
                // Only what was actually in view is judged, which is also how the auditor reads it.
                val inView = ArTargets.project(_targets.value, bundle.camera, placement).map { it.target }
                ArMatcher.match(inView, observed, placement)
            }

            _match.value = outcome
            _busy.value = false
            _step.value = Step.RESULT
            val obliquity = ArRayCaster.surfaceObliquityDeg(bundle.pose, surface.normal)
            if (obliquity > 55.0) {
                _message.value = "That wall was seen at %.0f degrees off square, so the diameters are rough."
                    .format(obliquity)
            }
        }
    }

    fun backToOverlay() {
        _match.value = null
        _step.value = Step.OVERLAY
    }

    fun apply() {
        val result = _match.value ?: return
        _busy.value = true
        viewModelScope.launch {
            val updates = ArMatcher.toChecklistUpdates(result)
            repository.applySiteFindings(projectId, sheetId, updates)
            _busy.value = false
            _match.value = null
            _step.value = Step.OVERLAY
            _message.value = "Recorded ${updates.size}: ${result.matched.size} found, " +
                "${result.missing.size} missing."
        }
    }

    private suspend fun renderSheet() {
        val sheetEntity = repository.findSheet(sheetId) ?: return
        val project = repository.findProject(projectId) ?: return
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                PdfPageSource.open(context, Uri.parse(project.documentUri)).use {
                    it.renderPreview(sheetEntity.pageIndex, maxPixels = SHEET_PREVIEW_PIXELS)
                }
            }.getOrNull()
        }
        _sheetPreview.value = bitmap?.asImageBitmap()
    }

    private companion object {
        const val SHEET_PREVIEW_PIXELS = 2_000_000
    }
}
