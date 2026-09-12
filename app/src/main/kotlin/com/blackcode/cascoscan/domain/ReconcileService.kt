package com.blackcode.cascoscan.domain

import com.blackcode.cascoscan.data.AlignmentEntity
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.Mappers
import com.blackcode.cascoscan.detect.Penetration
import com.blackcode.cascoscan.detect.PointSetAligner
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.Reconciler
import com.blackcode.cascoscan.detect.Similarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Compares two sheets and works out which required penetrations are absent.
 *
 * Alignment is offered two ways because the automatic one cannot always work. RANSAC over the
 * penetration positions needs the two sheets to share enough penetrations to lock onto; when a sheet
 * is mostly *missing* its openings - exactly the case that matters most - there is nothing to lock
 * onto and the user has to tap two or three common points instead. The app therefore tries the
 * automatic path, reports how well it fitted, and lets the user override it.
 */
class ReconcileService(private val repository: AuditRepository) {

    enum class AlignmentSource { AUTO, CONTROL_POINTS, SAVED }

    data class Outcome(
        val result: Reconciler.Result,
        val alignmentSource: AlignmentSource,
        val inliers: Int,
        val total: Int,
        /** Set when the fit is too weak to trust; the UI must ask for control points. */
        val warning: String?,
    ) {
        val findings: List<Penetration> get() = Reconciler.toChecklist(result)
    }

    /**
     * @param controlPoints matched taps, reference point to target point. Two pairs are enough for a
     *   similarity transform; a third only improves it.
     */
    suspend fun reconcile(
        projectId: String,
        referenceSheetId: String,
        targetSheetId: String,
        controlPoints: List<Pair<Pt, Pt>> = emptyList(),
        params: Reconciler.Params = Reconciler.Params(),
    ): Result<Outcome> = withContext(Dispatchers.Default) {
        val referenceSheet = repository.findSheet(referenceSheetId)
            ?: return@withContext Result.failure(IllegalStateException("reference sheet is gone"))
        val targetSheet = repository.findSheet(targetSheetId)
            ?: return@withContext Result.failure(IllegalStateException("target sheet is gone"))
        if (referenceSheetId == targetSheetId) {
            return@withContext Result.failure(IllegalArgumentException("a sheet cannot be compared with itself"))
        }

        val reference = repository.penetrations(referenceSheetId).first()
        val target = repository.penetrations(targetSheetId).first()
        if (reference.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("run detection on the reference sheet first"))
        }

        val targetScale = Mappers.scaleOf(targetSheet)

        // Explicit control points win: the user has told us the answer.
        if (controlPoints.size >= 2) {
            val transform = Similarity.fit(controlPoints.map { it.first }, controlPoints.map { it.second })
                ?: return@withContext Result.failure(
                    IllegalArgumentException("those control points are too close together to define an overlay"),
                )
            val result = Reconciler.reconcile(
                reference = reference,
                target = target,
                transform = transform,
                targetScale = targetScale,
                params = params,
                referencePageIndex = referenceSheet.pageIndex,
                targetPageIndex = targetSheet.pageIndex,
            )
            save(projectId, referenceSheetId, targetSheetId, transform, AlignmentSource.CONTROL_POINTS, result)
            return@withContext Result.success(
                Outcome(result, AlignmentSource.CONTROL_POINTS, result.matched.size, result.requiredCount, null),
            )
        }

        val alignment = PointSetAligner.align(
            from = reference.map { it.center },
            to = target.map { it.center },
            fromSizes = reference.map { it.box.longSide.toDouble() },
            toSizes = target.map { it.box.longSide.toDouble() },
        )
        val transform = alignment?.transform ?: Similarity.IDENTITY
        val result = Reconciler.reconcile(
            reference = reference,
            target = target,
            transform = transform,
            targetScale = targetScale,
            params = params,
            referencePageIndex = referenceSheet.pageIndex,
            targetPageIndex = targetSheet.pageIndex,
        )
        val warning = when {
            alignment == null ->
                "Could not line the two sheets up automatically. Tap two points that appear on both."
            !alignment.isTrustworthy() ->
                "Only ${alignment.inliers} of ${alignment.total} penetrations lined up, so the overlay may be " +
                    "wrong. Tap two common points to fix it before trusting these findings."
            else -> null
        }
        save(projectId, referenceSheetId, targetSheetId, transform, AlignmentSource.AUTO, result)
        Result.success(
            Outcome(
                result = result,
                alignmentSource = AlignmentSource.AUTO,
                inliers = alignment?.inliers ?: 0,
                total = alignment?.total ?: reference.size,
                warning = warning,
            ),
        )
    }

    /** Writes the findings onto the target sheet's checklist. */
    suspend fun apply(projectId: String, targetSheetId: String, outcome: Outcome) {
        repository.applyReconciliation(projectId, targetSheetId, outcome.findings)
    }

    private suspend fun save(
        projectId: String,
        referenceSheetId: String,
        targetSheetId: String,
        transform: Similarity,
        source: AlignmentSource,
        result: Reconciler.Result,
    ) {
        repository.saveAlignment(
            AlignmentEntity(
                projectId = projectId,
                referenceSheetId = referenceSheetId,
                targetSheetId = targetSheetId,
                a = transform.a,
                b = transform.b,
                tx = transform.tx,
                ty = transform.ty,
                source = source.name,
                inliers = result.matched.size,
                total = result.requiredCount,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}
