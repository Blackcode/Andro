package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.Binarize
import com.blackcode.cascoscan.detect.BinaryImage
import com.blackcode.cascoscan.detect.ConnectedComponents
import com.blackcode.cascoscan.detect.Evidence
import com.blackcode.cascoscan.detect.GrayImage
import com.blackcode.cascoscan.detect.SizeMm
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A photograph handed to the site detector. */
data class SitePhoto(
    val image: GrayImage,
    /**
     * Millimetres per pixel of [image], from calibrating the shot against something of known size.
     * Null is allowed: positions and shapes still work, sizes are simply not reported.
     */
    val mmPerPx: Double? = null,
    val photoUri: String? = null,
)

/** What the detector found in one photograph. */
data class SitePhotoResult(
    val observations: List<SiteObservation>,
    /**
     * The reduced image everything was measured on. All geometry in [observations] is in *this* image's
     * pixels, and this is also the right image to draw the overlay on, so no coordinate mapping is
     * needed anywhere.
     */
    val workingImage: GrayImage,
    val downsampleFactor: Int,
    val diagnostics: Diagnostics,
) {
    val accepted: List<SiteObservation> get() = observations.filter { it.confidence >= diagnostics.acceptThreshold }
    val needingReview: List<SiteObservation>
        get() = observations.filter { it.confidence < diagnostics.acceptThreshold }

    data class Diagnostics(
        val backgroundRadiusPx: Int,
        val topHatThreshold: Int,
        val darkRegions: Int,
        val droppedBySize: Int,
        val droppedByShape: Int,
        val droppedByConfidence: Int,
        val acceptThreshold: Double,
        val calibrated: Boolean,
        val elapsedMs: Long,
    )
}

/**
 * Finds penetrations in a photograph of the built structure.
 *
 * The shape of the problem is different from reading a drawing. There are no symbols and no conventions;
 * there is a grey wall, uneven light, and a dark patch that is either a hole or a shadow. So the pipeline
 * is short and every step is aimed at that one distinction:
 *
 *  1. **reduce** the frame - 2 MP is plenty, and a 12 MP raster is memory the phone will not spare;
 *  2. **remove the lighting** with a morphological closing wider than the largest hole, leaving a black
 *     top-hat in which plain wall is zero however it happens to be lit;
 *  3. **threshold and label** what is left: the dark patches;
 *  4. **measure** each one - the fitted ellipse, how well the region matches it, how convex it is, how
 *     much darker than the wall, and crucially how *abruptly* it becomes dark;
 *  5. **score** with explainable weights, and keep anything worth a second look.
 *
 * The measurement that carries the accuracy is [SiteFeatures.edgeTransitionPx]. A shadow is the same
 * shape, the same darkness and the same size as a hole; what it cannot be is sharp-edged.
 */
class SiteDetector(private val config: SiteConfig = SiteConfig()) {

