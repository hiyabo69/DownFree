package org.kivis.downfree

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

internal class LocalWebServer(private val engine: DownloadEngine) : NanoHTTPD("127.0.0.1", 5000) {
    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/" -> html("Down Free VPN - Descarga Libre y Segura", """
                <header><h1>Down Free VPN</h1><p>Acceso libre a descargas sin consumo de datos a través de nuestro bot especializado.</p></header>
                <section><h2>Características principales</h2><article><h3>Descargas seguras</h3><p>Descarga ficheros con el gestor Down Free.</p></article><article><h3>Streaming online</h3><p>Reproduce vídeos compatibles mediante streaming con rangos HTTP.</p></article><article><h3>Temas personalizables</h3><p>Configura tu experiencia en la aplicación.</p></article></section>
                <section><h2>Soporte técnico</h2><p>¿Necesitas ayuda o quieres contratar el servicio?</p><a href='https://t.me/Hiyabo'>Contactar soporte</a></section>
                <nav><a href='/settings'>Configuración</a> · <a href='/generate'>Generar Enlaces</a></nav>
            """.trimIndent())
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
        if (stream && "raw" !in session.parameters) return html("Down Free Stream - $filename", "<h1>${escape(filename)}</h1><video controls autoplay loop preload='metadata' src='/stream/$token?raw=1'></video><nav><a href='/'>Inicio</a> · <a href='/generate'>Generar Enlaces</a> · <a href='/settings'>Configuración</a></nav>")
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
        if (!stream) response.addHeader("Content-Disposition", "attachment; filename=\"${filename.replace("\"", "") }\"")
        if (rangeHeader != null && end != null && size > 0) response.addHeader("Content-Range", "bytes $start-$end/$size")
        return response
    }

    private fun generatePage(error: String? = null, download: String? = null, stream: String? = null) = html("Generar Enlaces - Down Free", buildString {
        append("<h1>Generador de Enlaces</h1><form method='post'><label for='json_input'>Pega tu código JSON:</label><textarea name='json_input' id='json_input' rows='14' style='width:100%'></textarea><button>Generar Enlaces</button></form>")
        error?.let { append("<p style='color:#b00'>Error: ${escape(it)}</p>") }
        download?.let { append("<p>Enlace de Descarga: <a href='${escape(it)}'>${escape(it)}</a></p>") }
        stream?.let { append("<p>Enlace de Streaming: <a href='${escape(it)}'>${escape(it)}</a></p>") }
        append("<nav><a href='/settings'>Configuración</a> · <a href='/'>Inicio</a></nav>")
    })

    private fun generatePost(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        return try {
            session.parseBody(body)
            val raw = (session.parameters["json_input"]?.firstOrNull() ?: body["postData"].orEmpty()).trim().replace('’', '\'')
            val json = JSONObject(normalizeLegacyObject(raw))
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
            val base = "http://127.0.0.1:5000"
            val download = "$base/download/$token"
            val filename = json.optString("fn").lowercase()
            val stream = if (listOf(".mp4", ".webm", ".ogg", ".mkv").any(filename::endsWith)) "$base/stream/$token" else null
            generatePage(download = download, stream = stream)
        } catch (error: Throwable) { generatePage(error = error.message ?: "JSON inválido") }
    }

    private fun html(title: String, body: String): Response {
        val document = "<!doctype html><html lang='es'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>${escape(title)}</title><style>body{font:16px system-ui;max-width:900px;margin:2rem auto;padding:1rem;background:#f2f4f8;color:#172033}nav,form{margin-top:2rem}video{width:100%}textarea{font:14px monospace}</style></head><body>$body</body></html>"
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", document)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    companion object { private const val USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0" }
}
