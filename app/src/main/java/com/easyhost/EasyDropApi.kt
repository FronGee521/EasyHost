package com.easyhost

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

class EasyDropApi(context: Context) {
    private val dir = (context.getExternalFilesDir("EasyDrop")
        ?: File(context.filesDir, "EasyDrop")).also { it.mkdirs() }

    fun handle(s: NanoHTTPD.IHTTPSession): Response {
        val p = s.uri.removePrefix("/api/")
        return try {
            when {
                p == "files" && s.method == Method.GET -> list()
                p == "upload" && s.method == Method.PUT -> upload(s)
                p.startsWith("files/") && s.method == Method.GET -> download(p.removePrefix("files/"))
                p.startsWith("files/") && s.method == Method.DELETE -> delete(p.removePrefix("files/"))
                else -> text(Status.NOT_FOUND, "Not found")
            }
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, e.message ?: "Error")
        }
    }

    private fun list(): Response {
        val arr = JSONArray()
        dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach {
                arr.put(JSONObject().put("name", it.name)
                    .put("size", it.length()).put("time", it.lastModified()))
            }
        return json(arr.toString())
    }

    private fun upload(s: NanoHTTPD.IHTTPSession): Response {
        val files = HashMap<String, String>()
        s.parseBody(files)                       // PUT-тело → временный файл "content"
        val tmp = files["content"]?.let(::File)
            ?: return text(Status.BAD_REQUEST, "Empty body")
        val name = unique(clean(s.parms["name"] ?: "file"))
        val target = File(dir, name)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
        return json(JSONObject().put("name", name).toString())
    }

    private fun download(raw: String): Response {
        val f = File(dir, clean(raw))
        if (!f.isFile) return text(Status.NOT_FOUND, "Not found")
        val r = NanoHTTPD.newFixedLengthResponse(
            Status.OK, "application/octet-stream", FileInputStream(f), f.length())
        val enc = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$enc")
        return r
    }

    private fun delete(raw: String): Response {
        File(dir, clean(raw)).takeIf { it.isFile }?.delete()
        return json("{\"ok\":true}")
    }

    private fun clean(n: String) =
        n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim().trim('.').take(120).ifEmpty { "file" }

    private fun unique(n: String): String {
        if (!File(dir, n).exists()) return n
        val base = n.substringBeforeLast('.', n)
        val ext = n.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val c = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (!File(dir, c).exists()) return c
            i++
        }
    }

    private fun json(body: String) =
        NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", body)
            .also { it.addHeader("Cache-Control", "no-store") }

    private fun text(st: Status, msg: String) =
        NanoHTTPD.newFixedLengthResponse(st, NanoHTTPD.MIME_PLAINTEXT, msg)
}
