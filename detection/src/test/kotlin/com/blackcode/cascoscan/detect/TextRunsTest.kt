package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRunsTest {

    @Test
    fun `a row of characters is recognised as text`() {
        // Five 12 px glyphs, 3 px apart, on one baseline.
        val boxes = (0 until 5).map { IBox(100 + it * 10, 50, 100 + it * 10 + 6, 62) }
        val flags = TextRuns.detect(boxes)
        assertTrue(flags.all { it }, "every glyph in the row should be marked")
    }

    @Test
    fun `symbols spread across a plan are not text`() {
        // Three identical sleeve symbols 120 px apart: the same height and baseline, but far apart.
        val boxes = (0 until 3).map { IBox(300 + it * 120, 439, 322 + it * 120, 461) }
        assertTrue(TextRuns.detect(boxes).none { it }, "well-separated symbols must not read as text")
    }

    @Test
    fun `two neighbours are not enough to make a run`() {
        val boxes = listOf(IBox(100, 50, 112, 62), IBox(116, 50, 128, 62))
        assertTrue(TextRuns.detect(boxes).none { it }, "two adjacent sleeves are a common layout")
    }

    @Test
    fun `an opening flanked by its closing lines is not text`() {
        // This is the wall-opening geometry: a 57x27 void between two 3x31 closing lines, all on one
        // baseline and touching. Only the sliver exclusion keeps it from reading as three characters.
        val leftEnd = IBox(1079, 300, 1081, 330)
        val opening = IBox(1082, 302, 1138, 328)
        val rightEnd = IBox(1139, 300, 1141, 330)
        val flags = TextRuns.detect(listOf(leftEnd, opening, rightEnd))
        assertFalse(flags[1], "the opening itself must not be penalised as annotation")
        assertTrue(flags.none { it }, "no member of this triplet is a character")
    }

    @Test
    fun `a scanned opening whose closing lines have thickened is still not text`() {
        // The same geometry as above after a blur and a local threshold: the closing lines are now 5 px
        // wide, so they are no longer slender enough to be ruled out as characters. Only the width
        // ratio between the gap and the lines that close it separates them now - and it always will,
        // because an opening is by definition much wider than its own end lines.
        val leftEnd = IBox(1078, 303, 1082, 327)
        val opening = IBox(1083, 303, 1138, 327)
        val rightEnd = IBox(1138, 303, 1142, 327)
        val flags = TextRuns.detect(listOf(leftEnd, opening, rightEnd))
        assertFalse(flags[1], "the opening must not be penalised as annotation")
        assertTrue(flags.none { it }, "no member of this triplet is a character")
    }

    @Test
    fun `a wide glyph beside a narrow one is still text`() {
        // Real lettering varies: an "l", an "o" and a "W" at the same height belong to one word.
        val boxes = listOf(
            IBox(100, 50, 102, 62),
            IBox(106, 50, 114, 62),
            IBox(118, 50, 130, 62),
            IBox(134, 50, 142, 62),
        )
        assertTrue(TextRuns.detect(boxes).all { it }, "a 6:1 width spread is normal lettering")
    }

    @Test
    fun `text on different lines is not joined into one run`() {
        val line1 = (0 until 2).map { IBox(100 + it * 10, 50, 106 + it * 10, 62) }
        val line2 = (0 until 2).map { IBox(100 + it * 10, 90, 106 + it * 10, 102) }
        assertTrue(TextRuns.detect(line1 + line2).none { it }, "two per line is below the run threshold")
    }
}
