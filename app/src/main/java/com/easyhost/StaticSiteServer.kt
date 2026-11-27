package com.easyhost

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD

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
        // Decode each path segment separately and reject traversal attempts.
        val raw = session.uri.substringBefore('?').removePrefix("/")
        val decoded = try { java.net.URLDecoder.decode(raw, "UTF-8") }
            catch (_: Exception) { return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad URL") }
        val safe = decoded.split('/').filter { it.isNotBlank() }
        if (safe.any { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden path")
        }
        var current = DocumentFile.fromTreeUri(context, treeUri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Folder unavailable")
        current = current.findFile(projectName)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Project not found")
        for (part in safe) {
            current = current.findFile(part)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        if (current.isDirectory) current = current.findFile("index.html")
            ?: current.findFile("index.htm")
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "index.html or index.htm not found")
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
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        val stream = context.contentResolver.openInputStream(current.uri)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot read file")
        // Stream files instead of loading the whole asset into RAM.
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }
}