    fun detect(photo: SitePhoto): SitePhotoResult {
        val started = System.currentTimeMillis()

        val factor = downsampleFactor(photo.image, config.workingPixels)
        val working = photo.image.downsample(factor)
        val mmPerPx = photo.mmPerPx?.times(factor)

        val radius = max(8, (config.backgroundRadiusFraction * min(working.width, working.height)).roundToInt())
        val topHat = GrayMorphology.blackTopHat(working, radius)

        // Otsu on the top-hat, floored: plain wall sits at zero, so without a floor the threshold
        // chases texture noise on a photograph that happens to contain no holes at all.
        val threshold = max(MIN_TOP_HAT, Binarize.otsuThreshold(topHat))
        val mask = BooleanArray(topHat.pixels.size) { (topHat.pixels[it].toInt() and 0xFF) >= threshold }
        val dark = BinaryImage(working.width, working.height, mask)

        val maxArea = (config.maxAreaFraction * working.width * working.height).roundToInt()
        val components = ConnectedComponents.label(dark, minArea = config.minPixels)

        var droppedBySize = 0
        var droppedByShape = 0
        val scored = ArrayList<Pair<SiteFeatures, Evidence>>()
        val kinds = ArrayList<SiteKind>()
        for (component in components) {
            if (component.area > maxArea) {
                droppedBySize++
                continue
            }
            val features = SiteFeatureExtractor.extract(component, working) ?: continue
            // A crack or a joint is discarded here rather than scored down; see SiteConfig.minAxisRatio.
            if (features.ellipse.axisRatio < config.minAxisRatio) {
                droppedByShape++
                continue
            }
            val diameterMm = mmPerPx?.let { features.ellipse.diameterPx * it }
            if (diameterMm != null && (diameterMm < config.absoluteMinMm || diameterMm > config.absoluteMaxMm)) {
                droppedBySize++
                continue
            }
            val kind = classify(features)
            scored += features to SiteScoring.score(features, kind, diameterMm, config)
            kinds += kind
        }

        val surviving = scored.indices
            .filter { scored[it].second.confidence >= config.reviewThreshold }
            .sortedByDescending { scored[it].second.confidence }

        val observations = surviving.mapIndexed { index, i ->
            val (features, evidence) = scored[i]
            val kind = kinds[i]
            SiteObservation(
                id = "S${index + 1}",
                kind = kind,
                confidence = evidence.confidence,
                features = features,
                evidence = evidence,
                sizeMm = mmPerPx?.let { scale ->
                    if (kind == SiteKind.RECT_OPENING) {
                        // The oriented box, not the ellipse, is the opening's real extent.
                        SizeMm(
                            features.ellipse.semiMajor * 2.0 * RECT_FROM_ELLIPSE * scale,
                            features.ellipse.semiMinor * 2.0 * RECT_FROM_ELLIPSE * scale,
                        )
                    } else {
                        // Major axis is the true diameter whatever the viewing angle - see [Ellipse].
                        val d = features.ellipse.diameterPx * scale
                        SizeMm(d, d)
                    }
                },
                photoUri = photo.photoUri,
            )
        }

        return SitePhotoResult(
            observations = observations,
            workingImage = working,
            downsampleFactor = factor,
            diagnostics = SitePhotoResult.Diagnostics(
                backgroundRadiusPx = radius,
                topHatThreshold = threshold,
                darkRegions = components.size,
                droppedBySize = droppedBySize,
                droppedByShape = droppedByShape,
                droppedByConfidence = scored.size - surviving.size,
                acceptThreshold = config.acceptThreshold,
                calibrated = mmPerPx != null,
                elapsedMs = System.currentTimeMillis() - started,
            ),
        )
    }

    /**
     * Round or rectangular, by whichever the region actually matches: a solid ellipse fills its own
     * fitted ellipse and only about 79% of its oriented box, and a solid rectangle does the reverse.
     */
    private fun classify(f: SiteFeatures): SiteKind {
        val roundish = f.ellipseIou
        val boxish = f.boxIou
        if (max(roundish, boxish) < 0.62) return SiteKind.UNCLEAR
        return when {
            roundish >= boxish && f.hasSleeveRing -> SiteKind.SLEEVED_HOLE
            roundish >= boxish -> SiteKind.OPEN_HOLE
            else -> SiteKind.RECT_OPENING
        }
    }

    /** Largest integer reduction that keeps the frame under the working budget. */
    private fun downsampleFactor(image: GrayImage, budget: Int): Int {
        val pixels = image.width.toLong() * image.height
        if (pixels <= budget) return 1
        return max(1, sqrt(pixels.toDouble() / budget).let { Math.ceil(it).toInt() })
    }

