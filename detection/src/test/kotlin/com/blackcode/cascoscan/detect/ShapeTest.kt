package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The classifier's contract, exercised on rasterised symbols rather than on hand-written feature
 * values - a feature-level test would pass happily while the rasteriser and the measurements
 * disagreed about what a circle is.
 */
class ShapeTest {

    private fun singleComponent(canvas: DrawingCanvas): Component {
        val mask = Binarize.global(canvas.toImage(), 128)
        val components = ConnectedComponents.label(mask, minArea = 4)
        assertEquals(1, components.size, "test fixture should contain exactly one blob")
        return components.first()
    }

    private fun classify(canvas: DrawingCanvas): Pair<SymbolKind, ShapeFeatures> {
        val component = singleComponent(canvas)
        val features = ComponentFeatures.extract(component)
        return ShapeClassifier.classify(features).kind to features
    }

    @Test
    fun `an outline circle measures as a disc, not as a ring`() {
        val canvas = DrawingCanvas(80, 80)
        canvas.circle(cx = 40, cy = 40, r = 20, thickness = 2)
        val (kind, f) = classify(canvas)
        assertEquals(SymbolKind.CIRCLE_OUTLINE, kind)
        // pi/4 of the bounding box: the interior is recovered even though it was never inked.
        assertTrue(f.boxFill in 0.72..0.85, "boxFill was ${f.boxFill}")
        assertTrue(f.radialCv < 0.05, "radialCv was ${f.radialCv}")
        assertTrue(f.inkRatio < 0.35, "a ring is mostly enclosed space, got ${f.inkRatio}")
        assertEquals(1, f.holeCount)
    }

    @Test
    fun `a solid disc is told apart from a ring`() {
        val canvas = DrawingCanvas(80, 80)
        canvas.disc(cx = 40, cy = 40, r = 20)
        val (kind, f) = classify(canvas)
        assertEquals(SymbolKind.CIRCLE_FILLED, kind)
        assertTrue(f.inkRatio > 0.95, "inkRatio was ${f.inkRatio}")
        assertEquals(0, f.holeCount)
    }

    @Test
    fun `a square outline is rectangular, not round`() {
        val canvas = DrawingCanvas(80, 80)
        canvas.strokeRect(20, 20, 60, 60, thickness = 2)
        val (kind, f) = classify(canvas)
        assertEquals(SymbolKind.RECT_OUTLINE, kind)
        assertTrue(f.boxFill > 0.95, "boxFill was ${f.boxFill}")
        // The corners are what separate a square from a circle.
        assertTrue(f.radialCv > 0.07, "radialCv was ${f.radialCv}")
    }

    @Test
    fun `a crossed circle is recognised as the penetration symbol it is`() {
        val canvas = DrawingCanvas(80, 80)
        canvas.crossedCircle(cx = 40, cy = 40, r = 20, thickness = 2)
        val (kind, f) = classify(canvas)
        assertEquals(SymbolKind.CIRCLE_CROSSED, kind)
        assertTrue(f.diagonalCoverageMin > 0.7, "both diagonals should be inked, got ${f.diagonalCoverageMin}")
    }

    @Test
    fun `a crossed rectangle is recognised`() {
        val canvas = DrawingCanvas(90, 70)
        canvas.crossedRect(15, 15, 70, 55, thickness = 2)
        val (kind, _) = classify(canvas)
        assertEquals(SymbolKind.RECT_CROSSED, kind)
    }

    @Test
    fun `hatching is distinguished from a solid fill and from a cross`() {
        val canvas = DrawingCanvas(90, 70)
        canvas.strokeRect(15, 15, 70, 55, thickness = 2)
        canvas.hatchRect(17, 17, 68, 53, spacing = 5)
        val (kind, f) = classify(canvas)
        assertEquals(SymbolKind.RECT_HATCHED, kind)
        assertTrue(f.hatchScore > 0.45, "hatchScore was ${f.hatchScore}")
        assertTrue(f.inkRatio < 0.8, "hatching is not a solid fill, got ${f.inkRatio}")
    }

    @Test
    fun `an outline broken by a crossing line is still measured as closed`() {
        // This is the case that matters on real sheets: a grid or wall line crosses the symbol and is
        // removed by the structural pass, leaving the ring breached at two points. Without gap
        // tolerance the interior leaks and every shape measurement collapses at once.
        val canvas = DrawingCanvas(80, 80)
        canvas.circle(cx = 40, cy = 40, r = 20, thickness = 2)
        val mask = Binarize.global(canvas.toImage(), 128)
        // Erase the ring where a three-pixel-wide line would have crossed it, on both sides.
        val breached = BooleanArray(mask.ink.size) { mask.ink[it] }
        for (dx in 18..22) {
            for (dy in -1..1) {
                breached[(40 + dy) * 80 + (40 - dx)] = false
                breached[(40 + dy) * 80 + (40 + dx)] = false
            }
        }
        val config = DetectionConfig()
        val components = StructuralLines.labelBridged(BinaryImage(80, 80, breached), config.bridgeRadius, minArea = 4)
        assertEquals(1, components.size, "bridging should keep the breached ring in one piece")
        val f = ComponentFeatures.extract(components.first(), config.sealRadius)
        assertEquals(1, f.holeCount, "the breach must be sealed before the interior is measured")
        assertTrue(f.boxFill in 0.70..0.86, "boxFill was ${f.boxFill}")
        assertEquals(SymbolKind.CIRCLE_OUTLINE, ShapeClassifier.classify(f).kind)
    }

    @Test
    fun `annotation never scores as a penetration`() {
        // Some letters really are cross-shaped, and the classifier is right to say so - an X is an X.
        // Keeping annotation out of the report is the scorer's job, using the things a letter cannot
        // fake: it is the size of the surrounding text, and it encloses nothing.
        val canvas = DrawingCanvas(220, 40)
        canvas.glyphRun(x = 10, y = 10, count = 9, heightPx = 12)
        val mask = Binarize.global(canvas.toImage(), 128)
        val components = ConnectedComponents.label(mask, minArea = 4)
        assertTrue(components.size >= 4, "fixture should produce several glyph blobs")

        val config = DetectionConfig()
        val scale = DrawingScale.fromRatio(50.0, 200.0)
        val candidates = Grouping.repetitionCounts(
            components
                .map { ComponentFeatures.extract(it, config.sealRadius) }
                .filter { it.box.shortSide > 2 }
                .map { f ->
                    val c = ShapeClassifier.classify(f, config)
                    Candidate(features = f, kind = c.kind, shapeConfidence = c.confidence)
                },
        )
        assertTrue(candidates.isNotEmpty(), "fixture should survive the line-fragment filter")

        val worst = candidates.maxOf { candidate ->
            Scoring.score(candidate, scale, config, prototypes = null).confidence
        }
        assertTrue(
            worst < config.acceptThreshold,
            "no glyph may reach the accept threshold; worst was %.3f".format(worst),
        )
    }
}
