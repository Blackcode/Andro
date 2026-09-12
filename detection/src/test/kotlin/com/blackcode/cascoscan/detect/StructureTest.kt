package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The structural pass, tested on its own. If this breaks, every symbol that touches a wall, a grid
 * line or a leader disappears from the app with no other visible symptom - so it gets direct tests
 * rather than being covered only through the detector.
 */
class StructureTest {

    private fun maskOf(canvas: DrawingCanvas) = Binarize.global(canvas.toImage(), 128)

    @Test
    fun `long runs are identified and short strokes are left alone`() {
        val canvas = DrawingCanvas(400, 200)
        canvas.line(10, 50, 380, 50, thickness = 2)
        canvas.line(200, 100, 240, 100, thickness = 2)
        val lines = StructuralLines.lineMask(maskOf(canvas), minRun = 60)
        assertTrue(lines.isInk(200, 50), "the long line is structural")
        assertTrue(!lines.isInk(220, 100), "a 40 px stroke is not structural")
    }

    @Test
    fun `a ring drawn on a grid line is recovered from the line's own blob`() {
        val canvas = DrawingCanvas(400, 200)
        canvas.line(10, 100, 390, 100, thickness = 1)
        canvas.circle(cx = 200, cy = 100, r = 14, thickness = 2)

        val mask = maskOf(canvas)
        val components = ConnectedComponents.label(mask, minArea = 4)
        assertEquals(1, components.size, "the ring and the line are a single blob, which is the problem")

        val config = DetectionConfig()
        val found = StructureAnalyzer.analyse(
            structure = components.first(),
            pageWidth = 400,
            minRun = config.wallRunPx,
            minArea = config.minBlobPx,
            maxSymbolPx = 100,
            minVoidPx = 6,
            bridgeRadius = config.bridgeRadius,
        )
        val ring = found.singleOrNull { it.source == CandidateSource.IN_STRUCTURE && it.component.box.longSide > 20 }
        assertTrue(ring != null, "expected the ring back, got ${found.map { "${it.source}:${it.component.box}" }}")
        assertTrue(ring.component.box.center.distanceTo(Pt(200.0, 100.0)) < 4.0)

        val features = ComponentFeatures.extract(ring.component, config.sealRadius)
        assertEquals(
            SymbolKind.CIRCLE_OUTLINE,
            ShapeClassifier.classify(features).kind,
            "the recovered ring must still measure as a ring (boxFill=${features.boxFill})",
        )
    }

    @Test
    fun `an opening between wall faces is recovered as a void`() {
        val canvas = DrawingCanvas(400, 200)
        // Two wall faces, closed off between x=180 and x=240 to form an opening.
        canvas.line(20, 80, 380, 80, thickness = 2)
        canvas.line(20, 110, 380, 110, thickness = 2)
        canvas.line(180, 80, 180, 110, thickness = 2)
        canvas.line(240, 80, 240, 110, thickness = 2)

        val components = ConnectedComponents.label(maskOf(canvas), minArea = 4)
        val wall = components.maxByOrNull { it.area }!!
        val found = StructureAnalyzer.analyse(
            structure = wall,
            pageWidth = 400,
            minRun = 60,
            minArea = 6,
            maxSymbolPx = 100,
            minVoidPx = 8,
            bridgeRadius = 2,
        )
        val voids = found.filter { it.source == CandidateSource.VOID_IN_STRUCTURE }
        assertEquals(1, voids.size, "exactly one enclosed opening, got ${voids.map { it.component.box }}")
        val box = voids.first().component.box
        assertTrue(box.center.distanceTo(Pt(210.0, 95.0)) < 6.0, "void centred at ${box.center}")
        assertTrue(box.width in 50..62, "void width ${box.width}")
        assertTrue(box.height in 22..32, "void height ${box.height}")
    }

    @Test
    fun `rooms are too large to be mistaken for openings`() {
        val canvas = DrawingCanvas(400, 300)
        canvas.strokeRect(20, 20, 380, 280, thickness = 2)
        val wall = ConnectedComponents.label(maskOf(canvas), minArea = 4).maxByOrNull { it.area }!!
        val found = StructureAnalyzer.analyse(
            structure = wall,
            pageWidth = 400,
            minRun = 60,
            minArea = 6,
            maxSymbolPx = 75,
            minVoidPx = 8,
            bridgeRadius = 2,
        )
        assertTrue(
            found.none { it.source == CandidateSource.VOID_IN_STRUCTURE },
            "the space enclosed by a room's walls is not an opening",
        )
    }

    @Test
    fun `otsu is chosen for line art and a local threshold for a shaded scan`() {
        val clean = DrawingCanvas(200, 200)
        clean.circle(100, 100, 40, 2)
        assertEquals("otsu", Binarize.auto(clean.toImage()).method)

        // Simulate a scan: a broad grey gradient across the page plus the same line work.
        val base = clean.toImage()
        val shaded = ByteArray(base.pixels.size)
        for (y in 0 until base.height) {
            for (x in 0 until base.width) {
                val ink = base[x, y] < 128
                val background = 150 + (x * 80 / base.width)
                shaded[y * base.width + x] = (if (ink) 60 else background).toByte()
            }
        }
        val method = Binarize.auto(GrayImage(base.width, base.height, shaded)).method
        assertTrue(method.startsWith("sauvola"), "expected a local threshold for a shaded page, got $method")
    }
}
