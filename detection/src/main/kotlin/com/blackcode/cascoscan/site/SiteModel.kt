package com.blackcode.cascoscan.site

import com.blackcode.cascoscan.detect.Evidence
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.Pt
import com.blackcode.cascoscan.detect.SizeMm

/** What a penetration looks like in a photograph of the finished structure. */
enum class SiteKind {
    /** A cored or cast hole, nothing in it: a dark round opening. */
    OPEN_HOLE,

    /** A hole with a sleeve or pipe in it, which reads as a ring around the dark centre. */
    SLEEVED_HOLE,

    /** A rectangular opening or recess. */
    RECT_OPENING,

    /** Round and dark enough to be an opening, but too irregular to classify with confidence. */
    UNCLEAR,
}

/**
 * Everything measured about one dark region in a photograph.
 *
 * The features are chosen to answer one question that matters more than any other on a building site:
 * **is this a hole, or is it a shadow?** They are the same shape, the same darkness, and often the same
 * size. What separates them is that a hole has a hard edge - the transition from wall to void happens
 * in a pixel or two - whereas a shadow's edge is a gradient several pixels wide. [edgeTransitionPx]
 * measures exactly that, and it does most of the work here.
 */
data class SiteFeatures(
    val box: IBox,
    val pixelCount: Int,
    val ellipse: Ellipse,
    /** Overlap between the region and its own fitted ellipse: ~1 for a real ellipse. */
    val ellipseIou: Double,
    /** Overlap with the oriented bounding box: ~1 for a rectangle, ~0.785 for an ellipse. */
    val boxIou: Double,
    /** Region area over convex hull area: ~1 for a hole, lower for a ragged stain. */
    val solidity: Double,
    val interiorMean: Double,
    /** Mean intensity of the wall just beyond the region. */
    val surroundMean: Double,
    /**
     * How wide the light-to-dark transition is at the boundary, in pixels. The single most useful
     * number here: an edge of a void is 1-3 px, the edge of a shadow is 8 px and upwards.
     */
    val edgeTransitionPx: Double,
    /**
     * Difference in brightness between the ring immediately outside the region and the wall further
     * out. A sleeve or pipe collar shows up here; bare concrete does not.
     */
    val ringContrast: Double,
    /** True when the region runs into the edge of the frame, so its shape is only partly visible. */
    val touchesFrameEdge: Boolean,
) {
    /** Absolute brightness difference between the wall and the region interior. */
    val contrast: Double get() = surroundMean - interiorMean

    /**
     * A collar around the opening, of the kind a sleeve or pipe leaves.
     *
     * Requires a hard edge as well as a brightness step, because the penumbra of a shadow produces the
     * same step: it is darker than the wall beyond it, so a soft shadow would otherwise be credited with
     * a sleeve it does not have.
     */
    val hasSleeveRing: Boolean
        get() = ringContrast >= 14.0 &&
            edgeTransitionPx != -1.0 &&
            edgeTransitionPx <= 3.0

    val center: Pt get() = ellipse.center
}

/**
 * One penetration observed in the building, as opposed to one required by the drawing.
 *
 * [sizeMm] is measured from the photograph, so it carries the error of both the calibration and the
 * viewing angle; [Ellipse.tiltDeg] says how far off-axis the shot was, and past [SiteConfig.maxTiltDeg]
 * the size should be treated as indicative only.
 */
data class SiteObservation(
    val id: String,
    val kind: SiteKind,
    val confidence: Double,
    val features: SiteFeatures,
    val evidence: Evidence = Evidence.EMPTY,
    /** Null when the photograph has not been calibrated, in which case only positions are usable. */
    val sizeMm: SizeMm? = null,
    /** Photograph this was seen in, so a finding can point at its evidence. */
    val photoUri: String? = null,
) {
    val center: Pt get() = features.center
    val tiltDeg: Double get() = features.ellipse.tiltDeg

    fun sizeIsReliable(config: SiteConfig) = sizeMm != null && tiltDeg <= config.maxTiltDeg

    val describeSize: String
        get() = sizeMm?.let { size ->
            if (kind == SiteKind.RECT_OPENING) {
                "${size.width.toInt()}x${size.height.toInt()}"
            } else {
                "Ø${size.diameter.toInt()}"
            }
        } ?: "size unknown"
}

