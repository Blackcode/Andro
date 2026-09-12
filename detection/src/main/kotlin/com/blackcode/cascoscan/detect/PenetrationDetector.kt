package com.blackcode.cascoscan.detect

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One page handed to the detector. */
data class PageInput(
    val pageIndex: Int,
    val image: GrayImage,
    /** Null is allowed: without a scale, size evidence is skipped instead of guessed. */
    val scale: DrawingScale? = null,
    val text: List<TextBox> = emptyList(),
    /** Human-readable sheet name, carried into the report. */
    val sheetName: String? = null,
)

/** Everything the detector learned about a page, including why it decided what it did. */
data class PageResult(
    val pageIndex: Int,
    val penetrations: List<Penetration>,
    val diagnostics: Diagnostics,
) {
    val accepted: List<Penetration> get() = penetrations.filter { !it.needsReview(diagnostics.acceptThreshold) }
    val needingReview: List<Penetration> get() = penetrations.filter { it.needsReview(diagnostics.acceptThreshold) }

    data class Diagnostics(
        val binarizeMethod: String,
        val inkFraction: Double,
        val componentCount: Int,
        val structureCount: Int,
        val candidateCount: Int,
        val droppedBySize: Int,
        val droppedByConfidence: Int,
        val labelsFound: Int,
        val labelsMatched: Int,
        val scaleUsed: DrawingScale?,
        val scaleEstimate: ScaleEstimator.Result?,
        val acceptThreshold: Double,
        val elapsedMs: Long,
    ) {
        /** A page with no scale and no labels is a page whose results need human eyes on them. */
        val isLowInformation: Boolean get() = scaleUsed == null && labelsMatched == 0
    }
}

/**
 * Finds the penetrations on one rendered drawing page.
 *
 * The pipeline, and why it is in this order:
 *  1. **binarize** - ink/paper, adaptively, so scans work as well as CAD exports;
 *  2. **structural lines** - long axis-aligned runs, kept aside as the wall/grid layer;
 *  3. **label components** - free-standing blobs are candidates directly;
 *  4. **mine the oversized ones** - a symbol drawn across a wall is part of the wall's blob, so the
 *     long runs are stripped inside it and its enclosed voids are taken as openings;
 *  5. **classify** - what each blob looks like, with no assumption about meaning;
 *  6. **group** - fold interior marks into their symbol so one sleeve is one finding;
 *  7. **contextualise** - repetition, structural proximity, excluded regions, and which blobs
 *     are really characters ([TextRuns]);
 *  8. **read the annotations** - attach "Ø110"/"sparing" to the symbol it belongs to, and if no
 *     scale was given, recover it from those labels;
 *  9. **score** - weighted, explainable evidence -> confidence;
 * 10. **number** - reading order, so the checklist matches how someone walks the building.
 *
 * Nothing here is allowed to silently discard a plausible candidate: anything above
 * [DetectionConfig.reviewThreshold] survives and is flagged for review instead.
 */
class PenetrationDetector(private val config: DetectionConfig = DetectionConfig()) {

