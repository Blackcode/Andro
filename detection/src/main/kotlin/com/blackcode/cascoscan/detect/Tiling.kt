package com.blackcode.cascoscan.detect

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Runs detection over a page too large to hold in memory at once.
 *
 * This is not an optimisation, it is what makes the app work at all. A penetration must be a dozen
 * pixels across before its form can be measured, and on a 1:100 plan a 110 mm sleeve is 1.1 mm on
 * paper - so the page has to be rasterised at 200-300 dpi, which for an A0 sheet is several hundred
 * megabytes. Tiles keep the peak allocation to one bounded raster while the results still describe
 * the whole page.
 *
 * The overlap is what keeps a symbol from being cut in half by a tile seam: any candidate touching an
 * interior seam is discarded, because the same symbol is guaranteed to sit whole inside the
 * neighbouring tile as long as it is smaller than the overlap.
 */
class TiledPenetrationDetector(
    private val detector: PenetrationDetector,
    private val tiling: Tiling = Tiling(),
) {

    data class Tiling(
        /** Peak raster budget for a single tile. 6 MP of 8-bit grey is ~6 MB plus the label buffer. */
        val maxPixelsPerTile: Int = 6_000_000,
        /**
         * Seam overlap. Symbols wider than this can be clipped at a seam and lost, so it must exceed
         * the largest opening worth finding: 200 px is about 1.3 m at 1:100 and 200 dpi.
         */
        val overlapPx: Int = 200,
    )

    /**
     * @param renderTile renders exactly the requested page-pixel region. Called once per tile, in
     *   order, so the caller can release each raster before the next is produced.
     */
    fun detect(
        pageIndex: Int,
        pageWidth: Int,
        pageHeight: Int,
        scale: DrawingScale?,
        text: List<TextBox> = emptyList(),
        sheetName: String? = null,
        prototypes: PrototypeLibrary? = null,
        renderTile: (IBox) -> GrayImage,
    ): PageResult {
        val tiles = tilesFor(pageWidth, pageHeight, tiling)
        val page = IBox(0, 0, pageWidth - 1, pageHeight - 1)

        val collected = ArrayList<Penetration>()
        val diagnostics = ArrayList<PageResult.Diagnostics>()

        for (tile in tiles) {
            val image = renderTile(tile)
            require(image.width == tile.width && image.height == tile.height) {
                "renderTile returned ${image.width}x${image.height} for $tile"
            }
            val result = detector.detect(
                PageInput(
                    pageIndex = pageIndex,
                    image = image,
                    scale = scale,
                    text = text.mapNotNull { shiftIntoTile(it, tile) },
                    sheetName = sheetName,
                ),
                prototypes = prototypes,
            )
            diagnostics += result.diagnostics
            for (found in result.penetrations) {
                if (touchesInteriorSeam(found.box, tile, page)) continue
                collected += found.copy(
                    center = Pt(found.center.x + tile.left, found.center.y + tile.top),
                    box = IBox(
                        found.box.left + tile.left,
                        found.box.top + tile.top,
                        found.box.right + tile.left,
                        found.box.bottom + tile.top,
                    ),
                    label = found.label?.let { it.copy(box = shift(it.box, tile.left, tile.top)) },
                )
            }
        }

        val merged = renumber(dedupe(collected), pageIndex, pageHeight)
        return PageResult(pageIndex, merged, mergeDiagnostics(diagnostics, tiles.size, collected.size - merged.size))
    }

    /**
     * A grid of overlapping tiles covering the page. Kept as square as the budget allows, because a
     * square tile has the least seam length for its area - fewer symbols land on a seam.
     */
    fun tilesFor(pageWidth: Int, pageHeight: Int, tiling: Tiling = this.tiling): List<IBox> {
        require(pageWidth > 0 && pageHeight > 0)
        val total = pageWidth.toLong() * pageHeight
        if (total <= tiling.maxPixelsPerTile) {
            return listOf(IBox(0, 0, pageWidth - 1, pageHeight - 1))
        }
        val side = max(64.0, sqrt(tiling.maxPixelsPerTile.toDouble()))
        // Each tile advances by (side - overlap), so that is what decides how many are needed.
        val step = max(32.0, side - tiling.overlapPx)
        val cols = max(1, ceil((pageWidth - tiling.overlapPx) / step).toInt())
        val rows = max(1, ceil((pageHeight - tiling.overlapPx) / step).toInt())

        val out = ArrayList<IBox>(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = min((col * step).toInt(), max(0, pageWidth - side.toInt()))
                val top = min((row * step).toInt(), max(0, pageHeight - side.toInt()))
                val right = min(pageWidth - 1, left + side.toInt() - 1)
                val bottom = min(pageHeight - 1, top + side.toInt() - 1)
                val tile = IBox(left, top, right, bottom)
                if (out.none { it == tile }) out += tile
            }
        }
        return out
    }

    /** A label is offered to a tile only if its box is inside it; otherwise it belongs to a neighbour. */
    private fun shiftIntoTile(textBox: TextBox, tile: IBox): TextBox? {
        val clipped = textBox.box.intersection(tile) ?: return null
        // Require most of the text to be in this tile, so one label is not counted in two tiles.
        if (clipped.area * 2 < textBox.box.area) return null
        return TextBox(textBox.text, shift(textBox.box, -tile.left, -tile.top))
    }

    private fun shift(box: IBox, dx: Int, dy: Int) =
        IBox(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)

    /**
     * True when the box runs into a tile edge that is not also a page edge - meaning the symbol may
     * continue into the next tile and what was measured here is a fragment.
     */
    private fun touchesInteriorSeam(box: IBox, tile: IBox, page: IBox): Boolean {
        val margin = 1
        if (box.left <= margin && tile.left > page.left) return true
        if (box.top <= margin && tile.top > page.top) return true
        if (box.right >= tile.width - 1 - margin && tile.right < page.right) return true
        if (box.bottom >= tile.height - 1 - margin && tile.bottom < page.bottom) return true
        return false
    }

    /** In the overlap band the same symbol is found twice; keep the more confident reading. */
    private fun dedupe(found: List<Penetration>): List<Penetration> {
        val sorted = found.sortedByDescending { it.confidence }
        val kept = ArrayList<Penetration>(sorted.size)
        for (candidate in sorted) {
            val duplicate = kept.any { existing ->
                val reach = max(6.0, 0.6 * min(existing.box.longSide, candidate.box.longSide))
                existing.center.distanceTo(candidate.center) <= reach
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    /** Page-level ids in reading order, matching what single-tile detection would have produced. */
    private fun renumber(found: List<Penetration>, pageIndex: Int, pageHeight: Int): List<Penetration> {
        val band = max(1.0, pageHeight * 0.02)
        return found
            .sortedWith(compareBy({ (it.center.y / band).toInt() }, { it.center.x }))
            .mapIndexed { index, p -> p.copy(id = "P${pageIndex + 1}.${index + 1}") }
    }

    private fun mergeDiagnostics(
        parts: List<PageResult.Diagnostics>,
        tileCount: Int,
        seamDuplicates: Int,
    ): PageResult.Diagnostics {
        val first = parts.firstOrNull()
        return PageResult.Diagnostics(
            binarizeMethod = parts.map { it.binarizeMethod }.distinct().joinToString("+")
                .ifEmpty { "none" } + " x$tileCount tiles",
            inkFraction = if (parts.isEmpty()) 0.0 else parts.sumOf { it.inkFraction } / parts.size,
            componentCount = parts.sumOf { it.componentCount },
            structureCount = parts.sumOf { it.structureCount },
            candidateCount = parts.sumOf { it.candidateCount },
            droppedBySize = parts.sumOf { it.droppedBySize },
            droppedByConfidence = parts.sumOf { it.droppedByConfidence } + seamDuplicates,
            labelsFound = parts.sumOf { it.labelsFound },
            labelsMatched = parts.sumOf { it.labelsMatched },
            scaleUsed = first?.scaleUsed,
            scaleEstimate = parts.firstNotNullOfOrNull { it.scaleEstimate },
            acceptThreshold = first?.acceptThreshold ?: DetectionConfig().acceptThreshold,
            elapsedMs = parts.sumOf { it.elapsedMs },
        )
    }
}
