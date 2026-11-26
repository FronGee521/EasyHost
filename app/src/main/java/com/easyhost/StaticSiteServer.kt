package com.easyhost

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Static HTML/CSS/JS server. PHP files are intentionally not executed by this class.
 * A bundled PHP-CGI runtime must be added per Android ABI before PHP execution is enabled.
 */
class StaticSiteServer(
    private val context: Context,
    private val treeUri: Uri,
    private val projectName: String,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        val raw = session.uri.substringBefore('?').removePrefix("/")
        val safe = raw.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        var current = DocumentFile.fromTreeUri(context, treeUri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Folder unavailable")
        current = current.findFile(projectName)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Project not found")
        for (part in safe) {
            current = current.findFile(part)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        if (current.isDirectory) current = current.findFile("index.html")
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "index.html not found")
        if (!current.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        if (current.name?.endsWith(".php", true) == true) {
            return newFixedLengthResponse(
                Response.Status.NOT_IMPLEMENTED, MIME_PLAINTEXT,
                "PHP runtime is not bundled in this build yet."
            )
        }

        val mime = when (current.name?.substringAfterLast('.', "")?.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
        val stream = context.contentResolver.openInputStream(current.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot read file")
        val bytes = stream.use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }
}