    fun detect(page: PageInput, prototypes: PrototypeLibrary? = null): PageResult {
        val started = System.currentTimeMillis()
        val image = page.image
        val w = image.width
        val h = image.height

        val binarized = Binarize.auto(image)
        val mask = binarized.mask
        val structureMask = StructuralLines.lineMask(mask, config.wallRunPx)

        val maxSymbolPx = max(16, (config.maxBlobFractionOfPage * min(w, h)).roundToInt())
        val minArea = max(4, config.minBlobPx)
        val minVoidPx = page.scale
            ?.let { (config.softMinMm * 0.6 / it.mmPerPx).roundToInt() }
            ?.coerceIn(4, maxSymbolPx / 2)
            ?: max(6, config.minBlobPx)

        val components = ConnectedComponents.label(mask, minArea = minArea)
        var structureCount = 0

        // --- Steps 3 and 4: gather blobs worth classifying. ---
        val raw = ArrayList<Pair<Component, CandidateSource>>()
        for (c in components) {
            if (c.box.longSide <= maxSymbolPx) {
                raw += c to CandidateSource.FREE_STANDING
            } else {
                structureCount++
                raw += StructureAnalyzer.analyse(
                    structure = c,
                    pageWidth = w,
                    minRun = config.wallRunPx,
                    minArea = minArea,
                    maxSymbolPx = maxSymbolPx,
                    minVoidPx = minVoidPx,
                    bridgeRadius = config.bridgeRadius,
                ).map { it.component to it.source }
            }
        }

        // --- Step 5: classify. ---
        var droppedBySize = 0
        val candidates = ArrayList<Candidate>(raw.size)
        for ((component, source) in raw) {
            val box = component.box
            // A stroke two pixels wide is a line fragment, never an opening.
            if (box.shortSide <= 2 || box.longSide < config.minBlobPx) continue
            val features = ComponentFeatures.extract(component, config.sealRadius)
            val classification = ShapeClassifier.classify(features, config)
            val candidate = Candidate(
                features = features,
                kind = classification.kind,
                shapeConfidence = classification.confidence,
                source = source,
            )
            // Hard size gate: outside the absolute band it cannot be a penetration whatever it looks like.
            val scale = page.scale
            if (scale != null && !config.sizeIsPossible(candidate.sizePx * scale.mmPerPx)) {
                droppedBySize++
                continue
            }
            candidates += candidate
        }

        // --- Steps 6 and 7. ---
        var working = Grouping.merge(candidates)
        working = Grouping.repetitionCounts(working)
        working = working.map { c ->
            val distance = StructuralLines.distanceToMask(structureMask, c.box, config.wallProximityPx)
            c.copy(
                nearWall = distance <= config.wallProximityPx,
                wallDistancePx = distance,
                inExcludedRegion = config.excludedRegions.any { it.contains(c.center) },
            )
        }

        // Annotation: a reported text box is definitive, a row of aligned look-alikes is inferred.
        val textRun = TextRuns.detect(working.map { it.box })
        working = working.mapIndexed { i, c ->
            c.copy(
                inTextBox = page.text.any { it.box.contains(c.center) },
                inTextRun = textRun[i],
            )
        }

        // --- Step 8: annotations, and the scale they can reveal. ---
        val labels = LabelParser.parseAll(page.text)
        val matches = LabelMatcher.match(working, labels, config)
        working = LabelMatcher.apply(working, matches)

        var scale = page.scale
        var scaleEstimate: ScaleEstimator.Result? = null
        if (scale == null) {
            scaleEstimate = estimateScale(working)
            // Only trust an estimate that several symbols agree on.
            if (scaleEstimate != null && scaleEstimate.spread <= 0.15) scale = scaleEstimate.scale
        }
        if (scale != null) {
            val before = working.size
            working = working.filter { config.sizeIsPossible(it.sizePx * scale.mmPerPx) }
            droppedBySize += before - working.size
        }

        // --- Step 9: score, then keep anything that is at least worth a look. ---
        val scored = working.map { candidate ->
            candidate to Scoring.score(candidate, scale, config, prototypes)
        }
        val surviving = scored.filter { (_, e) -> e.confidence >= config.reviewThreshold }
        val droppedByConfidence = scored.size - surviving.size

        // --- Step 10: number in reading order. ---
        val ordered = surviving.sortedWith(
            compareBy(
                { readingBand(it.first.center.y, h) },
                { it.first.center.x },
            ),
        )
        val penetrations = ordered.mapIndexed { index, (candidate, evidence) ->
            Penetration(
                id = "P${page.pageIndex + 1}.${index + 1}",
                pageIndex = page.pageIndex,
                center = candidate.center,
                box = candidate.box,
                kind = candidate.kind,
                confidence = evidence.confidence,
                evidence = evidence,
                sizeMm = scale?.let {
                    SizeMm(candidate.box.width * it.mmPerPx, candidate.box.height * it.mmPerPx)
                },
                label = candidate.label,
                origin = Origin.DETECTED,
                status = AuditStatus.PENDING,
            )
        }

        return PageResult(
            pageIndex = page.pageIndex,
            penetrations = penetrations,
            diagnostics = PageResult.Diagnostics(
                binarizeMethod = binarized.method,
                inkFraction = mask.inkFraction(),
                componentCount = components.size,
                structureCount = structureCount,
                candidateCount = candidates.size,
                droppedBySize = droppedBySize,
                droppedByConfidence = droppedByConfidence,
                labelsFound = labels.size,
                labelsMatched = matches.size,
                scaleUsed = scale,
                scaleEstimate = scaleEstimate,
                acceptThreshold = config.acceptThreshold,
                elapsedMs = System.currentTimeMillis() - started,
            ),
        )
    }

    /**
     * Recovers mm-per-pixel from symbols whose label declares a diameter. Restricted to round
     * symbols, whose drawn extent really is the nominal diameter; a rectangle's label may quote a
     * clear opening rather than the drawn outline.
     */
    private fun estimateScale(candidates: List<Candidate>): ScaleEstimator.Result? {
        val observations = candidates.mapNotNull { c ->
            val declared = c.label?.diameterMm ?: return@mapNotNull null
            if (!c.kind.isRound) return@mapNotNull null
            c.sizePx to declared
        }
        return ScaleEstimator.estimate(observations)
    }

    /**
     * Groups y coordinates into horizontal bands so numbering runs left-to-right across a row of
     * symbols instead of zig-zagging on one-pixel differences.
     */
    private fun readingBand(y: Double, pageHeight: Int): Int {
        val band = max(1.0, pageHeight * 0.02)
        return (y / band).toInt()
    }
}
