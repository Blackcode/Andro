package com.blackcode.cascoscan.detect

/**
 * One 8-connected ink blob: its bounding box and the page-pixel indices it occupies.
 *
 * Pixel indices (rather than a per-blob sub-mask) keep memory proportional to the ink on the page,
 * which on a line drawing is a couple of percent.
 */
class Component(
    val id: Int,
    val box: IBox,
    /** Indices into the page raster (`y * width + x`), ascending. */
    val pixels: IntArray,
    val imageWidth: Int,
) {
    val area: Int get() = pixels.size

    /** Ink mask of just this blob, in bbox-local coordinates. */
    fun localMask(): BooleanArray {
        val mask = BooleanArray(box.width * box.height)
        for (p in pixels) {
            val x = p % imageWidth
            val y = p / imageWidth
            mask[(y - box.top) * box.width + (x - box.left)] = true
        }
        return mask
    }
}

/**
 * Two-pass 8-connected labelling with union-find. Single allocation of the label buffer, so a
 * 4000x3000 page costs ~48 MB transiently - acceptable for one page at a time on a phone, and the
 * caller is expected to release the raster before moving to the next page.
 */
object ConnectedComponents {

    fun label(mask: BinaryImage, minArea: Int = 1): List<Component> {
        val w = mask.width
        val h = mask.height
        val labels = IntArray(w * h)

        // Union-find over provisional labels; index 0 is unused so 0 can mean "background".
        var parent = IntArray(1024)
        var next = 1

        fun ensureCapacity(n: Int) {
            if (n < parent.size) return
            var cap = parent.size
            while (cap <= n) cap *= 2
            parent = parent.copyOf(cap)
        }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            // Path compression keeps the second pass linear in practice.
            var cur = x
            while (parent[cur] != root) {
                val up = parent[cur]
                parent[cur] = root
                cur = up
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask.ink[y * w + x]) continue
                // Neighbours already visited in raster order: W, NW, N, NE.
                var best = 0
                fun consider(nx: Int, ny: Int) {
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
                    val l = labels[ny * w + nx]
                    if (l == 0) return
                    best = if (best == 0) l else { union(best, l); minOf(find(best), find(l)) }
                }
                consider(x - 1, y)
                consider(x - 1, y - 1)
                consider(x, y - 1)
                consider(x + 1, y - 1)
                if (best == 0) {
                    ensureCapacity(next)
                    parent[next] = next
                    best = next
                    next++
                }
                labels[y * w + x] = best
            }
        }

        // Compact roots to dense ids and accumulate boxes and areas in one pass.
        val remap = IntArray(next)
        var componentCount = 0
        for (l in 1 until next) {
            if (find(l) == l) {
                componentCount++
                remap[l] = componentCount
            }
        }
        if (componentCount == 0) return emptyList()

        val counts = IntArray(componentCount + 1)
        val left = IntArray(componentCount + 1) { Int.MAX_VALUE }
        val top = IntArray(componentCount + 1) { Int.MAX_VALUE }
        val right = IntArray(componentCount + 1) { Int.MIN_VALUE }
        val bottom = IntArray(componentCount + 1) { Int.MIN_VALUE }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val raw = labels[y * w + x]
                if (raw == 0) continue
                val id = remap[find(raw)]
                labels[y * w + x] = id
                counts[id]++
                if (x < left[id]) left[id] = x
                if (x > right[id]) right[id] = x
                if (y < top[id]) top[id] = y
                if (y > bottom[id]) bottom[id] = y
            }
        }

        // Counting sort of pixel indices into per-component slabs.
        val keep = BooleanArray(componentCount + 1)
        var kept = 0
        for (id in 1..componentCount) {
            if (counts[id] >= minArea) {
                keep[id] = true
                kept++
            }
        }
        if (kept == 0) return emptyList()

        val offsets = IntArray(componentCount + 2)
        for (id in 1..componentCount) {
            offsets[id + 1] = offsets[id] + if (keep[id]) counts[id] else 0
        }
        val cursor = offsets.copyOf()
        val flat = IntArray(offsets[componentCount + 1])
        for (i in labels.indices) {
            val id = labels[i]
            if (id == 0 || !keep[id]) continue
            flat[cursor[id]++] = i
        }

        val out = ArrayList<Component>(kept)
        for (id in 1..componentCount) {
            if (!keep[id]) continue
            val from = offsets[id]
            val to = offsets[id + 1]
            out += Component(
                id = id,
                box = IBox(left[id], top[id], right[id], bottom[id]),
                pixels = flat.copyOfRange(from, to),
                imageWidth = w,
            )
        }
        return out
    }
}
