package com.blackcode.cascoscan.detect

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns an ink blob into the measurements [ShapeClassifier] needs.
 *
 * Two ideas carry this file.
 *
 * **Measure the enclosed region, not the ink.** A penetration symbol is usually an outline, and the
 * ink statistics of a thin ring and a thin square are nearly identical. Everything here is therefore
 * measured on the region the blob *encloses*, recovered by flooding the background in from outside.
 *
 * **Tolerate breaks in that outline.** Outlines on real drawings are not closed: they are dashed, a
 * leader line clips them, a wall line crossed them and was removed in the structural pass, or the
 * scan simply lost a pixel. A single-pixel breach would let the flood into the interior and destroy
 * every shape measurement at once, so the flood runs against a morphologically *closed* copy of the
 * blob and the result is eroded back. [SEAL_RADIUS] is how wide a gap that survives.
 */
object ComponentFeatures {

    /**
     * Default gap tolerance in pixels; a break of up to roughly twice this no longer opens an outline.
     * Callers inside the app pass [DetectionConfig.sealRadius] instead.
     */
    private const val SEAL_RADIUS = 2

    fun extract(component: Component, sealRadius: Int = SEAL_RADIUS): ShapeFeatures {
        val box = component.box
        val pad = sealRadius + 1
        val w = box.width + 2 * pad
        val h = box.height + 2 * pad

        // Padding keeps the sealing dilation and the border flood away from the array edge, so no
        // feature has to special-case a shape that touches its own bounding box - which they all do.
        val ink = BooleanArray(w * h)
        for (p in component.pixels) {
            val x = p % component.imageWidth - box.left + pad
            val y = p / component.imageWidth - box.top + pad
            ink[y * w + x] = true
        }

        val filledResult = fillInterior(ink, w, h, sealRadius)
        val filled = filledResult.filled
        var filledArea = 0
        for (f in filled) if (f) filledArea++
        val inkArea = component.area
        val holeArea = (filledArea - inkArea).coerceAtLeast(0)

        // --- Outer boundary of the filled region: perimeter and radial statistics. ---
        var cxSum = 0.0
        var cySum = 0.0
        for (i in filled.indices) {
            if (!filled[i]) continue
            cxSum += (i % w).toDouble()
            cySum += (i / w).toDouble()
        }
        val cx = if (filledArea > 0) cxSum / filledArea else (w - 1) / 2.0
        val cy = if (filledArea > 0) cySum / filledArea else (h - 1) / 2.0

        var perimeter = 0
        var radiusSum = 0.0
        var radiusSqSum = 0.0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!filled[i]) continue
                if (filled[i - 1] && filled[i + 1] && filled[i - w] && filled[i + w]) continue
                perimeter++
                val r = hypot(x - cx, y - cy)
                radiusSum += r
                radiusSqSum += r * r
            }
        }
        val radialCv = if (perimeter > 0 && radiusSum > 0.0) {
            val mean = radiusSum / perimeter
            val variance = (radiusSqSum / perimeter - mean * mean).coerceAtLeast(0.0)
            sqrt(variance) / mean
        } else {
            1.0
        }
        val circularity = if (perimeter > 0) {
            (4.0 * PI * filledArea / (perimeter.toDouble() * perimeter)).coerceAtMost(1.5)
        } else {
            0.0
        }

        val symmetry = min(
            mirrorIou(filled, w, h, horizontal = true),
            mirrorIou(filled, w, h, horizontal = false),
        )
        val (diagMin, diagMax) = diagonalCoverage(ink, w, h, pad, box)
        val axial = axialCoverage(ink, w, h, pad, box)
        val hatch = hatchScore(ink, filled, w, h)

        return ShapeFeatures(
            box = box,
            inkArea = inkArea,
            filledArea = filledArea,
            holeArea = holeArea,
            holeCount = filledResult.holeCount,
            outerPerimeter = perimeter,
            inkRatio = if (filledArea > 0) (inkArea.toDouble() / filledArea).coerceAtMost(1.0) else 1.0,
            boxFill = filledArea.toDouble() / box.area,
            circularity = circularity,
            radialCv = radialCv,
            symmetryScore = symmetry,
            diagonalCoverageMin = diagMin,
            diagonalCoverageMax = diagMax,
            axialCoverage = axial,
            hatchScore = hatch,
        )
    }

    private class Filled(val filled: BooleanArray, val holeCount: Int)

    /**
     * The region the blob encloses, plus how many separate voids are inside it.
     *
     * The flood is run on a dilated ("sealed") copy so that small breaks in the outline do not leak,
     * and the result is eroded by the same amount so the boundary lands back where it was. Union with
     * the original ink guarantees no ink is ever lost by that erosion.
     */
    private fun fillInterior(ink: BooleanArray, w: Int, h: Int, sealRadius: Int): Filled {
        val inkMask = BinaryImage(w, h, ink)
        val sealed = if (sealRadius > 0) inkMask.dilate(sealRadius) else inkMask
        val outside = floodBackgroundFromBorder(sealed)

        val sealedFilled = BooleanArray(w * h) { !outside[it] }
        val filled = if (sealRadius > 0) {
            val shrunk = BinaryImage(w, h, sealedFilled).erode(sealRadius).ink
            BooleanArray(w * h) { shrunk[it] || ink[it] }
        } else {
            sealedFilled
        }

        // Count voids on the sealed geometry: a hole is only a hole if it is actually closed.
        var holeCount = 0
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (sealed.ink[start] || outside[start] || seen[start]) continue
            holeCount++
            var sp = 0
            seen[start] = true
            stack[sp++] = start
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                if (x > 0) sp = visit(i - 1, seen, sealed.ink, outside, stack, sp)
                if (x < w - 1) sp = visit(i + 1, seen, sealed.ink, outside, stack, sp)
                if (y > 0) sp = visit(i - w, seen, sealed.ink, outside, stack, sp)
                if (y < h - 1) sp = visit(i + w, seen, sealed.ink, outside, stack, sp)
            }
        }
        return Filled(filled, holeCount)
    }

    private fun visit(
        i: Int,
        seen: BooleanArray,
        ink: BooleanArray,
        outside: BooleanArray,
        stack: IntArray,
        sp: Int,
    ): Int {
        if (seen[i] || ink[i] || outside[i]) return sp
        seen[i] = true
        stack[sp] = i
        return sp + 1
    }

    /** Background cells reachable from the image border. Assumes a padded mask, so the border is free. */
    private fun floodBackgroundFromBorder(mask: BinaryImage): BooleanArray {
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
        return outside
    }

    private fun mirrorIou(mask: BooleanArray, w: Int, h: Int, horizontal: Boolean): Double {
        var inter = 0
        var union = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = mask[y * w + x]
                val b = if (horizontal) mask[y * w + (w - 1 - x)] else mask[(h - 1 - y) * w + x]
                if (a && b) inter++
                if (a || b) union++
            }
        }
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /**
     * Ink coverage along the two diagonals of the *original* bounding box, sampled over the middle
     * 70% so that the outline's own corners cannot fake a cross. A sample hits if ink is within 1 px,
     * which absorbs the rasterisation of a thin diagonal stroke.
     */
    private fun diagonalCoverage(ink: BooleanArray, w: Int, h: Int, pad: Int, box: IBox): Pair<Double, Double> {
        val samples = max(6, min(box.width, box.height))
        fun coverage(rising: Boolean): Double {
            var hits = 0
            for (s in 0 until samples) {
                val t = 0.15 + 0.70 * s / (samples - 1).toDouble()
                val x = pad + (t * (box.width - 1)).roundToInt()
                val y = pad + (if (rising) t * (box.height - 1) else (1.0 - t) * (box.height - 1)).roundToInt()
                if (nearInk(ink, w, h, x, y, 1)) hits++
            }
            return hits.toDouble() / samples
        }
        val a = coverage(true)
        val b = coverage(false)
        return min(a, b) to max(a, b)
    }

    /** Coverage of the centre row and centre column (a crosshair symbol); the weaker axis is returned. */
    private fun axialCoverage(ink: BooleanArray, w: Int, h: Int, pad: Int, box: IBox): Double {
        val midY = pad + (box.height - 1) / 2
        val midX = pad + (box.width - 1) / 2
        var rowHits = 0
        var rowTaken = 0
        for (dx in (box.width * 15 / 100)..(box.width * 85 / 100)) {
            rowTaken++
            if (nearInk(ink, w, h, pad + dx, midY, 1)) rowHits++
        }
        var colHits = 0
        var colTaken = 0
        for (dy in (box.height * 15 / 100)..(box.height * 85 / 100)) {
            colTaken++
            if (nearInk(ink, w, h, midX, pad + dy, 1)) colHits++
        }
        val row = if (rowTaken == 0) 0.0 else rowHits.toDouble() / rowTaken
        val col = if (colTaken == 0) 0.0 else colHits.toDouble() / colTaken
        return min(row, col)
    }

    private fun nearInk(ink: BooleanArray, w: Int, h: Int, x: Int, y: Int, tol: Int): Boolean {
        for (dy in -tol..tol) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            for (dx in -tol..tol) {
                val xx = x + dx
                if (xx < 0 || xx >= w) continue
                if (ink[yy * w + xx]) return true
            }
        }
        return false
    }

    /**
     * Detects parallel hatching: inside the enclosed region, scanlines cross many ink runs whose gaps
     * are all about the same width. A solid fill gives one run, an empty outline two, and a cross
     * gives runs whose spacing changes from line to line - only real hatching is regular in both
     * respects, hence the second factor over the run counts.
     */
    private fun hatchScore(ink: BooleanArray, filled: BooleanArray, w: Int, h: Int): Double {
        if (w < 8 || h < 8) return 0.0
        val rows = ArrayList<Double>()
        val runCounts = ArrayList<Int>()
        val lines = min(24, h)
        for (l in 0 until lines) {
            val y = (h * 10 / 100) + ((h * 80 / 100) * l / max(1, lines - 1))
            if (y < 0 || y >= h) continue
            var x0 = -1
            var x1 = -1
            for (x in 0 until w) {
                if (filled[y * w + x]) {
                    if (x0 < 0) x0 = x
                    x1 = x
                }
            }
            if (x0 < 0 || x1 - x0 < 5) continue

            var runs = 0
            val gaps = ArrayList<Int>()
            var gap = 0
            var prevInk = false
            for (x in x0..x1) {
                val isInk = ink[y * w + x]
                if (isInk && !prevInk) {
                    runs++
                    if (gap > 0 && runs > 1) gaps += gap
                    gap = 0
                } else if (!isInk) {
                    gap++
                }
                prevInk = isInk
            }
            if (runs < 3 || gaps.size < 2) continue
            val mean = gaps.average()
            if (mean < 1.0) continue
            val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
            val regularity = (1.0 - (sd / mean)).coerceIn(0.0, 1.0)
            val density = min(1.0, (runs - 2) / 3.0)
            rows += density * regularity
            runCounts += runs
        }
        if (rows.size < 3) return 0.0
        val meanRuns = runCounts.average()
        val runSd = sqrt(runCounts.sumOf { (it - meanRuns) * (it - meanRuns) } / runCounts.size)
        val consistency = (1.0 - (runSd / max(1.0, meanRuns))).coerceIn(0.0, 1.0)
        return (rows.average() * consistency).coerceIn(0.0, 1.0)
    }
}
