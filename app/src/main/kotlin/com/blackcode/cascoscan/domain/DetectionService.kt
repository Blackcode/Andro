package com.blackcode.cascoscan.domain

import android.content.Context
import android.net.Uri
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.Mappers
import com.blackcode.cascoscan.data.SheetEntity
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.PageResult
import com.blackcode.cascoscan.detect.PenetrationDetector
import com.blackcode.cascoscan.detect.TextBox
import com.blackcode.cascoscan.detect.TiledPenetrationDetector
import com.blackcode.cascoscan.pdf.PdfPageSource
import com.blackcode.cascoscan.pdf.TextSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs detection over a sheet, or over a whole drawing set, and stores the outcome.
 *
 * The work is deliberately sequential, one tile at a time: the bottleneck is the raster, not the CPU,
 * and running tiles in parallel would multiply the peak allocation by the number of threads, which is
 * the one thing that gets the process killed on a phone.
 */
class DetectionService(
    private val context: Context,
    private val repository: AuditRepository,
) {

    /** Where a run has got to, for the progress indicator. */
    data class Progress(
        val sheetIndex: Int,
        val sheetCount: Int,
        val tilesDone: Int,
        val tileCount: Int,
        val stage: Stage,
    ) {
        enum class Stage { READING_TEXT, DETECTING, SAVING }

        val fraction: Float
            get() {
                val perSheet = 1f / sheetCount.coerceAtLeast(1)
                val within = if (tileCount <= 0) 0f else tilesDone.toFloat() / tileCount
                return (sheetIndex * perSheet + within * perSheet).coerceIn(0f, 1f)
            }
    }

    /**
     * Detects on one sheet.
     *
     * @param useOcr fall back to on-device OCR when the PDF carries no text layer. Slower, and only
     *   worth it for scanned drawings - a CAD export always has real text.
     */
    suspend fun detectSheet(
        projectId: String,
        sheetId: String,
        useOcr: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): Result<PageResult> = withContext(Dispatchers.Default) {
        val project = repository.findProject(projectId)
            ?: return@withContext Result.failure(IllegalStateException("project $projectId is gone"))
        val sheet = repository.findSheet(sheetId)
            ?: return@withContext Result.failure(IllegalStateException("sheet $sheetId is gone"))

        repository.setSheetState(sheetId, SheetEntity.State.RUNNING)
        try {
            val uri = Uri.parse(project.documentUri)
            val result = PdfPageSource.open(context, uri).use { source ->
                onProgress(Progress(0, 1, 0, 1, Progress.Stage.READING_TEXT))
                val text = readText(uri, source, sheet, useOcr)

                val detector = PenetrationDetector(Mappers.configFor(sheet))
                val tiler = TiledPenetrationDetector(detector)
                val tiles = tiler.tilesFor(sheet.widthPx, sheet.heightPx)
                var done = 0
                onProgress(Progress(0, 1, 0, tiles.size, Progress.Stage.DETECTING))

                tiler.detect(
                    pageIndex = sheet.pageIndex,
                    pageWidth = sheet.widthPx,
                    pageHeight = sheet.heightPx,
                    scale = Mappers.scaleOf(sheet),
                    text = text,
                    sheetName = sheet.name,
                    prototypes = repository.prototypeLibrary(projectId),
                ) { tile ->
                    val image = source.renderTile(sheet.pageIndex, sheet.dpi, tile)
                    done++
                    onProgress(Progress(0, 1, done, tiles.size, Progress.Stage.DETECTING))
                    image
                }
            }
            onProgress(Progress(0, 1, 1, 1, Progress.Stage.SAVING))
            repository.saveDetection(projectId, sheetId, result)
            Result.success(result)
        } catch (cancellation: CancellationException) {
            repository.setSheetState(sheetId, SheetEntity.State.NOT_RUN)
            throw cancellation
        } catch (t: Throwable) {
            repository.setSheetState(sheetId, SheetEntity.State.FAILED, t.message ?: t::class.java.simpleName)
            Result.failure(t)
        }
    }

    /** Detects on every sheet that has not been done yet, or on all of them when [force] is set. */
    suspend fun detectProject(
        projectId: String,
        force: Boolean = false,
        useOcr: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): Result<Int> = withContext(Dispatchers.Default) {
        repository.findProject(projectId)
            ?: return@withContext Result.failure(IllegalStateException("project $projectId is gone"))
        val sheets = repository.sheetsOnce(projectId)
        val todo = if (force) sheets else sheets.filter { it.detectionState != SheetEntity.State.DONE.name }
        var completed = 0
        for ((index, sheet) in todo.withIndex()) {
            val outcome = detectSheet(projectId, sheet.id, useOcr) { progress ->
                onProgress(progress.copy(sheetIndex = index, sheetCount = todo.size))
            }
            if (outcome.isSuccess) completed++
        }
        Result.success(completed)
    }

    /**
     * Page text, preferring the PDF's own layer.
     *
     * OCR runs against a downscaled preview - ML Kit will not accept a 20 000 pixel image anyway - and
     * the boxes are scaled up into detection space afterwards. That loses a little precision on the
     * label positions, which costs nothing: labels are matched by proximity, not by exact overlap.
     */
    private suspend fun readText(
        uri: Uri,
        source: PdfPageSource,
        sheet: SheetEntity,
        useOcr: Boolean,
    ): List<TextBox> {
        TextSources.fromPdf(context, uri, sheet.pageIndex, sheet.dpi)?.let { return it }
        if (!useOcr) return emptyList()

        val preview = source.renderPreview(sheet.pageIndex, maxPixels = OCR_PIXELS)
        return try {
            val boxes = TextSources.fromOcr(preview)
            val factor = sheet.widthPx.toDouble() / preview.width
            boxes.map { box ->
                TextBox(
                    box.text,
                    IBox(
                        (box.box.left * factor).toInt(),
                        (box.box.top * factor).toInt(),
                        (box.box.right * factor).toInt(),
                        (box.box.bottom * factor).toInt(),
                    ),
                )
            }
        } finally {
            preview.recycle()
        }
    }

    companion object {
        /** Enough resolution for OCR to read drawing annotation, small enough for ML Kit to accept. */
        const val OCR_PIXELS = 4_000_000
    }
}
