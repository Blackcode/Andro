package com.blackcode.cascoscan.detect

/**
 * A synthetic casco floor plan with known ground truth.
 *
 * Real drawing sets cannot go in a repository - they are client property and far too large - so the
 * suite draws its own sheet containing every case the detector is supposed to handle, and every kind
 * of clutter it is supposed to ignore. Because the generator knows where it put things, tests can
 * assert recall *and* precision rather than just "it found something".
 *
 * Plotted at 1:50 and rendered at 200 dpi, which is a realistic combination for an A1 casco plan on
 * a phone, and puts 6.35 mm of building in each pixel.
 */
object SyntheticSheet {

    const val WIDTH = 1400
    const val HEIGHT = 1000
    const val RATIO = 50.0
    const val DPI = 200.0

    val scale: DrawingScale get() = DrawingScale.fromRatio(RATIO, DPI)

    /** A penetration the detector is expected to find, and why it is there. */
    data class Expected(val name: String, val center: Pt, val note: String)

    /** The title block, which the app excludes on the user's instruction. */
    val titleBlock = IBox(1050, 830, 1340, 940)

    val expected: List<Expected> = listOf(
        Expected("crossed circle A", Pt(300.0, 450.0), "free-standing sleeve symbol on the slab"),
        Expected("crossed circle B", Pt(420.0, 450.0), "repeat of the same symbol"),
        Expected("crossed circle C", Pt(540.0, 450.0), "repeat of the same symbol"),
        Expected("labelled ring", Pt(820.0, 315.0), "plain ring inside a wall band, labelled 110"),
        Expected("ring on a grid line", Pt(1000.0, 650.0), "only separable after structural line removal"),
        Expected("wall opening", Pt(1110.0, 315.0), "a void enclosed by the wall faces"),
        Expected("hatched opening", Pt(330.0, 800.0), "rectangular recess drawn with hatching"),
        Expected("crossed rectangle", Pt(480.0, 800.0), "rectangular opening with both diagonals"),
    )

    /** Clutter that must not be reported, with the reason it is a plausible trap. */
    val decoys: List<Expected> = listOf(
        Expected("column", Pt(230.0, 740.0), "solid square of penetration-like size"),
        Expected("dimension text", Pt(700.0, 120.0), "annotation, some of it cross-shaped"),
        Expected("room name", Pt(300.0, 620.0), "annotation"),
        Expected("title block text", Pt(1150.0, 890.0), "inside the excluded region"),
    )

    /**
     * @param omit names from [expected] to leave out, which is how the suite simulates a builder who
     *   did not form an opening: the same sheet, one penetration short.
     */
    fun render(omit: Set<String> = emptySet()): GrayImage {
        val c = DrawingCanvas(WIDTH, HEIGHT)
        fun drawing(name: String) = name !in omit

        // --- Structure: slab outline, two walls, a grid line. ---
        c.strokeRect(60, 60, 1340, 940, thickness = 2)
        c.line(200, 300, 1200, 300, thickness = 2)
        c.line(200, 330, 1200, 330, thickness = 2)
        c.line(600, 330, 600, 900, thickness = 2)
        c.line(630, 330, 630, 900, thickness = 2)
        c.line(100, 650, 1300, 650, thickness = 1)

        // --- Penetrations. ---
        if (drawing("crossed circle A")) c.crossedCircle(300, 450, r = 11, thickness = 2)
        if (drawing("crossed circle B")) c.crossedCircle(420, 450, r = 11, thickness = 2)
        if (drawing("crossed circle C")) c.crossedCircle(540, 450, r = 11, thickness = 2)
        if (drawing("labelled ring")) c.circle(820, 315, r = 9, thickness = 2)
        if (drawing("ring on a grid line")) c.circle(1000, 650, r = 10, thickness = 2)
        if (drawing("wall opening")) {
            // The wall faces are closed off at both ends, enclosing the gap between them.
            c.line(1080, 300, 1080, 330, thickness = 2)
            c.line(1140, 300, 1140, 330, thickness = 2)
        }
        if (drawing("hatched opening")) {
            c.strokeRect(300, 780, 360, 820, thickness = 2)
            c.hatchRect(303, 783, 357, 817, spacing = 5)
        }
        if (drawing("crossed rectangle")) c.crossedRect(450, 780, 510, 820, thickness = 2)

        // --- Clutter. ---
        c.fillRect(210, 720, 250, 760)
        c.glyphRun(x = 640, y = 114, count = 10, heightPx = 12, seed = 3)
        c.glyphRun(x = 250, y = 614, count = 9, heightPx = 12, seed = 11)
        c.glyphRun(x = 700, y = 900, count = 12, heightPx = 12, seed = 5)
        c.strokeRect(titleBlock.left + 10, titleBlock.top + 10, titleBlock.right - 10, titleBlock.bottom - 10, 2)
        c.glyphRun(x = 1080, y = 884, count = 8, heightPx = 12, seed = 19)

        return c.toImage()
    }

    /**
     * The text layer a CAD export would provide. Heights are 12 px, matching the glyph clutter, which
     * is what lets the scorer recognise annotation by its size.
     */
    fun text(): List<TextBox> = listOf(
        TextBox("Ø110", IBox(836, 296, 890, 308)),
        TextBox("sparing", IBox(1016, 668, 1084, 680)),
        TextBox("300x250", IBox(300, 752, 368, 764)),
        TextBox("SP-04", IBox(452, 752, 508, 764)),
        // Ordinary drawing text, which must not be read as penetration evidence.
        TextBox("WOONKAMER", IBox(250, 614, 366, 626)),
        TextBox("3600", IBox(640, 114, 700, 126)),
        TextBox("schaal 1:50", IBox(1080, 884, 1200, 896)),
    )

    fun config(excludeTitleBlock: Boolean = true) = DetectionConfig(
        excludedRegions = if (excludeTitleBlock) listOf(titleBlock) else emptyList(),
    )

    fun input(
        pageIndex: Int = 0,
        withText: Boolean = true,
        withScale: Boolean = true,
        omit: Set<String> = emptySet(),
    ) = PageInput(
        pageIndex = pageIndex,
        image = render(omit),
        scale = if (withScale) scale else null,
        text = if (withText) text() else emptyList(),
        sheetName = "Level 01 - casco",
    )
}
