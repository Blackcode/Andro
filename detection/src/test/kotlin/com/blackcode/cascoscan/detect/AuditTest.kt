package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditTest {

    private fun row(id: String, status: AuditStatus = AuditStatus.PENDING, confidence: Double = 0.9) = Penetration(
        id = id,
        pageIndex = 0,
        center = Pt(100.0, 100.0 + id.hashCode().mod(400)),
        box = IBox(90, 90, 110, 110),
        kind = SymbolKind.CIRCLE_CROSSED,
        confidence = confidence,
        status = status,
    )

    @Test
    fun `summary counts progress and findings`() {
        val rows = listOf(
            row("1", AuditStatus.PRESENT),
            row("2", AuditStatus.PRESENT),
            row("3", AuditStatus.MISSING),
            row("4", AuditStatus.WRONG_SIZE),
            row("5", AuditStatus.PENDING),
        )
        val summary = Audit.summarise(rows)
        assertEquals(5, summary.total)
        assertEquals(4, summary.inspected)
        assertEquals(2, summary.findings)
        assertEquals(1, summary.missing)
        assertEquals(0.8, summary.progress)
        assertFalse(summary.isComplete)
    }

    @Test
    fun `an audit is not complete while anything is still awaiting review`() {
        val rows = listOf(row("1", AuditStatus.PRESENT), row("2", AuditStatus.PRESENT, confidence = 0.4))
        val summary = Audit.summarise(rows, acceptThreshold = 0.55)
        assertEquals(2, summary.inspected)
        // The second row was inspected, so it no longer needs review - being inspected *is* the review.
        assertEquals(0, summary.needingReview)
        assertTrue(summary.isComplete)

        val untouched = listOf(row("1", AuditStatus.PRESENT), row("2", AuditStatus.PENDING, confidence = 0.4))
        assertFalse(Audit.summarise(untouched).isComplete)
    }

    @Test
    fun `recording a status promotes a detection so a re-run cannot discard it`() {
        val detected = row("1")
        assertEquals(Origin.DETECTED, detected.origin)
        val inspected = Audit.setStatus(detected, AuditStatus.MISSING, note = "no core drilled")
        assertEquals(AuditStatus.MISSING, inspected.status)
        assertEquals(Origin.DETECTED_CONFIRMED, inspected.origin)
        assertEquals("no core drilled", inspected.note)
        assertFalse(inspected.needsReview(acceptThreshold = 0.99), "a confirmed row is never queued for review")
    }

    @Test
    fun `re-running detection keeps inspected rows and adds genuinely new ones`() {
        val inspected = Audit.setStatus(row("kept"), AuditStatus.PRESENT)
        val staleMachineRow = row("stale").copy(center = Pt(700.0, 700.0))
        val existing = listOf(inspected, staleMachineRow)

        val fresh = listOf(
            // The same penetration the auditor already signed off, re-detected 3 px away.
            row("fresh-dupe").copy(center = Pt(inspected.center.x + 3.0, inspected.center.y)),
            row("fresh-new").copy(center = Pt(1200.0, 400.0)),
        )
        val merged = Audit.mergeRerun(existing, fresh)
        val ids = merged.map { it.id }
        assertTrue("kept" in ids, "inspection work must survive a re-run")
        assertTrue("fresh-new" in ids, "a new detection should be added")
        assertFalse("fresh-dupe" in ids, "a re-detection of a reviewed row must not duplicate it")
        assertFalse("stale" in ids, "an untouched machine row is replaced by the new run")
    }

    @Test
    fun `open items put the most likely findings first`() {
        val rows = listOf(
            row("low", AuditStatus.PENDING, confidence = 0.4),
            row("high", AuditStatus.PENDING, confidence = 0.95),
            row("done", AuditStatus.PRESENT),
        )
        val open = Audit.openItems(rows)
        assertEquals(listOf("high", "low"), open.map { it.id })
    }

    @Test
    fun `findings are ordered with the missing openings first`() {
        val rows = listOf(
            row("a", AuditStatus.NOT_SEALED),
            row("b", AuditStatus.MISSING),
            row("c", AuditStatus.WRONG_SIZE),
            row("d", AuditStatus.PRESENT),
            row("e", AuditStatus.MISSING),
        )
        val findings = Audit.findings(rows)
        assertEquals(listOf("b", "e", "c", "a"), findings.map { it.id })
        assertTrue(findings.none { it.id == "d" }, "a correct penetration is not a finding")
    }

    @Test
    fun `prototype learning distinguishes confirmed shapes from rejected ones`() {
        val library = PrototypeLibrary()
        val sleeve = PrototypeLibrary.Signature(
            kind = SymbolKind.CIRCLE_CROSSED, boxFill = 0.78, radialCv = 0.02, inkRatio = 0.35,
            hatchScore = 0.1, diagonal = 1.0, squareness = 1.0, logSizeMm = kotlin.math.ln(110.0),
        )
        val columnMarker = sleeve.copy(
            kind = SymbolKind.RECT_FILLED, boxFill = 1.0, radialCv = 0.12, inkRatio = 1.0,
            hatchScore = 0.0, logSizeMm = kotlin.math.ln(300.0),
        )
        library.remember(sleeve, wasPenetration = true)
        library.remember(columnMarker, wasPenetration = false)

        val sleeveFeatures = features(boxFill = 0.78, radialCv = 0.02, inkRatio = 0.35, hatch = 0.1, diag = 1.0)
        val columnFeatures = features(boxFill = 1.0, radialCv = 0.12, inkRatio = 1.0, hatch = 0.0, diag = 1.0)

        assertTrue(
            library.score(sleeveFeatures, SymbolKind.CIRCLE_CROSSED, 110.0) > 0.2,
            "a shape matching a confirmed sleeve should score positively",
        )
        assertTrue(
            library.score(columnFeatures, SymbolKind.RECT_FILLED, 300.0) < -0.2,
            "a shape matching a rejected marker should score negatively",
        )
        assertEquals(0.0, PrototypeLibrary().score(sleeveFeatures, SymbolKind.CIRCLE_CROSSED, 110.0))
    }

    private fun features(boxFill: Double, radialCv: Double, inkRatio: Double, hatch: Double, diag: Double) =
        ShapeFeatures(
            box = IBox(0, 0, 19, 19), inkArea = 100, filledArea = 300, holeArea = 200, holeCount = 1,
            outerPerimeter = 60, inkRatio = inkRatio, boxFill = boxFill, circularity = 0.9,
            radialCv = radialCv, symmetryScore = 0.95, diagonalCoverageMin = diag,
            diagonalCoverageMax = diag, axialCoverage = 0.3, hatchScore = hatch,
        )
}
