package com.blackcode.cascoscan.detect

import kotlin.math.abs

/**
 * How many millimetres of *real building* one rendered page pixel represents.
 *
 * Everything downstream that reasons about plausibility ("is a 110 mm sleeve") needs this.
 * Without it the detector still runs, but size evidence is neutralised, so false positives rise.
 */
data class DrawingScale(
    val mmPerPx: Double,
    val source: Source,
    /** Ratio denominator when known, e.g. 50 for a 1:50 drawing. */
    val ratioDenominator: Double? = null,
) {
    init {
        require(mmPerPx > 0.0) { "mmPerPx must be positive" }
    }

    fun pxToMm(px: Double) = px * mmPerPx
    fun mmToPx(mm: Double) = mm / mmPerPx

    enum class Source {
        /** User declared the drawing ratio (1:50, 1:100, ...) and the render DPI is known. */
        DECLARED_RATIO,

        /** User tapped two points and typed the real distance between them. */
        TWO_POINT,

        /** Derived from diameter labels found next to round symbols (see [ScaleEstimator]). */
        ESTIMATED_FROM_LABELS,
    }

    companion object {
        /**
         * A page rendered at [renderDpi] puts `25.4/dpi` mm of *paper* in a pixel; at scale
         * 1:[ratioDenominator] each paper mm is [ratioDenominator] real mm.
         */
        fun fromRatio(ratioDenominator: Double, renderDpi: Double): DrawingScale {
            require(ratioDenominator > 0 && renderDpi > 0)
            return DrawingScale(25.4 / renderDpi * ratioDenominator, Source.DECLARED_RATIO, ratioDenominator)
        }

        fun fromTwoPoints(a: Pt, b: Pt, realDistanceMm: Double): DrawingScale? {
            val px = a.distanceTo(b)
            if (px < 1.0 || realDistanceMm <= 0.0) return null
            return DrawingScale(realDistanceMm / px, Source.TWO_POINT)
        }
    }
}

/**
 * Recovers the scale from the drawing itself: CAD drawings annotate sleeves with their nominal
 * diameter ("Ø110"), so the ratio between a labelled diameter and the measured pixel diameter of
 * the symbol it belongs to *is* the scale. Taking the median over many such pairs shrugs off the
 * odd mis-association.
 */
object ScaleEstimator {

    data class Result(val scale: DrawingScale, val samples: Int, val spread: Double)

    /**
     * @param observations measured pixel size paired with the millimetre value read from its label.
     * @param minSamples below this the estimate is not trustworthy enough to use unattended.
     */
    fun estimate(observations: List<Pair<Double, Double>>, minSamples: Int = 3): Result? {
        val ratios = observations
            .filter { (px, mm) -> px > 2.0 && mm > 5.0 }
            .map { (px, mm) -> mm / px }
            .sorted()
        if (ratios.size < minSamples) return null
        val median = ratios[ratios.size / 2]
        if (median <= 0.0) return null
        // Median absolute deviation, relative: a tight cluster means the associations were sound.
        val mad = ratios.map { abs(it - median) }.sorted()[ratios.size / 2]
        val spread = mad / median
        return Result(DrawingScale(median, DrawingScale.Source.ESTIMATED_FROM_LABELS), ratios.size, spread)
    }
}
