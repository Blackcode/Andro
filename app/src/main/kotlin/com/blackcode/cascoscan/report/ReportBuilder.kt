package com.blackcode.cascoscan.report

import android.graphics.Bitmap
import android.util.Base64
import com.blackcode.cascoscan.detect.Audit
import com.blackcode.cascoscan.detect.AuditStatus
import com.blackcode.cascoscan.detect.Penetration
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the audit deliverable.
 *
 * Two formats, for two readers. The HTML report is one self-contained file with the marked-up sheets
 * embedded, so it can be mailed from site and opened by anyone without this app. The CSV is for the
 * office: a row per penetration, ready to be pasted into whatever the project actually tracks its
 * snags in.
 *
 * Both state what the machine was unsure about. A QC report that hides its own uncertainty is worse
 * than no report, because it will be signed.
 */
class ReportBuilder(private val acceptThreshold: Double) {

    data class SheetSection(
        val sheetName: String,
        val pageIndex: Int,
        val sheetFormat: String,
        val scaleNote: String,
        val diagnostics: String?,
        val penetrations: List<Penetration>,
        /** Marked-up preview of the sheet, already drawn on by [OverlayRenderer]. */
        val overlay: Bitmap?,
    )

    data class Meta(
        val projectName: String,
        val auditor: String?,
        val documentName: String,
        val generatedAt: Long = System.currentTimeMillis(),
    )

    fun html(meta: Meta, sections: List<SheetSection>): String {
        val all = sections.flatMap { it.penetrations }
        val summary = Audit.summarise(all, acceptThreshold)
        val findings = Audit.findings(all)

        return buildString {
            append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(escape(meta.projectName)).append(" - penetration audit</title>")
            append("<style>").append(CSS).append("</style></head><body>")

            append("<header><h1>Penetration audit</h1>")
            append("<p class=\"meta\">")
            append(escape(meta.projectName))
            meta.auditor?.let { append(" &middot; ").append(escape(it)) }
            append(" &middot; ").append(escape(meta.documentName))
            append(" &middot; ").append(escape(timestamp(meta.generatedAt)))
            append("</p></header>")

            append("<section class=\"cards\">")
            card("Required", summary.total.toString(), "penetrations on the drawings")
            card("Missing", summary.missing.toString(), "not found in the building", accent = summary.missing > 0)
            card("Other findings", (summary.findings - summary.missing).toString(), "wrong size, position or seal")
            card("Inspected", "${summary.inspected} / ${summary.total}", "%.0f%% complete".format(summary.progress * 100))
            if (summary.needingReview > 0) {
                card("Awaiting review", summary.needingReview.toString(), "machine was unsure", accent = true)
            }
            append("</section>")

            if (summary.needingReview > 0) {
                append("<p class=\"warn\">")
                append(summary.needingReview)
                append(" detection(s) scored below the confidence threshold and have not been checked by a person. ")
                append("They are listed as <em>awaiting review</em> and are not evidence either way.</p>")
            }

            if (findings.isNotEmpty()) {
                append("<h2>Findings</h2>")
                append("<table><thead><tr><th>Ref</th><th>Sheet</th><th>Status</th><th>Size</th>")
                append("<th>Position (px)</th><th>Note</th></tr></thead><tbody>")
                for (finding in findings) {
                    append("<tr class=\"").append(statusClass(finding.status)).append("\">")
                    cell(finding.id)
                    cell(sections.firstOrNull { it.pageIndex == finding.pageIndex }?.sheetName ?: "Sheet ${finding.pageIndex + 1}")
                    cell(label(finding.status))
                    cell(finding.displaySize)
                    cell("${finding.center.x.toInt()}, ${finding.center.y.toInt()}")
                    cell(finding.note ?: "")
                    append("</tr>")
                }
                append("</tbody></table>")
            } else {
                append("<p class=\"ok\">No findings recorded.</p>")
            }

            for (section in sections) {
                append("<h2>").append(escape(section.sheetName)).append("</h2>")
                append("<p class=\"meta\">").append(escape(section.sheetFormat))
                append(" &middot; ").append(escape(section.scaleNote))
                section.diagnostics?.let { append(" &middot; ").append(escape(it)) }
                append("</p>")

                section.overlay?.let { bitmap ->
                    append("<figure><img alt=\"Marked-up ")
                    append(escape(section.sheetName))
                    append("\" src=\"data:image/png;base64,")
                    append(encodePng(bitmap))
                    append("\"><figcaption>Red crossed rings are missing; green ticks are confirmed; ")
                    append("orange are wrong in size or position; blue are not yet inspected.</figcaption></figure>")
                }

                if (section.penetrations.isEmpty()) {
                    append("<p class=\"meta\">No penetrations on this sheet.</p>")
                    continue
                }
                append("<table><thead><tr><th>Ref</th><th>Form</th><th>Size</th><th>Label</th>")
                append("<th>Status</th><th>Confidence</th><th>Note</th></tr></thead><tbody>")
                for (p in section.penetrations.sortedBy { it.id }) {
                    append("<tr class=\"").append(statusClass(p.status)).append("\">")
                    cell(p.id)
                    cell(form(p))
                    cell(p.displaySize)
                    cell(p.label?.raw ?: "")
                    cell(if (p.needsReview(acceptThreshold)) "awaiting review" else label(p.status))
                    cell("%.0f%%".format(p.confidence * 100))
                    cell(p.note ?: "")
                    append("</tr>")
                }
                append("</tbody></table>")
            }

            append("<footer><p>Produced by CascoScan. Machine detection assists the audit; ")
            append("it does not replace the inspector's judgement.</p></footer>")
            append("</body></html>")
        }
    }

