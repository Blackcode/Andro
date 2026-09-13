package com.blackcode.cascoscan.site

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.Mappers
import com.blackcode.cascoscan.data.SheetEntity
import com.blackcode.cascoscan.detect.GrayImage
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.pdf.PdfPageSource
import com.blackcode.cascoscan.report.Exporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives one site inspection: where you are on the drawing, what the camera sees there, and which of the
 * penetrations the drawing asks for are actually in the wall.
 *
 * The steps are in this order because each one supplies something the next cannot work without. Locating
 * yourself narrows "required" from the whole sheet to the few holes that could be in frame - without that
 * the comparison is meaningless, because three holes in a photograph would be matched against two hundred
 * on the sheet and the rest all reported missing. Calibration is what turns a measured ellipse into
 * millimetres, and is offered rather than demanded: skipping it still finds the holes and still says which
 * are absent, it only withholds any claim about their size.
 */
class SiteScanViewModel(
    private val context: Context,
    private val projectId: String,
    private val sheetId: String,
    private val repository: AuditRepository,
    private val siteConfig: SiteConfig,
) : ViewModel() {

    enum class Step {
        /** Tap the drawing to say roughly where you are standing. */
        LOCATE,

        /** Photograph the wall or slab. */
        CAPTURE,

        /** Tap two points a known distance apart, so sizes can be measured. */
        CALIBRATE,

        /** Check the matches, then write them to the checklist. */
        REVIEW,
    }

    val sheet: StateFlow<SheetEntity?> =
        repository.sheet(sheetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val required: StateFlow<List<Penetration>> =
        repository.penetrations(sheetId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _step = MutableStateFlow(Step.LOCATE)
    val step: StateFlow<Step> = _step

    private val _sheetPreview = MutableStateFlow<ImageBitmap?>(null)
    val sheetPreview: StateFlow<ImageBitmap?> = _sheetPreview

    private val _tapOnDrawing = MutableStateFlow<Pt?>(null)
    val tapOnDrawing: StateFlow<Pt?> = _tapOnDrawing

    private val _zoneRadiusMm = MutableStateFlow(DEFAULT_ZONE_MM)
    val zoneRadiusMm: StateFlow<Double> = _zoneRadiusMm

    private val _photo = MutableStateFlow<ImageBitmap?>(null)
    val photo: StateFlow<ImageBitmap?> = _photo

    private val _detection = MutableStateFlow<SitePhotoResult?>(null)
    val detection: StateFlow<SitePhotoResult?> = _detection

    private val _calibrationPoints = MutableStateFlow<List<Pt>>(emptyList())
    val calibrationPoints: StateFlow<List<Pt>> = _calibrationPoints

    private val _knownDistanceMm = MutableStateFlow("1000")
    val knownDistanceMm: StateFlow<String> = _knownDistanceMm

    private val _mmPerPx = MutableStateFlow<Double?>(null)
    val mmPerPx: StateFlow<Double?> = _mmPerPx

    private val _strategy = MutableStateFlow(SiteMatcher.Strategy.ORDER_AND_SIZE)
    val strategy: StateFlow<SiteMatcher.Strategy> = _strategy

    private val _match = MutableStateFlow<SiteMatcher.Result?>(null)
    val match: StateFlow<SiteMatcher.Result?> = _match

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied

    /** The photograph everything was measured on; kept so re-detection lands in the same pixel space. */
    private var analysed: GrayImage? = null
    private var photoFile: File? = null

    val acceptThreshold: Double get() = siteConfig.acceptThreshold

    init {
        viewModelScope.launch { renderSheet() }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setZoneRadius(mm: Double) {
        _zoneRadiusMm.value = mm
        rematch()
    }

    fun tapDrawing(point: Pt) {
        _tapOnDrawing.value = point
    }

    fun toCapture() {
        if (_tapOnDrawing.value == null) {
            _message.value = "Tap the drawing where you are standing first."
            return
        }
        _step.value = Step.CAPTURE
    }

    fun backToLocate() {
        _step.value = Step.LOCATE
    }

    /** Destination for the next shot, inside the app's own photo directory. */
    fun newPhotoTarget(): File {
        val (file, _) = Exporter.newPhotoTarget(context, "site-$sheetId")
        photoFile = file
        return file
    }

    fun photoUri(): Uri? = photoFile?.let { Exporter.uriFor(context, it) }

    /**
     * Decodes the shot and runs detection once without a scale, to find the holes and - importantly - to
     * settle the pixel space that the calibration taps and any later re-detection will share.
     */
    fun onPhotoCaptured(file: File) {
        photoFile = file
        _busy.value = true
        viewModelScope.launch {
            val bitmap = CameraCapture.decode(file)
            if (bitmap == null) {
                _busy.value = false
                _message.value = "That photograph could not be read. Try again."
                return@launch
            }
            _photo.value = bitmap.asImageBitmap()
            val result = withContext(Dispatchers.Default) {
                val gray = CameraCapture.toGray(bitmap)
                SiteDetector(siteConfig).detect(SitePhoto(gray, null, photoUri()?.toString()))
            }
            analysed = result.workingImage
            _detection.value = result
            _calibrationPoints.value = emptyList()
            _mmPerPx.value = null
            _busy.value = false
            _step.value = Step.CALIBRATE
            if (result.observations.isEmpty()) {
                _message.value = "Nothing found in that photograph. Check the light and the framing."
            }
        }
    }

    fun tapPhoto(point: Pt) {
        val points = _calibrationPoints.value
        _calibrationPoints.value = if (points.size >= 2) listOf(point) else points + point
    }

    fun setKnownDistance(text: String) {
        _knownDistanceMm.value = text.filter { it.isDigit() }
    }

    fun clearCalibration() {
        _calibrationPoints.value = emptyList()
    }

    /**
     * Turns two taps and a real distance into millimetres per pixel, then re-runs detection so sizes are
     * measured. Re-detection runs on the already-reduced image, so the scale, the markers and the taps all
     * refer to one pixel space rather than three.
     */
    fun applyCalibration() {
        val points = _calibrationPoints.value
        val distance = _knownDistanceMm.value.toDoubleOrNull()
        val working = analysed
        if (points.size < 2 || distance == null || distance <= 0.0 || working == null) {
            _message.value = "Tap two points and enter the real distance between them."
            return
        }
        val pixels = points[0].distanceTo(points[1])
        if (pixels < 8.0) {
            _message.value = "Those points are too close together to calibrate from."
            return
        }
        val scale = distance / pixels
        _mmPerPx.value = scale
        _busy.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                SiteDetector(siteConfig).detect(SitePhoto(working, scale, photoUri()?.toString()))
            }
            _detection.value = result
            _busy.value = false
            _step.value = Step.REVIEW
            rematch()
        }
    }

    /** Goes on without measuring sizes: holes are still found, no size is claimed. */
    fun skipCalibration() {
        _mmPerPx.value = null
        _step.value = Step.REVIEW
        rematch()
    }

    fun setStrategy(strategy: SiteMatcher.Strategy) {
        _strategy.value = strategy
        rematch()
    }

    private fun rematch() {
        val observations = _detection.value?.observations ?: return
        val tap = _tapOnDrawing.value ?: return
        viewModelScope.launch {
            val sheetEntity = repository.findSheet(sheetId) ?: return@launch
            val drawingScale = Mappers.scaleOf(sheetEntity)
            val all = required.first()
            val inZone = if (drawingScale != null) {
                SiteMatcher.requiredNear(all, tap, _zoneRadiusMm.value, drawingScale)
            } else {
                all
            }
            _match.value = withContext(Dispatchers.Default) {
                SiteMatcher.match(
                    required = inZone,
                    observed = observations,
                    drawingScale = drawingScale,
                    photoMmPerPx = _mmPerPx.value,
                    strategy = _strategy.value,
                    siteConfig = siteConfig,
                )
            }
        }
    }

    /** Writes the verdicts, their reasons and the photograph onto the checklist. */
    fun apply() {
        val result = _match.value ?: return
        _busy.value = true
        viewModelScope.launch {
            val updates = SiteMatcher.toChecklistUpdates(result)
            repository.applySiteFindings(projectId, sheetId, updates)
            _busy.value = false
            _applied.value = true
            _message.value = "Recorded ${updates.size} penetrations: " +
                "${result.matched.size} found, ${result.missing.size} missing."
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
        /** How much of the building around the tap could plausibly be in one photograph. */
        const val DEFAULT_ZONE_MM = 4000.0
        const val SHEET_PREVIEW_PIXELS = 2_000_000
    }
}
