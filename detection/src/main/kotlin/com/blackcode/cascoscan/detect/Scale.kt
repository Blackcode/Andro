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

/**
 * Chooses the resolution to rasterise a page at, and says what that choice costs.
 *
 * The trade-off is unavoidable and worth stating plainly to the user. A symbol must be roughly a
 * dozen pixels across before its form can be measured at all, and how many pixels a 110 mm sleeve
 * gets depends entirely on the plot scale: at 1:50 it is 2.2 mm of paper, at 1:200 only 0.55 mm. So a
 * coarse drawing needs a very high render resolution to stay readable, and past some point the render
 * cost has to win - at which point small openings genuinely cannot be found and the app should say so
 * rather than quietly miss them.
 */
object RenderPlan {

    /** Below this many pixels across, a symbol's shape cannot be measured reliably. */
    const val MIN_SYMBOL_PX = 12

    data class Plan(
        val dpi: Double,
        val scale: DrawingScale,
        /** Smallest real-world size that still spans [MIN_SYMBOL_PX] at this resolution. */
        val smallestReliableMm: Double,
        /** True when [dpi] hit the cap and small openings will be missed. */
        val resolutionLimited: Boolean,
    ) {
        fun pixelsFor(pageWidthPt: Double, pageHeightPt: Double): Pair<Int, Int> =
            (pageWidthPt * dpi / 72.0).toInt() to (pageHeightPt * dpi / 72.0).toInt()
    }

    /**
     * @param ratioDenominator plot scale denominator: 50 for a 1:50 drawing.
     * @param targetSmallestMm smallest opening the audit cares about.
     * @param maxDpi render cap; 300 keeps an A0 sheet to about 35 tiles at the default tile budget.
     */
    fun forRatio(
        ratioDenominator: Double,
        targetSmallestMm: Double = 70.0,
        maxDpi: Double = 300.0,
        minDpi: Double = 120.0,
    ): Plan {
        require(ratioDenominator > 0 && targetSmallestMm > 0 && maxDpi >= minDpi)
        // dpi such that targetSmallestMm spans MIN_SYMBOL_PX: px = mm / (25.4/dpi * ratio).
        val ideal = MIN_SYMBOL_PX * 25.4 * ratioDenominator / targetSmallestMm
        val dpi = ideal.coerceIn(minDpi, maxDpi)
        val scale = DrawingScale.fromRatio(ratioDenominator, dpi)
        return Plan(
            dpi = dpi,
            scale = scale,
            smallestReliableMm = MIN_SYMBOL_PX * scale.mmPerPx,
            resolutionLimited = ideal > maxDpi,
        )
    }
}
