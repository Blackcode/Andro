package com.blackcode.cascoscan.detect

import kotlin.math.min

/**
 * Weights of the evidence model, in logit units: each term shifts the log-odds that a candidate is a
 * real penetration, and the sum goes through a logistic to become a confidence.
 *
 * A linear model is a deliberate choice over something learned. There is no labelled corpus of casco
 * drawings to train on, the terms have to stay individually explainable for an audit trail, and a
 * site engineer must be able to move one number when their office's symbols differ. Everything here
 * is a projectwide setting in the app, not a constant.
 */
data class ScoringWeights(
    /** Prior against any given blob being a penetration; most ink on a drawing is not one. */
    val bias: Double = -1.70,

    /** Per-form prior, scaled by how well the blob actually fits that form. */
    val shapePrior: Map<SymbolKind, Double> = mapOf(
        SymbolKind.CIRCLE_CROSSED to 2.00,
        SymbolKind.RECT_CROSSED to 1.90,
        SymbolKind.CIRCLE_HATCHED to 1.30,
        SymbolKind.RECT_HATCHED to 1.30,
        SymbolKind.CIRCLE_OUTLINE to 0.95,
        SymbolKind.CIRCLE_FILLED to 0.35,
        SymbolKind.RECT_OUTLINE to 0.20,
        SymbolKind.RECT_FILLED to 0.05,
        SymbolKind.UNKNOWN to -0.80,
    ),

    /**
     * Real-world size inside the plausible band; implausible-but-not-impossible sizes go negative.
     *
     * Kept modest on purpose: a plausible size is a *necessary* condition for a penetration, not
     * evidence of one. Most ink on a drawing happens to be penetration-sized.
     */
    val sizePlausibility: Double = 1.20,

    /** A nearby "Ø110" or "sparing" is the single strongest cue the drawing offers. */
    val labelSupport: Double = 2.40,

    /** Measured size versus the size the label declares. Signed: disagreement counts against. */
    val sizeAgreement: Double = 1.40,

    /** Where the candidate sits in the page structure - see [CandidateSource]. */
    val structureContext: Map<CandidateSource, Double> = mapOf(
        CandidateSource.FREE_STANDING to 0.00,
        CandidateSource.IN_STRUCTURE to 0.85,
        CandidateSource.VOID_IN_STRUCTURE to 1.10,
    ),

    /** Sitting on a structural line at all, for candidates that were never inside one. */
    val wallProximity: Double = 0.45,

    /** Symbols repeat across a drawing; one-of-a-kind shapes are more often something else. */
    val repetition: Double = 0.70,

    /** Similarity to what the auditor has already confirmed or rejected on this project. */
    val prototypeSimilarity: Double = 1.60,

    /** Title block, legend, revision table: whatever is in there is not a penetration. */
    val excludedRegion: Double = -3.50,

    /**
     * The candidate is a character, not a symbol - see [TextRuns]. Strong, because at plan scale a
     * drawn "0" and a sleeve symbol are indistinguishable by shape and size alike, so nothing else
     * in this model can separate them.
     */
    val annotationPenalty: Double = -2.60,

    /**
     * A sliver: far longer than it is wide. Openings are not slivers - even a cable-tray slot stays
     * within about 4:1 - whereas the fragments left over from chopping up structural lines always are.
     */
    val slendernessPenalty: Double = -2.00,

    /**
     * A sprawling skeleton that encloses nothing: a diagonal line, a dimension arrow, a leader, a
     * letter fragment, a stray hatch strip. A drawn opening always encloses space or is solid within
     * its outline, so this is one of the cheapest false-positive filters there is - and unlike
     * [glyphPenalty] it works with no text layer at all.
     */
    val sprawlPenalty: Double = -1.10,
) {
    fun priorFor(kind: SymbolKind): Double = shapePrior[kind] ?: 0.0
    fun contextFor(source: CandidateSource): Double = structureContext[source] ?: 0.0
}

/** Turns a [Candidate] plus its context into an explainable confidence. */
object Scoring {

    fun score(
        candidate: Candidate,
        scale: DrawingScale?,
        config: DetectionConfig,
        prototypes: PrototypeLibrary? = null,
    ): Evidence {
        val w = config.scoring
        val terms = LinkedHashMap<String, Double>()

        terms["shape:${candidate.kind.name.lowercase()}"] = w.priorFor(candidate.kind) * candidate.shapeConfidence

        val sizeMm = scale?.let { candidate.sizePx * it.mmPerPx }
        if (sizeMm != null) {
            val membership = Fuzzy.trapezoid(
                sizeMm,
                absMin = config.absoluteMinMm,
                softMin = config.softMinMm,
                softMax = config.softMaxMm,
                absMax = config.absoluteMaxMm,
            )
            // Map 0..1 membership onto -1..+1 so an odd size actively counts against the candidate.
            terms["size"] = w.sizePlausibility * (2.0 * membership - 1.0)
        }

        val label = candidate.label
        if (label != null) {
            val searchRadius = labelSearchRadius(candidate, config)
            val distance = candidate.labelDistancePx ?: 0.0
            val proximity = Fuzzy.falling(distance, good = 0.0, bad = searchRadius)
            terms["label"] = w.labelSupport * label.strength * proximity

            val declared = label.declaredSizeMm
            if (declared != null && sizeMm != null) {
                val measured = if (candidate.kind.isRound || label.diameterMm != null) {
                    sizeMm
                } else {
                    candidate.box.longSide * scale.mmPerPx
                }
                terms["sizeMatch"] = w.sizeAgreement * sizeAgreement(measured, declared)
            }
        }

        val context = w.contextFor(candidate.source)
        if (context != 0.0) terms["context:${candidate.source.name.lowercase()}"] = context
        if (candidate.source == CandidateSource.FREE_STANDING && candidate.nearWall) {
            terms["onStructure"] = w.wallProximity
        }

        if (candidate.repetitionCount > 1) {
            terms["repeats"] = w.repetition * min(1.0, (candidate.repetitionCount - 1) / 4.0)
        }

        if (prototypes != null && !prototypes.isEmpty) {
            val similarity = prototypes.score(candidate.features, candidate.kind, sizeMm)
            if (similarity != 0.0) terms["learned"] = w.prototypeSimilarity * similarity
        }

        // Encloses nothing and fills little of its own bounding box: ink that merely passes through.
        if (candidate.features.boxFill < 0.45 && candidate.features.inkRatio > 0.90) {
            terms["sprawl"] = w.sprawlPenalty
        }

        if (candidate.inExcludedRegion) terms["excluded"] = w.excludedRegion

        // A reported text box is direct evidence; a text run is inferred, so it counts for less.
        val annotation = when {
            candidate.inTextBox -> 1.0
            candidate.inTextRun -> 0.7
            else -> 0.0
        }
        if (annotation > 0.0) terms["annotation"] = w.annotationPenalty * annotation

        val slenderness = 1.0 - Fuzzy.rising(candidate.box.squareness, bad = 0.10, good = 0.30)
        if (slenderness > 0.0) terms["slender"] = w.slendernessPenalty * slenderness

        val logit = w.bias + terms.values.sum()
        return Evidence(terms = terms, bias = w.bias, logit = logit, confidence = Fuzzy.logistic(logit))
    }

    /** How far from a symbol its own annotation may sit: proportional to the symbol, with a floor. */
    fun labelSearchRadius(candidate: Candidate, config: DetectionConfig): Double =
        maxOf(config.labelSearchMinPx, candidate.sizePx * config.labelSearchFactor)
}
