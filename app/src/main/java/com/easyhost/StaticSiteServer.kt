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
 * Static HTML/CSS/JS server. PHP files are intentionally not executed by this class.
 * A bundled PHP-CGI runtime must be added per Android ABI before PHP execution is enabled.
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

    override fun serve(session: IHTTPSession): Response {
        // Decode each path segment separately and reject traversal attempts.
        val raw = session.uri.substringBefore('?').removePrefix("/")
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad URL"
            )
        }
        val safe = decoded.split('/').filter { it.isNotBlank() }
        if (safe.any { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden path"
            )
        }

        var current = resolveProjectRoot()
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Project not found. treeUri=$treeUri, projectName=[$projectName]"
            )

        for (part in safe) {
            current = current.findFile(part)
                ?: current.listFiles().firstOrNull {
                    it.name.equals(part, ignoreCase = true)
                }
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $part"
                )
        }

        if (current.isDirectory) {
            current = current.findFile("index.html")
                ?: current.findFile("index.htm")
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "index.html or index.htm not found"
                )
        }
        if (!current.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }
        if (current.name?.endsWith(".php", true) == true) {
            return newFixedLengthResponse(
                Response.Status.NOT_IMPLEMENTED,
                MIME_PLAINTEXT,
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

        // Stream files instead of loading the whole asset into RAM.
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    /**
     * Резолвит корень проекта устойчиво:
     *  1) если treeUri уже указывает на папку проекта — возвращаем её;
     *  2) пробуем прямой findFile;
     *  3) fallback: перебор с регистронезависимым сравнением и нормализацией Unicode.
     * Плюс один раз пытаемся взять persistable permission на всякий случай.
     */
    private fun resolveProjectRoot(): DocumentFile? {
        ensurePersistablePermission()

        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            Log.w(TAG, "fromTreeUri returned null for $treeUri")
            return null
        }

        val wanted = normalize(projectName)
        Log.d(TAG, "treeUri=$treeUri, projectName=[$projectName], root.name=[${root.name}]")

        // Случай: пользователь выбрал саму папку проекта, либо projectName пустой.
        if (wanted.isEmpty() || normalize(root.name ?: "").equals(wanted, ignoreCase = true)) {
            return root
        }

        // Прямой поиск.
        root.findFile(projectName)?.let { return it }

        // Fallback: некоторые SAF-провайдеры (GDrive, MTP, отдельные прошивки)
        // некорректно реализуют findFile — идём по списку вручную.
        val children = root.listFiles()
        for (child in children) {
            Log.d(TAG, "child.name=[${child.name}] dir=${child.isDirectory}")
        }
        return children.firstOrNull {
            it.isDirectory && normalize(it.name ?: "").equals(wanted, ignoreCase = true)
        }
    }

    private fun ensurePersistablePermission() {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // уже есть или провайдер не поддерживает — не критично
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
            "json" -> "application/json; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain; charset=utf-8"
            "map" -> "application/json; charset=utf-8"
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
