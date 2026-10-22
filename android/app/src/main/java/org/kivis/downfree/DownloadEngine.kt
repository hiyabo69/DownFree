package org.kivis.downfree

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class DownloadEngine(
    private val repository: DownloadRepository,
    private val onNotification: (DownloadItem) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val itemsState = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = itemsState.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pauseGates = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val activeCount = java.util.concurrent.atomic.AtomicInteger()
    private val slots = Any()
    @Volatile private var maxActive = 1
    @Volatile private var selectedPath: String? = null
    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder().cookieJar(cookieJar)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "s3.todus.cu") listOf(InetAddress.getByName("152.207.206.86")) else Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()

    suspend fun restore() {
        val config = repository.loadConfig()
        selectedPath = config.optString("selected_path").takeIf(String::isNotBlank)
        val status = repository.loadStatuses()
        itemsState.value = repository.loadDownloads().map { item ->
            when (status.optJSONObject(item.fileName)?.optString("status")) {
                "completed" -> item.copy(state = DownloadState.COMPLETED, downloadedBytes = item.totalBytes)
                "error" -> item.copy(state = DownloadState.ERROR, error = status.optJSONObject(item.fileName)?.optString("error"))
                else -> item.copy(state = DownloadState.QUEUED)
            }
        }
        itemsState.value.filter { it.state == DownloadState.QUEUED }.forEach { enqueue(it.urlData, allowExisting = true) }
    }

    fun setSelectedPath(path: String?) { selectedPath = path }
    fun setMaxActive(value: Int) { maxActive = value.coerceIn(1, 3) }

    fun enqueue(raw: String, allowExisting: Boolean = false) {
        val request = runCatching { DownloadRequest.parse(raw) }.getOrElse { return }
        if (!allowExisting && itemsState.value.any { it.fileName == request.fileName && it.state !in setOf(DownloadState.ERROR, DownloadState.CANCELLED) }) return
        val item = itemsState.value.firstOrNull { it.fileName == request.fileName }?.copy(urlData = raw, state = DownloadState.QUEUED, error = null)
            ?: DownloadItem(request.fileName, raw, request.size)
        itemsState.value = itemsState.value.filterNot { it.fileName == item.fileName } + item
        persist()
        val gate = pauseGates.getOrPut(item.fileName) { MutableStateFlow(true) }
        jobs[item.fileName] = scope.launch {
            var acquired = false
            try {
                while (!acquired) {
                    acquired = synchronized(slots) {
                        if (activeCount.get() < maxActive) { activeCount.incrementAndGet(); true } else false
                    }
                    if (!acquired) delay(200)
                }
                update(item.fileName) { it.copy(state = DownloadState.DOWNLOADING, error = null) }
                perform(request, gate)
                update(item.fileName) { it.copy(state = DownloadState.COMPLETED, downloadedBytes = it.totalBytes.takeIf { total -> total > 0 } ?: it.downloadedBytes, speedBytesPerSecond = 0.0) }
                repository.saveStatus(item.fileName, DownloadState.COMPLETED)
            } catch (cancelled: CancellationException) {
                update(item.fileName) { it.copy(state = DownloadState.CANCELLED, speedBytesPerSecond = 0.0) }
                repository.saveStatus(item.fileName, DownloadState.CANCELLED)
                throw cancelled
            } catch (failure: Throwable) {
                update(item.fileName) { it.copy(state = DownloadState.ERROR, error = failure.message ?: "Error de descarga", speedBytesPerSecond = 0.0) }
                repository.saveStatus(item.fileName, DownloadState.ERROR, failure.message)
            } finally {
                if (acquired) synchronized(slots) { activeCount.decrementAndGet() }
                jobs.remove(item.fileName)
                pauseGates.remove(item.fileName)
                persist()
            }
        }
    }

    private suspend fun perform(request: DownloadRequest, gate: MutableStateFlow<Boolean>) {
        val urls = expand(request)
        authenticate(request)
        val safeName = File(request.fileName).name
        val partsDir = repository.partsDir(selectedPath).apply { mkdirs() }
        val finalDir = repository.downloadsDir(selectedPath).apply { mkdirs() }
        val destination = File(finalDir, safeName)
        if (request.method == "todus") {
            val partial = File(partsDir, safeName)
            downloadTodus(request, urls.first(), partial, gate)
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "No se pudo mover el archivo descargado" }
            val publishedUri = repository.publishCompletedFile(destination, selectedPath)
            update(request.fileName) { it.copy(publicUri = publishedUri.toString()) }
            destination.delete()
            return
        }

        val partSize = request.partSizeMb.coerceAtLeast(1).toLong() * 1024 * 1024
        val partFiles = urls.indices.map { File(partsDir, "$safeName.part$it") }
        var lastBytes = 0L
        var lastTick = System.nanoTime()
        urls.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val part = partFiles[index]
            val expected = if (index < urls.lastIndex) partSize else (request.size - partSize * index).coerceAtLeast(0)
            var attempt = 0
            while (true) {
                try {
                    transfer(request, chunkUrl(request, chunk), part, null, expected, gate) { bytes ->
                        val downloaded = partFiles.take(index).sumOf(File::length) + bytes
                        val now = System.nanoTime()
                        val elapsed = (now - lastTick) / 1_000_000_000.0
                        val speed = if (elapsed > .1) (downloaded - lastBytes).coerceAtLeast(0) / elapsed else 0.0
                        if (elapsed > .5) { lastBytes = downloaded; lastTick = now }
                        update(request.fileName) { it.copy(downloadedBytes = downloaded, totalBytes = request.size, speedBytesPerSecond = speed, etaSeconds = if (speed > 1024) ((request.size - downloaded).coerceAtLeast(0) / speed).toLong() else null) }
                    }
                    check(expected <= 0 || part.length() >= expected) { "Parte incompleta o corrupta" }
                    break
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    attempt++
                    if (attempt >= 5) throw failure
                    update(request.fileName) { it.copy(state = DownloadState.DOWNLOADING, error = "Conexión perdida. Reintentando ($attempt/5): ${failure.message}") }
                    retryConnection()
                }
            }
        }
        val assembling = File(partsDir, "$safeName.assembling")
        assembling.outputStream().buffered().use { out -> partFiles.forEach { part -> part.inputStream().buffered().use { it.copyTo(out) } } }
        if (request.method !in setOf("cocmed", "m", "moodle", "evea", "ts", "pv_ts")) removeInjectedPng(assembling)
        if (destination.exists()) destination.delete()
        check(assembling.renameTo(destination)) { "No se pudo guardar el archivo final" }
        val publishedUri = repository.publishCompletedFile(destination, selectedPath)
        update(request.fileName) { it.copy(publicUri = publishedUri.toString()) }
        destination.delete()
        partFiles.forEach(File::delete)
        update(request.fileName) { it.copy(downloadedBytes = request.size, speedBytesPerSecond = 0.0) }
    }

    private suspend fun downloadTodus(request: DownloadRequest, url: String, file: File, gate: MutableStateFlow<Boolean>) {
        var attempt = 0
        while (attempt < 5) {
            try {
                val offset = file.length().takeIf { it > 0 }
                transfer(request, url, file, offset, request.size, gate) { bytes ->
                    update(request.fileName) { it.copy(downloadedBytes = bytes, totalBytes = request.size) }
                }
                check(request.size <= 0 || file.length() >= request.size) { "Descarga Todus incompleta" }
                return
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                attempt++
                if (attempt >= 5) throw failure
                retryConnection()
            }
        }
    }

    private suspend fun transfer(request: DownloadRequest, url: String, file: File, rangeStart: Long?, expected: Long, gate: MutableStateFlow<Boolean>, progress: (Long) -> Unit) {
        val target = if (request.method == "todus") url.toHttpUrl().newBuilder().scheme("https").host("s3.todus.cu").port(443).build() else url.toHttpUrl()
        val builder = Request.Builder().url(target).header("User-Agent", USER_AGENT)
        if (rangeStart != null) builder.header("Range", "bytes=$rangeStart-")
        client.newCall(builder.build()).execute().use { response ->
            validateResponse(response)
            val append = rangeStart != null && response.code == 206
            RandomAccessFile(file, "rw").use { output ->
                if (append) output.seek(output.length()) else output.setLength(0)
                val input = response.body?.byteStream() ?: error("Respuesta vacía")
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    gate.first { it }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    progress(output.length())
                }
            }
        }
        check(expected <= 0 || file.length() >= expected) { "Descarga incompleta" }
    }

    private suspend fun expand(request: DownloadRequest): List<String> {
        if (request.method !in setOf("cocmed", "moodle", "evea", "md", "ts", "pv_ts", "rev2", "r", "todus")) return request.urls
        val short = request.urls.firstOrNull()?.takeIf { !it.startsWith("http") } ?: return request.urls
        val response = client.newCall(Request.Builder().url("https://downfree-hiyabo.onrender.com/unshort/${short.trimStart('/')}").get().build()).execute().use { it.body?.string().orEmpty() }
        val long = JSONObject(response).optString("enlace_largo")
        if (long.isBlank()) return request.urls
        return runCatching { JSONArray(long).let { array -> (0 until array.length()).map(array::getString) } }.getOrElse { listOf(long) }
    }

    private suspend fun authenticate(request: DownloadRequest) {
        if (request.method in setOf("m", "todus")) return
        if (request.method in setOf("cocmed", "evea", "md", "ts")) {
            val body = JSONObject().put("id", request.id).toString()
            val sessionRequest = Request.Builder().url("https://downfree-hiyabo.onrender.com/session").post(body.toRequestBody(JSON_MEDIA)).build()
            client.newCall(sessionRequest).execute().use { response -> check(response.isSuccessful) { "Error creando sesión: ${response.code}" } }
            return
        }
        val callMarker = "/" + '$'.toString().repeat(3) + "call" + '$'.toString().repeat(3)
        val base = request.connector.substringBefore("^").takeIf(String::isNotBlank) ?: request.urls.firstOrNull().orEmpty().substringBefore(callMarker)
        val login = when (request.method) {
            "moodle" -> request.connector.trimEnd('/') + "/login/index.php"
            "pv_ts" -> request.connector.substringBefore("ndex.php?P") + "index.php?P=UserLogin"
            "rev2" -> request.connector.substringBefore("author") + "login/signIn"
            else -> "$base/login/signIn"
        }
        val page = client.newCall(Request.Builder().url(login).header("User-Agent", USER_AGENT).get().build()).execute().use { it.body?.string().orEmpty() }
        val token = Regex("name=[\"'](?:logintoken|csrfToken)[\"'][^>]*value=[\"']([^\"']+)").find(page)?.groupValues?.get(1)
        val fields = when (request.method) {
            "moodle" -> listOf("anchor" to "", "logintoken" to (token ?: ""), "username" to request.username, "password" to request.password, "rememberusername" to "1")
            "pv_ts" -> listOf("F_UserName" to request.username, "F_Password" to request.password)
            "rev2" -> listOf("source" to "", "username" to request.username, "password" to request.password, "remember" to "1")
            else -> listOfNotNull(token?.let { "csrfToken" to it }, "source" to "", "username" to request.username, "password" to request.password, "remember" to "1")
        }
        val form = fields.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
        client.newCall(Request.Builder().url(login).header("User-Agent", USER_AGENT).post(form.toRequestBody(FORM_MEDIA)).build()).execute().use { response ->
            check(response.isSuccessful) { "No se pudo iniciar sesión: ${response.code}" }
        }
    }

    suspend fun streamPayload(raw: String, rangeStart: Long?, rangeEnd: Long?, output: java.io.OutputStream) {
        val request = DownloadRequest.parse(raw)
        val urls = expand(request)
        authenticate(request)
        if (request.method == "todus") {
            streamChunk(request, urls.first(), rangeStart, rangeEnd, output)
            return
        }
        val partSize = request.partSizeMb.coerceAtLeast(1).toLong() * 1024 * 1024
        var offset = 0L
        urls.forEachIndexed { index, chunk ->
            val partBytes = if (index < urls.lastIndex) partSize else (request.size - offset).coerceAtLeast(0)
            val partEnd = if (partBytes > 0) offset + partBytes - 1 else Long.MAX_VALUE
            if ((rangeEnd == null || rangeEnd >= offset) && (rangeStart == null || rangeStart <= partEnd)) {
                streamChunk(request, chunkUrl(request, chunk), rangeStart?.let { (it - offset).coerceAtLeast(0) }, rangeEnd?.let { (it - offset).coerceAtMost(if (partBytes > 0) partBytes - 1 else Long.MAX_VALUE) }, output)
            }
            offset += partBytes
        }
    }

    private fun streamChunk(request: DownloadRequest, url: String, start: Long?, end: Long?, output: java.io.OutputStream) {
        val target = if (request.method == "todus") url.toHttpUrl().newBuilder().scheme("https").host("s3.todus.cu").port(443).build() else url.toHttpUrl()
        val builder = Request.Builder().url(target).header("User-Agent", USER_AGENT)
        if (start != null) builder.header("Range", "bytes=$start-${end ?: ""}")
        client.newCall(builder.build()).execute().use { response ->
            validateResponse(response)
            response.body?.byteStream()?.use { it.copyTo(output, 8192) } ?: error("Respuesta vacía")
        }
    }

    private fun chunkUrl(request: DownloadRequest, chunk: String): String = when (request.method) {
        "cocmed" -> chunk.trimEnd('/') + "/.file"
        "m", "ts", "md", "rev2", "pv_ts", "mined", "r", "moodle", "evea", "todus" -> chunk
        else -> request.connector.split("^").let { (prefix, suffix) -> prefix + chunk + suffix }
    }

    private fun validateResponse(response: okhttp3.Response) {
        if (response.code == 404 || response.code == 410) throw IllegalStateException("El enlace ya fue eliminado.")
        if (response.header("Content-Type").orEmpty().contains("text/html", true)) throw IllegalStateException("El enlace devolvió HTML en lugar del archivo.")
        check(response.isSuccessful) { "Respuesta HTTP ${response.code}" }
    }

    private suspend fun retryConnection(): Boolean {
        repeat(5) { index ->
            val online = runCatching {
                client.newCall(Request.Builder().url("https://www.portal.nauta.cu/login").get().build()).execute().use { it.code == 200 }
            }.getOrDefault(false)
            if (online) return true
            if (index < 4) delay(5000)
        }
        return false
    }

    private fun removeInjectedPng(file: File) {
        val pattern = "89 50 4e 47 0d 0a 1a 0a 00 00 00 0c 49 48 44 52 00 00 00 01 00 00 00 01 08 02 00 00 00 90 77 53 de 00 00 00 0c 49 44 41 54 78 9c 63 60 60 60 00 00 00 04 00 01 f6 17 38 55 00 00 00 00 49 45 4e 44 ae 42 60 82".split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val temp = File(file.parentFile, file.name + ".clean")
        file.inputStream().buffered().use { input -> temp.outputStream().buffered().use { output ->
            var tail = ByteArray(0)
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val data = tail + buffer.copyOf(count)
                val safeLimit = (data.size - pattern.size + 1).coerceAtLeast(0)
                var index = 0
                while (index < safeLimit) {
                    if (matchesAt(data, pattern, index)) index += pattern.size else output.write(data[index++].toInt())
                }
                tail = data.copyOfRange(index, data.size)
            }
            var index = 0
            while (index < tail.size) {
                if (index + pattern.size <= tail.size && matchesAt(tail, pattern, index)) index += pattern.size else output.write(tail[index++].toInt())
            }
        } }
        if (temp.exists()) {
            if (file.delete()) temp.renameTo(file) else temp.delete()
        }
    }

    private fun matchesAt(source: ByteArray, pattern: ByteArray, offset: Int): Boolean =
        offset + pattern.size <= source.size && pattern.indices.all { source[offset + it] == pattern[it] }

    fun pause(fileName: String) { pauseGates[fileName]?.value = false; update(fileName) { it.copy(state = DownloadState.PAUSED, speedBytesPerSecond = 0.0) } }
    fun resume(fileName: String) { pauseGates[fileName]?.value = true; update(fileName) { it.copy(state = DownloadState.DOWNLOADING) } }
    fun pauseAll() = itemsState.value.filter { it.state == DownloadState.DOWNLOADING }.forEach { pause(it.fileName) }
    fun resumeAll() = itemsState.value.filter { it.state == DownloadState.PAUSED }.forEach { resume(it.fileName) }
    suspend fun cancel(fileName: String) { jobs.remove(fileName)?.cancelAndJoin(); repository.removeStatus(fileName); itemsState.value = itemsState.value.filterNot { it.fileName == fileName }; persist() }
    suspend fun clearCompleted() { val names = itemsState.value.filter { it.state == DownloadState.COMPLETED }.map { it.fileName }; names.forEach { repository.removeStatus(it) }; itemsState.value = itemsState.value.filterNot { it.fileName in names }; persist() }

    private fun update(fileName: String, transform: (DownloadItem) -> DownloadItem) {
        itemsState.value = itemsState.value.map { if (it.fileName == fileName) transform(it) else it }
        itemsState.value.firstOrNull { it.fileName == fileName }?.let(onNotification)
        persist()
    }
    private fun persist() { scope.launch { repository.saveDownloads(itemsState.value) } }
    fun shutdown() { scope.cancel() }

    private class MemoryCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { this.cookies[url.host] = cookies.toMutableList() }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host].orEmpty().filter { it.matches(url) }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}
