package com.blackcode.cascoscan.detect

import kotlin.math.max
import kotlin.math.min

/**
 * Separates the drawing's *structure* (wall faces, slab edges, grid and dimension lines) from its
 * *symbols*.
 *
 * This is the step that decides whether the app finds anything real. A sleeve drawn through a wall
 * is not an isolated blob: its outline touches both wall faces, so plain connected-component
 * labelling swallows it into one page-sized component along with every other wall. Stripping the
 * long axis-aligned runs first leaves the symbol standing on its own.
 */
object StructuralLines {

    /**
     * Pixels belonging to an axis-aligned ink run of at least [minRun] pixels - equivalent to a
     * morphological opening with a 1xL and an Lx1 kernel, done with run lengths so it costs one
     * pass per axis instead of one per kernel position.
     */
    fun lineMask(mask: BinaryImage, minRun: Int): BinaryImage {
        val w = mask.width
        val h = mask.height
        val out = BooleanArray(w * h)

        for (y in 0 until h) {
            var runStart = -1
            for (x in 0..w) {
                val isInk = x < w && mask.ink[y * w + x]
                if (isInk) {
                    if (runStart < 0) runStart = x
                } else {
                    if (runStart >= 0 && x - runStart >= minRun) {
                        for (xx in runStart until x) out[y * w + xx] = true
                    }
                    runStart = -1
                }
            }
        }
        for (x in 0 until w) {
            var runStart = -1
            for (y in 0..h) {
                val isInk = y < h && mask.ink[y * w + x]
                if (isInk) {
                    if (runStart < 0) runStart = y
                } else {
                    if (runStart >= 0 && y - runStart >= minRun) {
                        for (yy in runStart until y) out[yy * w + x] = true
                    }
                    runStart = -1
                }
            }
        }
        return BinaryImage(w, h, out)
    }

    /**
     * Labels [mask] while bridging gaps of up to [bridgeRadius] pixels, then reports each component
     * with its *true* ink only.
     *
     * Removing wall lines snips an embedded symbol's outline into arcs; bridging reconnects them
     * without the shape distortion that a dilate/erode pair would leave behind, because the
     * dilation is used purely to decide connectivity and then discarded.
     */
    fun labelBridged(mask: BinaryImage, bridgeRadius: Int, minArea: Int): List<Component> {
        val connectivity = if (bridgeRadius > 0) mask.dilate(bridgeRadius) else mask
        val w = mask.width
        val coarse = ConnectedComponents.label(connectivity, minArea = 1)
        val out = ArrayList<Component>(coarse.size)
        for (c in coarse) {
            val real = c.pixels.filter { mask.ink[it] }
            if (real.size < minArea) continue
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            for (p in real) {
                val x = p % w
                val y = p / w
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            out += Component(c.id, IBox(left, top, right, bottom), real.toIntArray(), w)
        }
        return out
    }

    /**
     * Shortest distance from [box] to any set pixel of [mask], searched outwards from the box and
     * capped at [maxSearch]. Returns [maxSearch] + 1 when nothing is within range.
     */
    fun distanceToMask(mask: BinaryImage, box: IBox, maxSearch: Int): Double {
        val search = box.expand(maxSearch, mask.width, mask.height)
        var best = (maxSearch + 1).toDouble()
        for (y in search.top..search.bottom) {
            for (x in search.left..search.right) {
                if (!mask.ink[y * mask.width + x]) continue
                val dx = when {
                    x < box.left -> (box.left - x).toDouble()
                    x > box.right -> (x - box.right).toDouble()
                    else -> 0.0
                }
                val dy = when {
                    y < box.top -> (box.top - y).toDouble()
                    y > box.bottom -> (y - box.bottom).toDouble()
                    else -> 0.0
                }
                val d = kotlin.math.hypot(dx, dy)
                if (d < best) {
                    best = d
                    if (best == 0.0) return 0.0
                }
            }
        }
        return best
    }
}

/**
 * Mines an oversized component - a wall network, a slab outline, a title block - for the symbols
 * hiding inside it.
 *
 * Two things are recovered:
 *  1. what is left of the component once its long runs are removed (an embedded sleeve outline);
 *  2. the voids it encloses (how a large rectangular opening is drawn: a gap bounded by structure).
 */
object StructureAnalyzer {

    data class Found(val component: Component, val source: CandidateSource)

    fun analyse(
        structure: Component,
        pageWidth: Int,
        minRun: Int,
        minArea: Int,
        maxSymbolPx: Int,
        minVoidPx: Int,
        bridgeRadius: Int,
    ): List<Found> {
        val box = structure.box
        val w = box.width
        val h = box.height
        if (w < 3 || h < 3) return emptyList()

        val local = BooleanArray(w * h)
        for (p in structure.pixels) {
            val x = p % pageWidth - box.left
            val y = p / pageWidth - box.top
            local[y * w + x] = true
        }
        val localMask = BinaryImage(w, h, local)

        val found = ArrayList<Found>()

        // (1) Strip the long runs and see what stands on its own.
        val lines = StructuralLines.lineMask(localMask, minRun)
        val residue = localMask.andNot(lines)
        if (residue.inkCount >= minArea) {
            for (c in StructuralLines.labelBridged(residue, bridgeRadius, minArea)) {
                if (c.box.longSide > maxSymbolPx) continue
                found += Found(toPageSpace(c, box, pageWidth), CandidateSource.IN_STRUCTURE)
            }
        }

        // (2) Voids: background regions the structure fully encloses.
        for (void in enclosedVoids(localMask, minVoidPx)) {
            if (void.box.longSide > maxSymbolPx) continue
            found += Found(toPageSpace(void, box, pageWidth), CandidateSource.VOID_IN_STRUCTURE)
        }
        return found
    }

    private fun toPageSpace(local: Component, box: IBox, pageWidth: Int): Component {
        val lw = box.width
        val pixels = IntArray(local.pixels.size)
        for (i in local.pixels.indices) {
            val p = local.pixels[i]
            val x = p % local.imageWidth + box.left
            val y = p / local.imageWidth + box.top
            pixels[i] = y * pageWidth + x
        }
        val shifted = IBox(
            local.box.left + box.left,
            local.box.top + box.top,
            local.box.right + box.left,
            local.box.bottom + box.top,
        )
        check(lw > 0)
        return Component(local.id, shifted, pixels, pageWidth)
    }

    /**
     * Background regions of [mask] that the border flood cannot reach. Each is returned as a solid
     * component covering the void, i.e. the opening itself rather than the lines around it.
     */
    private fun enclosedVoids(mask: BinaryImage, minSidePx: Int): List<Component> {
        val w = mask.width
        val h = mask.height
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) {
            if (!outside[i] && !mask.ink[i]) {
                outside[i] = true
                stack[sp++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }

        val voidMask = BinaryImage(w, h, BooleanArray(w * h) { !mask.ink[it] && !outside[it] })
        val minArea = max(4, minSidePx * minSidePx / 3)
        return ConnectedComponents.label(voidMask, minArea = minArea)
            .filter { min(it.box.width, it.box.height) >= minSidePx }
    }
}
