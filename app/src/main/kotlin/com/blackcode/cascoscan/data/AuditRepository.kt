package com.blackcode.cascoscan.data

import android.content.Context
import android.net.Uri
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.PageResult
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.PrototypeLibrary
import com.blackcode.cascoscan.detect.RenderPlan
import com.blackcode.cascoscan.detect.SymbolKind
import com.blackcode.cascoscan.pdf.PdfPageSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Everything the UI is allowed to do to the stored audit.
 *
 * Keeping the rules here rather than in the view models is what makes two of them safe: a detection
 * re-run must never delete a row a human has touched, and confirming or rejecting a symbol must feed
 * the project's prototype library. Both are easy to forget at a call site and impossible to forget
 * here.
 */
class AuditRepository(
    private val context: Context,
    private val database: CascoDatabase,
) {

    val projects: Flow<List<ProjectEntity>> get() = database.projects().observeAll()

    fun project(projectId: String): Flow<ProjectEntity?> = database.projects().observe(projectId)

    fun sheets(projectId: String): Flow<List<SheetEntity>> = database.sheets().observeForProject(projectId)

    fun sheet(sheetId: String): Flow<SheetEntity?> = database.sheets().observe(sheetId)

    fun penetrations(sheetId: String): Flow<List<Penetration>> =
        database.penetrations().observeForSheet(sheetId).map { rows -> rows.map(Mappers::toModel) }

    fun projectPenetrations(projectId: String): Flow<List<Penetration>> =
        database.penetrations().observeForProject(projectId).map { rows -> rows.map(Mappers::toModel) }

    fun prototypeCount(projectId: String): Flow<Int> = database.prototypes().observeCount(projectId)

    suspend fun findSheet(sheetId: String): SheetEntity? = database.sheets().find(sheetId)

    suspend fun findProject(projectId: String): ProjectEntity? = database.projects().find(projectId)

    suspend fun sheetsOnce(projectId: String): List<SheetEntity> = withContext(Dispatchers.IO) {
        database.sheets().forProject(projectId)
    }

    /**
     * Registers a drawing set: reads its page geometry once and creates a sheet per page.
     *
     * Re-importing the same document returns the existing project rather than a second copy, because
     * on site the usual way to reopen a job is to tap the same attachment again.
     */
    suspend fun importDocument(
        uri: Uri,
        name: String,
        ratioDenominator: Double?,
    ): String = withContext(Dispatchers.IO) {
        database.projects().findByDocument(uri.toString())?.let { return@withContext it.id }

        // Survive a reboot: without this the uri is only readable for the life of the activity.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val plan = ratioDenominator?.let { RenderPlan.forRatio(it) }
        val dpi = plan?.dpi ?: DEFAULT_DPI
        val projectId = UUID.randomUUID().toString()

        val sheets = PdfPageSource.open(context, uri).use { source ->
            source.pageSizes().mapIndexed { index, size ->
                SheetEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    pageIndex = index,
                    name = "Sheet ${index + 1}",
                    sheetFormat = size.sheetFormat(),
                    widthPx = (size.widthPt * dpi / 72.0).toInt().coerceAtLeast(1),
                    heightPx = (size.heightPt * dpi / 72.0).toInt().coerceAtLeast(1),
                    dpi = dpi,
                    mmPerPx = plan?.scale?.mmPerPx,
                    scaleSource = plan?.scale?.source?.name,
                    detectionState = SheetEntity.State.NOT_RUN.name,
                    detectedAt = null,
                    diagnostics = null,
                    excludedLeft = null,
                    excludedTop = null,
                    excludedRight = null,
                    excludedBottom = null,
                )
            }
        }

        database.projects().upsert(
            ProjectEntity(
                id = projectId,
                name = name,
                documentUri = uri.toString(),
                pageCount = sheets.size,
                createdAt = System.currentTimeMillis(),
                ratioDenominator = ratioDenominator,
                renderDpi = dpi,
                referenceSheetId = null,
                notes = null,
            ),
        )
        database.sheets().upsertAll(sheets)
        projectId
    }

    /**
     * Sets the plot scale for a whole project and re-plans the render resolution.
     *
     * Changing the scale invalidates every size judgement the detector made, so sheets already
     * processed are marked as needing another run rather than left showing stale confidences.
     */
    suspend fun setProjectScale(projectId: String, ratioDenominator: Double) = withContext(Dispatchers.IO) {
        val project = database.projects().find(projectId) ?: return@withContext
        val plan = RenderPlan.forRatio(ratioDenominator)
        database.projects().upsert(project.copy(ratioDenominator = ratioDenominator, renderDpi = plan.dpi))

        // Read the sheets before writing any of them: each sheet's new raster size is derived from the
        // dpi it currently has, so overwriting that first would make every factor 1.
        for (sheet in database.sheets().forProject(projectId)) {
            val factor = if (sheet.dpi > 0.0) plan.dpi / sheet.dpi else 1.0
            database.sheets().update(
                sheet.copy(
                    widthPx = (sheet.widthPx * factor).toInt().coerceAtLeast(1),
                    heightPx = (sheet.heightPx * factor).toInt().coerceAtLeast(1),
                    dpi = plan.dpi,
                    mmPerPx = plan.scale.mmPerPx,
                    scaleSource = plan.scale.source.name,
                    // Every size judgement the detector made is now stale, so say so rather than
                    // leaving confidences on screen that were computed against the old scale.
                    detectionState = if (sheet.detectionState == SheetEntity.State.DONE.name) {
                        SheetEntity.State.NOT_RUN.name
                    } else {
                        sheet.detectionState
                    },
                ),
            )
        }
    }

    suspend fun setExcludedRegion(sheetId: String, region: com.blackcode.cascoscan.detect.IBox?) =
        withContext(Dispatchers.IO) {
            val sheet = database.sheets().find(sheetId) ?: return@withContext
            database.sheets().update(
                sheet.copy(
                    excludedLeft = region?.left,
                    excludedTop = region?.top,
                    excludedRight = region?.right,
                    excludedBottom = region?.bottom,
                ),
            )
        }

    suspend fun setSheetState(sheetId: String, state: SheetEntity.State, diagnostics: String? = null) =
        withContext(Dispatchers.IO) {
            database.sheets().setDetectionState(
                id = sheetId,
                state = state.name,
                at = if (state == SheetEntity.State.DONE) System.currentTimeMillis() else null,
                diagnostics = diagnostics,
            )
        }

    /**
     * Stores a detection run, preserving inspection work.
     *
     * Rows whose origin is still DETECTED are the previous run's guesses and are replaced. Anything
     * else - confirmed, hand-added, or raised by a reconciliation - is kept, and a fresh detection
     * landing on top of one is dropped so the checklist does not grow a duplicate.
     */
    suspend fun saveDetection(
        projectId: String,
        sheetId: String,
        result: PageResult,
    ) = withContext(Dispatchers.IO) {
        val existing = database.penetrations().forSheet(sheetId).map(Mappers::toModel)
        val kept = existing.filter { it.origin != com.blackcode.cascoscan.detect.Origin.DETECTED }
        val fresh = result.penetrations.filter { candidate ->
            kept.none { it.center.distanceTo(candidate.center) <= DUPLICATE_TOLERANCE_PX }
        }
        database.penetrations().replaceDetected(
            sheetId = sheetId,
            machineOrigin = com.blackcode.cascoscan.detect.Origin.DETECTED.name,
            fresh = fresh.map { Mappers.toEntity(it, projectId, sheetId) },
        )
        database.sheets().setDetectionState(
            id = sheetId,
            state = SheetEntity.State.DONE.name,
            at = System.currentTimeMillis(),
            diagnostics = describe(result),
        )
    }

    /** Records an inspection result. */
    suspend fun setStatus(
        projectId: String,
        sheetId: String,
        penetration: Penetration,
        status: AuditStatus,
        note: String? = null,
    ) = withContext(Dispatchers.IO) {
        val updated = com.blackcode.cascoscan.detect.Audit.setStatus(penetration, status, note)
        database.penetrations().upsert(Mappers.toEntity(updated, projectId, sheetId))
    }

    suspend fun addPhoto(projectId: String, sheetId: String, penetration: Penetration, uri: String) =
        withContext(Dispatchers.IO) {
            val updated = com.blackcode.cascoscan.detect.Audit.addPhoto(penetration, uri)
            database.penetrations().upsert(Mappers.toEntity(updated, projectId, sheetId))
        }

    /** Adds a penetration the detector missed, which is how a false negative gets into the report. */
    suspend fun addManual(
        projectId: String,
        sheetId: String,
        pageIndex: Int,
        center: com.blackcode.cascoscan.detect.Pt,
        sizeMm: com.blackcode.cascoscan.detect.SizeMm?,
        kind: SymbolKind,
        note: String?,
        mmPerPx: Double?,
    ) = withContext(Dispatchers.IO) {
        val halfWidth = sizeMm?.width?.let { w -> mmPerPx?.let { w / it / 2.0 } } ?: 12.0
        val halfHeight = sizeMm?.height?.let { h -> mmPerPx?.let { h / it / 2.0 } } ?: 12.0
        val existing = database.penetrations().forSheet(sheetId)
        val manualCount = existing.count { it.origin == com.blackcode.cascoscan.detect.Origin.MANUAL.name }
        val penetration = Penetration(
            id = "M${pageIndex + 1}.${manualCount + 1}",
            pageIndex = pageIndex,
            center = center,
            box = com.blackcode.cascoscan.detect.IBox.around(center, halfWidth, halfHeight),
            kind = kind,
            confidence = 1.0,
            sizeMm = sizeMm,
            origin = com.blackcode.cascoscan.detect.Origin.MANUAL,
            status = AuditStatus.PENDING,
            note = note,
        )
        database.penetrations().upsert(Mappers.toEntity(penetration, projectId, sheetId))
    }

    suspend fun deletePenetration(sheetId: String, displayId: String) = withContext(Dispatchers.IO) {
        database.penetrations().deleteById(Mappers.rowId(sheetId, displayId))
    }

    /**
     * Teaches the project from a correction.
     *
     * Called when the auditor confirms a detection or rejects it. Requires the shape's measurements,
     * which only the detection run has - so the caller passes them through from the review screen.
     */
    suspend fun remember(projectId: String, penetration: Penetration, wasPenetration: Boolean) =
        withContext(Dispatchers.IO) {
            val signature = penetration.signature ?: return@withContext
            database.prototypes().insert(Mappers.toEntity(signature, projectId, wasPenetration))
        }

    suspend fun prototypeLibrary(projectId: String): PrototypeLibrary = withContext(Dispatchers.IO) {
        Mappers.toLibrary(database.prototypes().forProject(projectId))
    }

    suspend fun clearPrototypes(projectId: String) = withContext(Dispatchers.IO) {
        database.prototypes().clear(projectId)
    }

    suspend fun saveAlignment(alignment: AlignmentEntity) = withContext(Dispatchers.IO) {
        database.alignments().upsert(alignment)
    }

    suspend fun findAlignment(referenceSheetId: String, targetSheetId: String): AlignmentEntity? =
        withContext(Dispatchers.IO) { database.alignments().find(referenceSheetId, targetSheetId) }

    /** Adds the rows a reconciliation produced, replacing any earlier reconciliation of the same pair. */
    suspend fun applyReconciliation(
        projectId: String,
        targetSheetId: String,
        rows: List<Penetration>,
    ) = withContext(Dispatchers.IO) {
        database.penetrations().deleteByOrigin(
            targetSheetId,
            com.blackcode.cascoscan.detect.Origin.RECONCILED.name,
        )
        database.penetrations().insertAll(rows.map { Mappers.toEntity(it, projectId, targetSheetId) })
    }

    suspend fun setReferenceSheet(projectId: String, sheetId: String?) = withContext(Dispatchers.IO) {
        val project = database.projects().find(projectId) ?: return@withContext
        database.projects().upsert(project.copy(referenceSheetId = sheetId))
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        database.projects().find(projectId)?.let { database.projects().delete(it) }
    }

    private fun describe(result: PageResult): String {
        val d = result.diagnostics
        return buildString {
            append("${result.penetrations.size} found, ${result.accepted.size} accepted")
            append(", ${result.needingReview.size} to review")
            append(" | ${d.binarizeMethod}")
            append(", ${d.componentCount} blobs")
            append(", ${d.labelsMatched}/${d.labelsFound} labels matched")
            d.scaleUsed?.let { append(", %.2f mm/px".format(it.mmPerPx)) }
            append(", ${d.elapsedMs} ms")
        }
    }

    companion object {
        /** Used until the user declares a plot scale; enough for a 1:50 sheet. */
        const val DEFAULT_DPI = 200.0

        /** A fresh detection this close to a reviewed row is the same penetration. */
        const val DUPLICATE_TOLERANCE_PX = 20.0
    }
}
