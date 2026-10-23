package org.kivis.downfree

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.Base64

internal class LocalWebServer(
    private val context: Context,
    private val engine: DownloadEngine,
) : NanoHTTPD("127.0.0.1", 5000) {

    private fun loadAsset(name: String): String = runCatching {
        context.assets.open("web/$name").bufferedReader().use { it.readText() }
    }.getOrDefault("")

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/" -> {
                val html = loadAsset("home.html").replace("{DEVELOPER_CONTACT}", "https://t.me/Hiyabo")
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
            path == "/settings" -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Ruta /settings no implementada en la versión legacy")
            path == "/generate" && session.method == Method.GET -> generatePage()
            path == "/generate" && session.method == Method.POST -> generatePost(session)
            path.startsWith("/download/") -> proxy(session, path.substringAfter("/download/"), false)
            path.startsWith("/stream/") -> proxy(session, path.substringAfter("/stream/"), true)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun proxy(session: IHTTPSession, token: String, stream: Boolean): Response {
        val payload = runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
            JSONObject(normalizeLegacyObject(decoded))
        }.getOrElse { return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Token inválido o corrupto") }
        val filename = payload.optString("fn", "download.bin")
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (stream && ext !in setOf("mp4", "webm", "ogg", "mkv")) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No es un archivo de video soportado")
        if (stream && "raw" !in session.parameters) {
            val mime = when (ext) { "mp4" -> "video/mp4"; "webm" -> "video/webm"; "ogg" -> "video/ogg"; else -> "video/mp4" }
            val html = loadAsset("stream.html")
                .replace("{fn}", filename)
                .replace("{token}", token)
                .replace("{content_type}", mime)
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        val size = payload.optLong("fs")
        val rangeHeader = session.headers["range"]
        val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = range?.groupValues?.get(2)?.takeIf(String::isNotBlank)?.toLongOrNull() ?: (size - 1).takeIf { size > 0 }
        if (range != null && (start >= size || (end != null && end < start))) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
        if (rangeHeader != null && range == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Range inválido")
        val mime = if (stream) when (ext) { "mp4" -> "video/mp4"; "webm" -> "video/webm"; "ogg" -> "video/ogg"; else -> "video/mp4" } else "application/octet-stream"
        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val input = java.io.PipedInputStream(64 * 1024)
        val output = java.io.PipedOutputStream(input)
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { engine.streamPayload(payload.toString(), start.takeIf { range != null }, end, output) } }
                .onFailure { it.printStackTrace() }
                .also { runCatching { output.close() } }
        }.apply { name = "downfree-http-stream"; isDaemon = true; start() }
        val response = if (end != null && size > 0)
            newFixedLengthResponse(status, mime, input, (end - start + 1).coerceAtLeast(0))
        else newChunkedResponse(status, mime, input)
        response.addHeader("Accept-Ranges", "bytes")
        if (!stream) response.addHeader("Content-Disposition", "attachment; filename=\"${filename.replace("\"", "")}\"")
        if (rangeHeader != null && end != null && size > 0) response.addHeader("Content-Range", "bytes $start-$end/$size")
        return response
    }

    private fun generatePage(error: String? = null, download: String? = null, stream: String? = null): Response {
        val top = loadAsset("generate_top.html")
        val bottom = loadAsset("generate_bottom.html")
        val middle = buildString {
            if (error != null) {
                append("""
                    <div class="error-message">
                        <strong>❌ Error:</strong> $error
                    </div>
                """.trimIndent())
            }
            if (download != null) {
                append("""
                    <div class="success-section">
                        <h2>📥 Enlace de Descarga</h2>
                        <div class="link-container">
                            <a href='$download' target="_blank">$download</a>
                        </div>
                    </div>
                """.trimIndent())
            }
            if (stream != null) {
                append("""
                    <div class="success-section">
                        <h2>🎬 Enlace de Streaming</h2>
                        <div class="link-container">
                            <a href='$stream' target="_blank">$stream</a>
                        </div>
                    </div>
                """.trimIndent())
            }
        }
        val full = top + middle + bottom
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", full)
    }

    private fun generatePost(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        return try {
            session.parseBody(body)
            val raw = (session.parameters["json_input"]?.firstOrNull() ?: body["postData"].orEmpty()).trim()
            val normalized = normalizeLegacyObject(raw)
            val json = JSONObject(normalized)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
            val base = "http://127.0.0.1:5000"
            val download = "$base/download/$token"
            val filename = json.optString("fn").lowercase()
            val stream = if (listOf(".mp4", ".webm", ".ogg", ".mkv").any(filename::endsWith)) "$base/stream/$token" else null
            generatePage(download = download, stream = stream)
        } catch (error: Throwable) {
            generatePage(error = error.message ?: "JSON inválido")
        }
    }
}
