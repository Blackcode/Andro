package com.blackcode.cascoscan.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.Penetration

/**
 * Draws the audit onto the drawing.
 *
 * This is the artefact that makes a report usable on site: a marked-up sheet the foreman can read at
 * arm's length. Colour carries the status and is never the only cue - a missing penetration also gets
 * a heavier ring and a cross - because construction sites are full of people who cannot rely on
 * colour, and of printers that cannot reproduce it.
 */
object OverlayRenderer {

    /** Palette shared with the UI so an on-screen marker and a report marker mean the same thing. */
    object Palette {
        const val MISSING = 0xFFD32F2F.toInt()
        const val PRESENT = 0xFF2E7D32.toInt()
        const val WRONG = 0xFFEF6C00.toInt()
        const val PENDING = 0xFF1565C0.toInt()
        const val REVIEW = 0xFF6A1B9A.toInt()
        const val NOT_APPLICABLE = 0xFF757575.toInt()

        fun forStatus(status: AuditStatus, needsReview: Boolean): Int = when {
            needsReview && status == AuditStatus.PENDING -> REVIEW
            status == AuditStatus.MISSING -> MISSING
            status == AuditStatus.PRESENT -> PRESENT
            status == AuditStatus.WRONG_SIZE || status == AuditStatus.WRONG_POSITION ||
                status == AuditStatus.OBSTRUCTED || status == AuditStatus.NOT_SEALED -> WRONG
            status == AuditStatus.NOT_APPLICABLE -> NOT_APPLICABLE
            else -> PENDING
        }
    }

    /**
     * @param bitmap the rendered sheet, drawn on in place.
     * @param pageWidthPx the width in the coordinate space [penetrations] use - usually much larger
     *   than the bitmap, since the bitmap is a preview.
     */
    fun draw(
        bitmap: Bitmap,
        penetrations: List<Penetration>,
        pageWidthPx: Int,
        acceptThreshold: Double,
        showLabels: Boolean = true,
    ) {
        if (pageWidthPx <= 0) return
        val canvas = Canvas(bitmap)
        val factor = bitmap.width.toDouble() / pageWidthPx
        // Marker weight is tied to the output size, so a thumbnail and an A3 print both read clearly.
        val unit = (bitmap.width / 500f).coerceIn(1.2f, 6f)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = unit * 7f
            isFakeBoldText = true
        }

        for (penetration in penetrations) {
            val needsReview = penetration.needsReview(acceptThreshold)
            val colour = Palette.forStatus(penetration.status, needsReview)
            val cx = (penetration.center.x * factor).toFloat()
            val cy = (penetration.center.y * factor).toFloat()
            // Small symbols would vanish at preview scale, so the marker has a minimum size of its own.
            val radius = ((penetration.box.longSide * factor / 2.0).toFloat()).coerceAtLeast(unit * 3.5f)

            stroke.color = colour
            stroke.strokeWidth = if (penetration.status == AuditStatus.MISSING) unit * 2.2f else unit * 1.4f
            canvas.drawCircle(cx, cy, radius, stroke)

            when (penetration.status) {
                // A cross through the ring: the one marker that must be unmistakable in monochrome.
                AuditStatus.MISSING -> {
                    val arm = radius * 0.72f
                    canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, stroke)
                    canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, stroke)
                }
                AuditStatus.PRESENT -> {
                    // A tick, drawn inside the ring.
                    canvas.drawLine(cx - radius * 0.45f, cy, cx - radius * 0.1f, cy + radius * 0.45f, stroke)
                    canvas.drawLine(cx - radius * 0.1f, cy + radius * 0.45f, cx + radius * 0.5f, cy - radius * 0.4f, stroke)
                }
                AuditStatus.WRONG_SIZE, AuditStatus.WRONG_POSITION,
                AuditStatus.OBSTRUCTED, AuditStatus.NOT_SEALED,
                -> {
                    fill.color = colour
                    canvas.drawRect(
                        RectF(cx - unit * 0.9f, cy - radius * 0.55f, cx + unit * 0.9f, cy + radius * 0.15f),
                        fill,
                    )
                    canvas.drawCircle(cx, cy + radius * 0.42f, unit * 0.9f, fill)
                }
                else -> Unit
            }

            if (showLabels) {
                val caption = buildString {
                    append(penetration.id)
                    penetration.displaySize.takeIf { it != "?" }?.let { append(" ").append(it) }
                }
                val x = cx + radius + unit * 2f
                val y = cy - radius * 0.2f
                // A halo keeps the caption readable over dense line work.
                text.style = Paint.Style.STROKE
                text.strokeWidth = unit * 1.6f
                text.color = Color.WHITE
                canvas.drawText(caption, x, y, text)
                text.style = Paint.Style.FILL
                text.color = colour
                canvas.drawText(caption, x, y, text)
            }
        }
    }
}