/** Tunables for photographic detection. Defaults suit a phone photo of a casco wall or slab. */
data class SiteConfig(
    /**
     * Detection runs on a reduced copy of the photograph. A 12 MP frame costs more memory than it buys
     * accuracy: at 2 MP a 110 mm sleeve photographed from two metres is still tens of pixels across.
     */
    val workingPixels: Int = 2_000_000,
    /**
     * Radius of the closing kernel, as a fraction of the working image's short side. Must exceed the
     * largest penetration's radius in the frame, or that penetration becomes part of its own background
     * estimate and disappears.
     */
    val backgroundRadiusFraction: Double = 0.18,
    /** Regions smaller than this many pixels in the working image are noise. */
    val minPixels: Int = 120,
    /** Regions larger than this fraction of the frame are walls, doorways, or the floor. */
    val maxAreaFraction: Double = 0.20,
    /** Plausible real penetration sizes, in mm. */
    val absoluteMinMm: Double = 30.0,
    val softMinMm: Double = 60.0,
    val softMaxMm: Double = 1200.0,
    val absoluteMaxMm: Double = 3000.0,
    /** Beyond this viewing angle the measured diameter stops being trustworthy. */
    val maxTiltDeg: Double = 62.0,
    /**
     * Below this ratio of minor to major axis a region is discarded outright rather than scored down.
     * 0.12 is an 83-degree view of a circle: nobody photographs a wall from there, so a shape this
     * elongated is a crack, a construction joint, a cable shadow or a skirting line. Making it a gate
     * rather than a penalty stops a long dark line from out-scoring its own penalty on the strength of
     * being genuinely dark, genuinely sharp-edged and genuinely convex - which a joint is.
     */
    val minAxisRatio: Double = 0.12,
    val acceptThreshold: Double = 0.55,
    val reviewThreshold: Double = 0.30,
    val scoring: SiteScoringWeights = SiteScoringWeights(),
)

/**
 * Evidence weights in logit units, in the same explainable form as the drawing detector: an auditor who
 * is told a hole is missing is entitled to see why the app thinks the other ones are present.
 */
data class SiteScoringWeights(
    /**
     * Most dark patches on a construction site are not holes. Set low deliberately: the terms below are
     * individually satisfiable by shadows and stains, and only their combination should convict.
     */
    val bias: Double = -3.20,

    /** How well the region matches the ellipse or rectangle it was classified as. */
    val shapeFit: Double = 2.00,

    /** Darker than its surroundings. Signed: barely darker argues actively against. */
    val contrast: Double = 1.10,

    /**
     * Dark in absolute terms, not merely relative. You can see *into* a hole, so its interior reads
     * near-black; concrete texture and light staining never do, however much they stand out locally.
     */
    val darkness: Double = 1.20,

    /**
     * A hard edge, and deliberately the heaviest term in the model - heavier than any other single
     * piece of evidence.
     *
     * That is not arbitrary. Every other term here can be satisfied in full by a hard shadow: it is
     * round, it is convex, it is the right size, it is as dark as a void, and it is far darker than the
     * wall around it. Edge width is the only measurement that separates them, so it has to be able to
     * outvote the rest of the model combined.
     */
    val edgeSharpness: Double = 4.00,

    /** Convex and clean rather than ragged. */
    val solidity: Double = 1.00,

    /** Plausible real-world size, once the frame has been calibrated. */
    val size: Double = 1.20,

    /** A sleeve collar around the opening. Corroborating, never required. */
    val ring: Double = 0.60,

    /** Too elongated to be a hole viewed at any sane angle: a crack, a joint, a cable shadow. */
    val elongationPenalty: Double = -2.20,

    /** Only partly in frame, so its shape and size are guesses. */
    val clippedPenalty: Double = -1.30,

    /**
     * Matches neither an ellipse nor a rectangle. Applied in full rather than scaled by the fit, for
     * the same reason the drawing detector does: the worse the shape matches anything, the more it
     * argues against - scaling the penalty by the fit cancels it exactly when it is most deserved.
     */
    val unclearPenalty: Double = -1.60,
)