    private companion object {
        /** A top-hat response below this is concrete texture, not a void. */
        const val MIN_TOP_HAT = 16

        /**
         * A solid rectangle's equivalent ellipse has semi-axes of side/sqrt(3), so the side is
         * sqrt(3)/2 = 0.866 of the ellipse's own axis. Converts the moment fit back to real extents.
         */
        const val RECT_FROM_ELLIPSE = 0.866
    }
}

/** Turns the measurements into an explainable confidence, in the same form as the drawing detector. */
object SiteScoring {

    fun score(features: SiteFeatures, kind: SiteKind, diameterMm: Double?, config: SiteConfig): Evidence {
        val w = config.scoring
        val terms = LinkedHashMap<String, Double>()

        val fit = if (kind == SiteKind.RECT_OPENING) features.boxIou else features.ellipseIou
        terms["shape"] = w.shapeFit * ramp(fit, weak = 0.60, strong = 0.90)
        if (kind == SiteKind.UNCLEAR) terms["shapeless"] = w.unclearPenalty

        // Signed: a patch barely darker than the wall is texture, and should be pushed down for it.
        terms["contrast"] = w.contrast * (2.0 * ramp(features.contrast, weak = 12.0, strong = 60.0) - 1.0)

        // Absolute darkness, which separates a void from a locally-dark patch of concrete.
        terms["dark"] = w.darkness * (2.0 * ramp(150.0 - features.interiorMean, weak = 0.0, strong = 100.0) - 1.0)

        // The shadow test.
        //
        // The thresholds are in 20%-to-80% rise distance, which is what EdgeProfile measures, and that
        // is roughly 0.43 of the full width of a smooth transition - so an edge a human would call
        // "twelve pixels wide" measures about five here. Getting that factor wrong once made this term
        // almost inert, so the numbers are stated in the units they are measured in: a void's edge comes
        // in under 2 px even through lens and JPEG softening, while a penumbra runs from 4 px upwards.
        if (features.edgeTransitionPx != EdgeProfile.UNKNOWN) {
            terms["hardEdge"] = w.edgeSharpness * (2.0 * ramp(5.0 - features.edgeTransitionPx, 0.0, 3.0) - 1.0)
        }

        terms["solidity"] = w.solidity * ramp(features.solidity, weak = 0.78, strong = 0.95)

        if (diameterMm != null) {
            val membership = trapezoid(
                diameterMm,
                config.absoluteMinMm,
                config.softMinMm,
                config.softMaxMm,
                config.absoluteMaxMm,
            )
            terms["size"] = w.size * (2.0 * membership - 1.0)
        }

        // A sleeve collar only counts on a hard-edged opening. The ring around a shadow reads as a
        // brightness step too - the penumbra is darker than the wall beyond it - so without this gate a
        // soft shadow acquires a "sleeve" and the corroboration runs backwards.
        if (features.hasSleeveRing && kind != SiteKind.RECT_OPENING) {
            terms["sleeve"] = w.ring * ramp(features.ringContrast, weak = 14.0, strong = 40.0)
        }

        // Elongation beyond the hard gate in SiteConfig is still worth discouraging.
        val elongation = 1.0 - ramp(features.ellipse.axisRatio, weak = 0.16, strong = 0.34)
        if (elongation > 0.0) terms["elongated"] = w.elongationPenalty * elongation

        if (features.touchesFrameEdge) terms["clipped"] = w.clippedPenalty

        val logit = w.bias + terms.values.sum()
        return Evidence(terms, w.bias, logit, 1.0 / (1.0 + Math.exp(-logit)))
    }

    private fun ramp(v: Double, weak: Double, strong: Double): Double =
        ((v - weak) / (strong - weak)).coerceIn(0.0, 1.0)

    private fun trapezoid(v: Double, absMin: Double, softMin: Double, softMax: Double, absMax: Double) =
        when {
            v <= absMin || v >= absMax -> 0.0
            v < softMin -> (v - absMin) / (softMin - absMin)
            v > softMax -> (absMax - v) / (absMax - softMax)
            else -> 1.0
        }
}
