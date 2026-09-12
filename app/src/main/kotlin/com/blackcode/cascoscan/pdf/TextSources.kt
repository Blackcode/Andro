package com.blackcode.cascoscan.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.blackcode.cascoscan.detect.IBox
import com.blackcode.cascoscan.detect.TextBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Where the "Ø110" next to a symbol comes from.
 *
 * Two sources, in order of preference:
 *  - **the PDF's own text layer**, exact and free, present in every CAD export;
 *  - **OCR**, for a scan or a photograph of a paper drawing.
 *
 * Both are optional. With no text at all, detection still runs on geometry - it just loses the label
 * evidence, the ability to recover the scale from the drawing, and the strongest way of telling a
 * symbol from a character. That is a degradation, not a failure, and the UI reports it as such.
 */
object TextSources {

    private const val TAG = "CascoScan/Text"

    /**
     * Words and their boxes from the PDF text layer, in the pixel space of a page rendered at [dpi].
     *
     * PDF text coordinates are in points with the origin at the bottom-left of the sheet; PDFBox's
     * "DirAdj" accessors have already flipped that to a top-left origin and applied the text's own
     * direction, which is exactly the space the renderer produces.
     *
     * @return null when the document has no usable text layer, so the caller can fall back to OCR
     *   rather than treating an empty result as "this sheet is unannotated".
     */
    fun fromPdf(context: Context, uri: Uri, pageIndex: Int, dpi: Double): List<TextBox>? {
        return try {
            PDFBoxResourceLoader.init(context)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { document ->
                    if (pageIndex >= document.numberOfPages) return null
                    val collector = BoxCollector(dpi / 72.0)
                    collector.sortByPosition = true
                    collector.startPage = pageIndex + 1
                    collector.endPage = pageIndex + 1
                    collector.getText(document)
                    collector.boxes.takeIf { it.isNotEmpty() }
                }
            }
        } catch (t: Throwable) {
            // A malformed or encrypted document must not take the import down with it.
            Log.w(TAG, "no PDF text layer for page $pageIndex: ${t.message}")
            null
        }
    }

    /**
     * OCR over an already-rendered page. Runs the recogniser on the bitmap the caller supplies, so
     * the boxes come back in that bitmap's pixel space - pass the same raster detection will use, or
     * scale the result.
     */
    suspend fun fromOcr(bitmap: Bitmap): List<TextBox> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val out = ArrayList<TextBox>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    // Elements are words: the right granularity, since a label is one or two words and
                    // a whole line's box would swallow the symbol next to it.
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        out += TextBox(element.text, IBox(box.left, box.top, box.right, box.bottom))
                    }
                    if (line.elements.isEmpty()) {
                        val box = line.boundingBox ?: continue
                        out += TextBox(line.text, IBox(box.left, box.top, box.right, box.bottom))
                    }
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "OCR failed: ${t.message}")
            emptyList()
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) }
            addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    /**
     * Collects one box per text run. PDFBox hands [writeString] the glyph positions of a run, and the
     * union of their extents is the box we want.
     */
    private class BoxCollector(private val pointsToPixels: Double) : PDFTextStripper() {

        val boxes = ArrayList<TextBox>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && textPositions.isNotEmpty()) {
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                for (position in textPositions) {
                    val x = position.xDirAdj
                    val y = position.yDirAdj
                    val w = position.widthDirAdj
                    val h = position.heightDir
                    if (x < left) left = x
                    if (x + w > right) right = x + w
                    // yDirAdj is the glyph's bottom, measured downwards from the top of the sheet.
                    if (y - h < top) top = y - h
                    if (y > bottom) bottom = y
                }
                if (left <= right && top <= bottom) {
                    boxes += TextBox(
                        trimmed,
                        IBox(
                            (left * pointsToPixels).toInt(),
                            (top * pointsToPixels).toInt(),
                            (right * pointsToPixels).toInt().coerceAtLeast((left * pointsToPixels).toInt()),
                            (bottom * pointsToPixels).toInt().coerceAtLeast((top * pointsToPixels).toInt()),
                        ),
                    )
                }
            }
            super.writeString(text, textPositions)
        }
    }
}
