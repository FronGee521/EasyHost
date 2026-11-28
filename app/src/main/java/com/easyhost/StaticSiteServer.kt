package com.easyhost

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import java.net.URLDecoder
import java.text.Normalizer

/**
 * Статический сервер + API EasyDrop (/api/...).
 * PHP не исполняется (501).
 */
class StaticSiteServer(
    private val context: Context,
    private val treeUri: Uri,
    private val projectName: String,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "StaticSiteServer"
    }

    private val api by lazy { EasyDropApi(context) }

    override fun serve(session: IHTTPSession): Response {
        // API передачи файлов
        if (session.uri.startsWith("/api/")) return api.handle(session)

        // Статика: каждый сегмент пути декодируем отдельно и отсекаем traversal.
        val raw = session.uri.substringBefore('?').removePrefix("/")
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad URL")
        }
        val safe = decoded.split('/').filter { it.isNotBlank() }
        if (safe.any { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden path")
        }

        var current = resolveProjectRoot()
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                "Project not found. treeUri=$treeUri, projectName=[$projectName]"
            )

        for (part in safe) {
            current = current.findFile(part)
                ?: current.listFiles().firstOrNull { it.name.equals(part, ignoreCase = true) }
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $part"
                )
        }

        if (current.isDirectory) {
            current = current.findFile("index.html")
                ?: current.findFile("index.htm")
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "index.html or index.htm not found"
                )
        }
        if (!current.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        if (current.name?.endsWith(".php", true) == true) {
            return newFixedLengthResponse(
                Response.Status.NOT_IMPLEMENTED, MIME_PLAINTEXT,
                "PHP runtime is not bundled in this build yet."
            )
        }

        val mime = mimeFor(current.name)
        val stream = try {
            context.contentResolver.openInputStream(current.uri)
        } catch (e: Exception) {
            Log.w(TAG, "openInputStream failed for ${current.uri}", e)
            null
        } ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cannot read file"
        )

        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun resolveProjectRoot(): DocumentFile? {
        ensurePersistablePermission()

        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            Log.w(TAG, "fromTreeUri returned null for $treeUri")
            return null
        }

        val wanted = normalize(projectName)

        // Выбрана сама папка проекта или имя пустое.
        if (wanted.isEmpty() || normalize(root.name ?: "").equals(wanted, ignoreCase = true)) {
            return root
        }

        root.findFile(projectName)?.let { return it }

        // Fallback для SAF-провайдеров с кривым findFile.
        return root.listFiles().firstOrNull {
            it.isDirectory && normalize(it.name ?: "").equals(wanted, ignoreCase = true)
        }
    }

    private fun ensurePersistablePermission() {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            Log.d(TAG, "takePersistableUriPermission failed: ${e.message}")
        }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).trim()

    private fun mimeFor(name: String?): String =
        when (name?.substringAfterLast('.', "")?.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wasm" -> "application/wasm"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
}
