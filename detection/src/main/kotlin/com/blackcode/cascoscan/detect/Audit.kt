package com.blackcode.cascoscan.detect

/**
 * The audit side of the domain: turning a list of penetrations into the numbers a QC report is
 * judged on, and keeping the state transitions honest.
 */
object Audit {

    data class Summary(
        val total: Int,
        val byStatus: Map<AuditStatus, Int>,
        val needingReview: Int,
        val lowConfidence: Int,
    ) {
        val inspected: Int get() = total - (byStatus[AuditStatus.PENDING] ?: 0)
        val findings: Int get() = byStatus.entries.filter { it.key.isFinding }.sumOf { it.value }
        val missing: Int get() = byStatus[AuditStatus.MISSING] ?: 0

        /** Fraction of the checklist that has been dealt with, 0..1. */
        val progress: Double get() = if (total == 0) 0.0 else inspected.toDouble() / total

        /** An audit is only finished when every row is closed *and* nothing is awaiting review. */
        val isComplete: Boolean get() = total > 0 && inspected == total && needingReview == 0

        fun count(status: AuditStatus) = byStatus[status] ?: 0
    }

    fun summarise(penetrations: List<Penetration>, acceptThreshold: Double = 0.55): Summary {
        val byStatus = penetrations.groupingBy { it.status }.eachCount()
        return Summary(
            total = penetrations.size,
            byStatus = byStatus,
            needingReview = penetrations.count { it.needsReview(acceptThreshold) },
            lowConfidence = penetrations.count { it.origin == Origin.DETECTED && it.confidence < acceptThreshold },
        )
    }

    /**
     * Records an inspection result.
     *
     * Confirming a detected penetration also promotes its origin, which is what stops a re-run of the
     * detector from quietly deleting or re-scoring something a human has already signed off on.
     */
    fun setStatus(penetration: Penetration, status: AuditStatus, note: String? = null): Penetration {
        val promoted = if (penetration.origin == Origin.DETECTED && status.isClosed) {
            Origin.DETECTED_CONFIRMED
        } else {
            penetration.origin
        }
        return penetration.copy(
            status = status,
            origin = promoted,
            note = note ?: penetration.note,
        )
    }

    fun addPhoto(penetration: Penetration, uri: String): Penetration =
        penetration.copy(photoUris = penetration.photoUris + uri)

    /**
     * Merges a fresh detection run into an existing checklist without losing inspection work.
     *
     * Re-running detection is normal - the auditor corrects the scale, excludes the title block, or
     * confirms a few symbols to teach the prototype library, and wants the rest of the sheet redone.
     * Anything a human has touched wins; genuinely new detections are appended.
     */
    fun mergeRerun(
        existing: List<Penetration>,
        fresh: List<Penetration>,
        matchTolerancePx: Double = 20.0,
    ): List<Penetration> {
        // Reviewed rows are kept verbatim; untouched machine rows are replaced wholesale by the new run.
        val human = existing.filter { it.origin != Origin.DETECTED }
        val index = SpatialIndex(human.map { it.center }, matchTolerancePx)
        val added = fresh.filter { candidate ->
            // A fresh detection at the same spot as a reviewed row is the same penetration: drop it.
            index.nearest(candidate.center, matchTolerancePx) == null
        }
        return human + added
    }

    /** Rows an auditor still has to deal with, ordered so the worst comes first. */
    fun openItems(penetrations: List<Penetration>, acceptThreshold: Double = 0.55): List<Penetration> =
        penetrations
            .filter { it.status == AuditStatus.PENDING || it.needsReview(acceptThreshold) }
            .sortedWith(compareBy({ it.pageIndex }, { -it.confidence }))

    /** Findings, ordered for the report: missing first, then wrong, then the rest. */
    fun findings(penetrations: List<Penetration>): List<Penetration> {
        val rank = mapOf(
            AuditStatus.MISSING to 0,
            AuditStatus.WRONG_SIZE to 1,
            AuditStatus.WRONG_POSITION to 2,
            AuditStatus.OBSTRUCTED to 3,
            AuditStatus.NOT_SEALED to 4,
        )
        return penetrations
            .filter { it.status.isFinding }
            .sortedWith(compareBy({ rank[it.status] ?: 9 }, { it.pageIndex }, { it.id }))
    }
}
