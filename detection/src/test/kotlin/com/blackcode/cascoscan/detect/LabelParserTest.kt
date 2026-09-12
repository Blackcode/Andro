package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelParserTest {

    private fun parse(text: String) = LabelParser.parse(TextBox(text, IBox(0, 0, 40, 10)))

    @Test
    fun `reads diameters in the notations drawings actually use`() {
        assertEquals(110.0, assertNotNull(parse("Ø110")).diameterMm)
        assertEquals(110.0, assertNotNull(parse("Ø 110")).diameterMm)
        assertEquals(125.0, assertNotNull(parse("D=125")).diameterMm)
        assertEquals(160.0, assertNotNull(parse("dia 160 mm")).diameterMm)
        assertEquals(200.0, assertNotNull(parse("DN200")).diameterMm)
    }

    @Test
    fun `reads rectangular sizes`() {
        val label = assertNotNull(parse("300x200"))
        assertEquals(300.0, label.widthMm)
        assertEquals(200.0, label.heightMm)
        assertEquals("300x200", label.sizeText)
        assertEquals(400.0, assertNotNull(parse("400 × 250")).widthMm)
    }

    @Test
    fun `recognises penetration vocabulary across the languages a casco set mixes`() {
        for (word in listOf(
            "sparing", "vloersparing", "doorvoer", "mantelbuis", "kernboring",
            "Durchbruch", "Aussparung", "sleeve", "penetration", "opening",
            "réservation", "Trémie",
        )) {
            val label = parse(word)
            assertNotNull(label, "should recognise '$word'")
            assertNotNull(label.keyword, "'$word' should set a keyword")
        }
    }

    @Test
    fun `reads penetration tags`() {
        assertEquals("SP-14", assertNotNull(parse("SP-14")).tag)
        assertEquals("VS-3", assertNotNull(parse("VS 3")).tag)
        assertEquals("DB-102", assertNotNull(parse("DB102 Durchbruch")).tag)
    }

    @Test
    fun `combined annotation carries every field`() {
        val label = assertNotNull(parse("SP-07 sparing Ø110"))
        assertEquals("SP-07", label.tag)
        assertEquals("sparing", label.keyword)
        assertEquals(110.0, label.diameterMm)
        assertEquals(1.0, label.strength, "a fully specified label is maximal evidence")
    }

    @Test
    fun `ordinary drawing text is not a penetration label`() {
        assertNull(parse("WOONKAMER"))
        assertNull(parse("peil = 0"))
        assertNull(parse("schaal 1:50"))
        assertNull(parse(""))
        // A bare dimension string is a dimension, not a size declaration for an opening.
        assertNull(parse("3600"))
    }

    @Test
    fun `implausible numbers are rejected rather than believed`() {
        assertNull(parse("Ø5"), "5 mm is not a penetration diameter")
        assertNull(parse("D=99999"))
    }

    @Test
    fun `label strength ranks a keyword plus size above a bare tag`() {
        val full = assertNotNull(parse("sparing Ø110"))
        val tagOnly = assertNotNull(parse("SP-14"))
        assertTrue(full.strength > tagOnly.strength)
    }

}
