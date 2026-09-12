package com.blackcode.cascoscan.report

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the report out and hands it to whatever the auditor wants to send it with.
 *
 * Everything goes through a [FileProvider]: sharing a `file://` uri has been an error since Android
 * 7, and reports leave this app constantly - by mail, to a document system, into a group chat.
 */
object Exporter {

    private const val AUTHORITY_SUFFIX = ".files"

    fun writeReport(context: Context, projectName: String, html: String): Uri =
        write(context, "reports", "${slug(projectName)}-penetration-audit-${stamp()}.html", html)

    fun writeCsv(context: Context, projectName: String, csv: String): Uri =
        write(context, "reports", "${slug(projectName)}-penetrations-${stamp()}.csv", csv)

    /** Destination for a site photograph, to be passed to `ACTION_IMAGE_CAPTURE`. */
    fun newPhotoTarget(context: Context, reference: String): Pair<File, Uri> {
        val directory = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(directory, "${slug(reference)}-${System.currentTimeMillis()}.jpg")
        return file to uriFor(context, file)
    }

    fun shareIntent(uris: List<Uri>, subject: String): Intent {
        val mime = when {
            uris.size > 1 -> "*/*"
            uris.firstOrNull()?.toString()?.endsWith(".csv") == true -> "text/csv"
            else -> "text/html"
        }
        val intent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        }
        return intent.apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun viewIntent(context: Context, uri: Uri, mime: String = "text/html"): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    private fun write(context: Context, folder: String, fileName: String, content: String): Uri {
        val directory = File(context.filesDir, folder).apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeText(content)
        return uriFor(context, file)
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun slug(value: String): String = value
        .lowercase(Locale.US)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .take(48)
        .ifEmpty { "project" }
}
