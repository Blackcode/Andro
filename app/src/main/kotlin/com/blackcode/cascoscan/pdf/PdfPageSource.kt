package com.blackcode.cascoscan.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.blackcode.cascoscan.detect.GrayImage
import com.blackcode.cascoscan.detect.IBox
import java.io.Closeable

/**
 * Reads a drawing set with the platform's own PDF rasteriser.
 *
 * [android.graphics.pdf.PdfRenderer] ships with Android, needs no dependency and handles the vector
 * PDFs that come out of CAD, which is the whole input format for this app. It has two constraints
 * that shape this class: only one page may be open at a time, and it is not thread-safe - so every
 * call is serialised on one lock.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val lock = Any()

    val pageCount: Int get() = synchronized(lock) { renderer.pageCount }

    /** Page size in PostScript points (1/72 inch), which is how PDF measures a sheet. */
    data class PageSize(val widthPt: Double, val heightPt: Double) {
        val isLandscape: Boolean get() = widthPt >= heightPt

        /** Nearest ISO A-series sheet name, for display: drawings are always plotted on one. */
        fun sheetFormat(): String {
            val longMm = maxOf(widthPt, heightPt) * 25.4 / 72.0
            val shortMm = minOf(widthPt, heightPt) * 25.4 / 72.0
            val formats = listOf(
                "A0" to Pair(1189.0, 841.0), "A1" to Pair(841.0, 594.0), "A2" to Pair(594.0, 420.0),
                "A3" to Pair(420.0, 297.0), "A4" to Pair(297.0, 210.0),
            )
            val best = formats.minByOrNull { (_, size) ->
                kotlin.math.abs(size.first - longMm) + kotlin.math.abs(size.second - shortMm)
            }
            val tolerance = 20.0
            return best
                ?.takeIf {
                    kotlin.math.abs(it.second.first - longMm) < tolerance &&
                        kotlin.math.abs(it.second.second - shortMm) < tolerance
                }
                ?.first
                ?: "${longMm.toInt()}x${shortMm.toInt()} mm"
        }
    }

    fun pageSize(pageIndex: Int): PageSize = synchronized(lock) {
        renderer.openPage(pageIndex).use { page ->
            PageSize(page.width.toDouble(), page.height.toDouble())
        }
    }

    fun pageSizes(): List<PageSize> = (0 until pageCount).map(::pageSize)

    /**
     * Rasterises exactly [tile] of the page at [dpi], as greyscale.
     *
     * The bitmap is converted one row at a time rather than through a full-page `IntArray`, which
     * halves peak memory - the difference between a 6 MP tile costing 30 MB and costing 54 MB, and on
     * a phone that is the difference between working and being killed.
     */
    fun renderTile(pageIndex: Int, dpi: Double, tile: IBox): GrayImage = synchronized(lock) {
        val scale = (dpi / 72.0).toFloat()
        val bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
        try {
            // PdfRenderer draws only the page content, leaving everything else transparent, so the
            // paper has to be painted first or every margin reads as ink.
            bitmap.eraseColor(Color.WHITE)
            val transform = Matrix().apply {
                postScale(scale, scale)
                postTranslate(-tile.left.toFloat(), -tile.top.toFloat())
            }
            renderer.openPage(pageIndex).use { page ->
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            return toGray(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Whole page at [dpi]. Only safe for a preview: use [renderTile] for detection. */
    fun renderPreview(pageIndex: Int, maxPixels: Int = 2_000_000): Bitmap = synchronized(lock) {
        renderer.openPage(pageIndex).use { page ->
            val aspect = page.width.toDouble() / page.height
            var height = kotlin.math.sqrt(maxPixels / aspect).toInt().coerceAtLeast(1)
            var width = (height * aspect).toInt().coerceAtLeast(1)
            if (width > 8_000) {
                width = 8_000
                height = (width / aspect).toInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {

        /**
         * @throws java.io.IOException when the document cannot be opened - a corrupt file, or one
         *   protected with a password, which [PdfRenderer] refuses outright.
         */
        fun open(context: Context, uri: Uri): PdfPageSource {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw java.io.IOException("cannot open $uri")
            return try {
                PdfPageSource(descriptor, PdfRenderer(descriptor))
            } catch (t: Throwable) {
                runCatching { descriptor.close() }
                throw t
            }
        }

        private fun toGray(bitmap: Bitmap): GrayImage {
            val w = bitmap.width
            val h = bitmap.height
            val out = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                val base = y * w
                for (x in 0 until w) {
                    val p = row[x]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    out[base + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                }
            }
            return GrayImage(w, h, out)
        }
    }
}