    /** One row per penetration. Semicolon-separated, which is what European spreadsheets expect. */
    fun csv(meta: Meta, sections: List<SheetSection>): String = buildString {
        append("ref;sheet;page;form;measured_width_mm;measured_height_mm;label;declared_mm;")
        append("status;confidence;origin;required_by_page;centre_x_px;centre_y_px;note;photos\n")
        for (section in sections) {
            for (p in section.penetrations.sortedBy { it.id }) {
                field(p.id)
                field(section.sheetName)
                field((p.pageIndex + 1).toString())
                field(form(p))
                field(p.sizeMm?.width?.let { "%.0f".format(it) } ?: "")
                field(p.sizeMm?.height?.let { "%.0f".format(it) } ?: "")
                field(p.label?.raw ?: "")
                field(p.label?.declaredSizeMm?.let { "%.0f".format(it) } ?: "")
                field(if (p.needsReview(acceptThreshold)) "AWAITING_REVIEW" else p.status.name)
                field("%.2f".format(p.confidence))
                field(p.origin.name)
                field(p.requiredByPageIndex?.let { (it + 1).toString() } ?: "")
                field("%.0f".format(p.center.x))
                field("%.0f".format(p.center.y))
                field(p.note ?: "")
                append(csvEscape(p.photoUris.joinToString(" ")))
                append('\n')
            }
        }
    }

    private fun StringBuilder.field(value: String) {
        append(csvEscape(value)).append(';')
    }

    private fun csvEscape(value: String): String {
        val cleaned = value.replace('\n', ' ').replace('\r', ' ')
        return if (cleaned.contains(';') || cleaned.contains('"')) {
            "\"" + cleaned.replace("\"", "\"\"") + "\""
        } else {
            cleaned
        }
    }

    private fun StringBuilder.cell(value: String) {
        append("<td>").append(escape(value)).append("</td>")
    }

    private fun StringBuilder.card(title: String, value: String, note: String, accent: Boolean = false) {
        append("<div class=\"card").append(if (accent) " accent" else "").append("\">")
        append("<div class=\"card-value\">").append(escape(value)).append("</div>")
        append("<div class=\"card-title\">").append(escape(title)).append("</div>")
        append("<div class=\"card-note\">").append(escape(note)).append("</div></div>")
    }

    private fun form(p: Penetration): String = p.kind.name.lowercase().replace('_', ' ')

    private fun label(status: AuditStatus): String = when (status) {
        AuditStatus.PENDING -> "not inspected"
        AuditStatus.PRESENT -> "present"
        AuditStatus.MISSING -> "MISSING"
        AuditStatus.WRONG_SIZE -> "wrong size"
        AuditStatus.WRONG_POSITION -> "wrong position"
        AuditStatus.OBSTRUCTED -> "obstructed"
        AuditStatus.NOT_SEALED -> "not sealed"
        AuditStatus.NOT_APPLICABLE -> "not applicable"
    }

    private fun statusClass(status: AuditStatus): String = when {
        status == AuditStatus.MISSING -> "missing"
        status.isFinding -> "wrong"
        status == AuditStatus.PRESENT -> "present"
        else -> ""
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    private fun encodePng(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private companion object {
        const val CSS = """
:root { color-scheme: light dark; --ink:#16181d; --paper:#fff; --line:#d8dce3; --muted:#5b6472;
  --missing:#d32f2f; --wrong:#ef6c00; --present:#2e7d32; }
@media (prefers-color-scheme: dark) { :root { --ink:#e9ecf1; --paper:#14161a; --line:#2c313a; --muted:#9aa3b2; } }
* { box-sizing: border-box; }
body { margin:0; padding:24px 16px 48px; background:var(--paper); color:var(--ink);
  font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; max-width:1100px; margin-inline:auto; }
h1 { font-size:1.6rem; margin:0 0 4px; }
h2 { font-size:1.15rem; margin:32px 0 8px; padding-bottom:6px; border-bottom:1px solid var(--line); }
.meta { color:var(--muted); margin:4px 0 16px; font-size:.9rem; }
.cards { display:flex; flex-wrap:wrap; gap:12px; margin:16px 0 8px; }
.card { flex:1 1 150px; border:1px solid var(--line); border-radius:10px; padding:12px 14px; }
.card.accent { border-color:var(--missing); }
.card-value { font-size:1.7rem; font-weight:650; letter-spacing:-.02em; }
.card-title { font-weight:600; font-size:.85rem; }
.card-note { color:var(--muted); font-size:.8rem; }
.warn { border-left:3px solid var(--wrong); padding:8px 12px; background:rgba(239,108,0,.08); border-radius:0 6px 6px 0; }
.ok { color:var(--present); font-weight:600; }
table { width:100%; border-collapse:collapse; margin:8px 0 16px; font-size:.9rem; display:block; overflow-x:auto; }
th, td { text-align:left; padding:7px 9px; border-bottom:1px solid var(--line); white-space:nowrap; }
th { font-size:.78rem; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }
td:last-child, th:last-child { white-space:normal; min-width:180px; }
tr.missing td:nth-child(3) { color:var(--missing); font-weight:700; }
tr.wrong td:nth-child(3) { color:var(--wrong); font-weight:600; }
tr.present td:nth-child(3) { color:var(--present); }
figure { margin:12px 0 20px; }
img { width:100%; height:auto; border:1px solid var(--line); border-radius:8px; background:#fff; }
figcaption { color:var(--muted); font-size:.82rem; margin-top:6px; }
footer { margin-top:40px; color:var(--muted); font-size:.82rem; border-top:1px solid var(--line); padding-top:12px; }
"""
    }
}
