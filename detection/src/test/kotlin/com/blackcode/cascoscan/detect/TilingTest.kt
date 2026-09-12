package com.blackcode.cascoscan.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tiled detection has to produce the same answer as whole-page detection, or the app quietly gets
 * worse on exactly the large sheets it exists for.
 */
class TilingTest {

    private val config = SyntheticSheet.config()

    @Test
    fun `tiles cover the page and overlap`() {
        val tiler = TiledPenetrationDetector(PenetrationDetector(config))
        val tiling = TiledPenetrationDetector.Tiling(maxPixelsPerTile = 250_000, overlapPx = 120)
        val tiles = tiler.tilesFor(1400, 1000, tiling)
        assertTrue(tiles.size > 1, "a 1.4 MP page must be split at a 0.25 MP budget")

        // Every pixel of the page belongs to at least one tile.
        for (x in 0 until 1400 step 37) {
            for (y in 0 until 1000 step 41) {
                assertTrue(tiles.any { it.contains(Pt(x.toDouble(), y.toDouble())) }, "($x,$y) uncovered")
            }
        }
        for (tile in tiles) {
            assertTrue(tile.area <= 250_000 * 1.05, "tile $tile exceeds the budget")
        }
    }

    @Test
    fun `a page small enough to fit is a single tile`() {
        val tiler = TiledPenetrationDetector(PenetrationDetector(config))
        assertEquals(listOf(IBox(0, 0, 1399, 999)), tiler.tilesFor(1400, 1000))
    }

    @Test
    fun `tiled detection finds the same penetrations as a single pass`() {
        val image = SyntheticSheet.render()
        val detector = PenetrationDetector(config)
        val singlePass = detector.detect(SyntheticSheet.input())

        // A budget that forces a 3x3-ish grid over the synthetic sheet.
        val tiler = TiledPenetrationDetector(
            detector,
            TiledPenetrationDetector.Tiling(maxPixelsPerTile = 300_000, overlapPx = 160),
        )
        val tiled = tiler.detect(
            pageIndex = 0,
            pageWidth = SyntheticSheet.WIDTH,
            pageHeight = SyntheticSheet.HEIGHT,
            scale = SyntheticSheet.scale,
            text = SyntheticSheet.text(),
        ) { tile -> image.crop(tile) }

        for (expected in SyntheticSheet.expected) {
            assertTrue(
                tiled.accepted.any { it.center.distanceTo(expected.center) <= 18.0 },
                "tiled detection missed ${expected.name}; found " +
                    tiled.accepted.joinToString { "${it.id}@${it.center.x.toInt()},${it.center.y.toInt()}" },
            )
        }
        val spurious = tiled.accepted.filter { found ->
            SyntheticSheet.expected.none { it.center.distanceTo(found.center) <= 18.0 }
        }
        assertTrue(spurious.isEmpty(), "tiling introduced false positives: ${spurious.map { it.center }}")
        assertEquals(
            singlePass.accepted.size,
            tiled.accepted.size,
            "tiled and single-pass detection should agree on the count",
        )
    }

    @Test
    fun `a symbol on a seam is reported once, not twice`() {
        val image = SyntheticSheet.render()
        val tiler = TiledPenetrationDetector(
            PenetrationDetector(config),
            TiledPenetrationDetector.Tiling(maxPixelsPerTile = 300_000, overlapPx = 160),
        )
        val tiled = tiler.detect(
            pageIndex = 0,
            pageWidth = SyntheticSheet.WIDTH,
            pageHeight = SyntheticSheet.HEIGHT,
            scale = SyntheticSheet.scale,
            text = SyntheticSheet.text(),
        ) { tile -> image.crop(tile) }

        for (expected in SyntheticSheet.expected) {
            val hits = tiled.penetrations.count { it.center.distanceTo(expected.center) <= 18.0 }
            assertTrue(hits <= 1, "${expected.name} was reported $hits times")
        }
        assertEquals(tiled.penetrations.map { it.id }.distinct(), tiled.penetrations.map { it.id })
    }

    @Test
    fun `ids follow reading order across tiles`() {
        val image = SyntheticSheet.render()
        val tiler = TiledPenetrationDetector(
            PenetrationDetector(config),
            TiledPenetrationDetector.Tiling(maxPixelsPerTile = 300_000, overlapPx = 160),
        )
        val tiled = tiler.detect(
            pageIndex = 2,
            pageWidth = SyntheticSheet.WIDTH,
            pageHeight = SyntheticSheet.HEIGHT,
            scale = SyntheticSheet.scale,
        ) { tile -> image.crop(tile) }

        assertTrue(tiled.penetrations.isNotEmpty())
        assertEquals("P3.1", tiled.penetrations.first().id, "ids carry the sheet number, not the tile")
        val bands = tiled.penetrations.map { (it.center.y / (SyntheticSheet.HEIGHT * 0.02)).toInt() }
        assertEquals(bands.sorted(), bands, "rows must come out top to bottom")
    }
}
