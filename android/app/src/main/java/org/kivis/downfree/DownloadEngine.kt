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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "s3.todus.cu") listOf(InetAddress.getByName("152.207.206.86")) else Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
            val targetUrl = chunkUrl(request, chunk)

            // Resumir: Si la parte ya está descargada completamente por un intento previo, se omite
            if (part.exists() && expected > 0 && part.length() >= expected) {
                val done = partFiles.take(index + 1).sumOf(File::length)
                update(request.fileName) { it.copy(downloadedBytes = done, totalBytes = request.size) }
                return@forEachIndexed
            }

            var attempt = 0
            while (true) {
                try {
                    if (targetUrl.contains("aula.uclv.edu.cu")) {
                        if (!isSessionActive(targetUrl, request.fileName)) {
                            authenticate(request)
                        }
                    }
                    transfer(request, targetUrl, part, null, expected, gate) { bytes ->
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
            if (response.code == 416) return
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
        val first = request.urls.firstOrNull() ?: return request.urls
        if (first.startsWith("http://") || first.startsWith("https://")) {
            if (!first.contains("onrender.com/unshort/")) return request.urls
        }
        val short = first.substringAfter("onrender.com/unshort/").trimStart('/')
        val response = client.newCall(
            Request.Builder()
                .url("https://downfree-hiyabo.onrender.com/unshort/$short")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        val long = JSONObject(response).optString("enlace_largo")
        if (long.isBlank()) return request.urls
        val normalizedLong = normalizeLegacyObject(long)
        return runCatching {
            JSONArray(normalizedLong).let { array -> (0 until array.length()).map(array::getString) }
        }.getOrElse { listOf(long) }
    }

    private suspend fun authenticate(request: DownloadRequest) {
        if (request.method in setOf("m", "todus")) return
        if (request.method in setOf("cocmed", "evea", "md", "ts")) {
            val body = JSONObject().put("id", request.id).toString()
            val sessionRequest = Request.Builder()
                .url("https://downfree-hiyabo.onrender.com/session")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(sessionRequest).execute().use { response ->
                check(response.isSuccessful) { "Error creando sesión: ${response.code}" }
                val responseText = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(responseText) }.getOrNull()
                if (json != null) {
                    val map = mutableMapOf<String, String>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "error") {
                            map[key] = json.optString(key)
                        }
                    }
                    cookieJar.setGlobalCookies(map)
                }
            }
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
            ?: Regex("value=[\"']([^\"']+)[\"'][^>]*name=[\"'](?:logintoken|csrfToken)[\"']").find(page)?.groupValues?.get(1)
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

    private fun isSessionActive(testUrl: String, fileName: String): Boolean {
        return runCatching {
            val req = Request.Builder().url(testUrl).header("User-Agent", USER_AGENT).head().build()
            client.newCall(req).execute().use { it.code in 200..299 }
        }.getOrElse {
            update(fileName) { it.copy(error = "Error al verificar sesión. Puede que la nube esté caída en este momento.") }
            false
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
        else -> {
            val parts = request.connector.split("^")
            if (parts.size >= 2) parts[0] + chunk + parts[1] else request.connector + chunk
        }
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
        val pattern = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xde.toByte(), 0x00, 0x00, 0x00, 0x0c, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9c.toByte(), 0x63, 0x60, 0x60, 0x60, 0x00,
            0x00, 0x00, 0x04, 0x00, 0x01, 0xf6.toByte(), 0x17, 0x38,
            0x55, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e,
            0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        val temp = File(file.parentFile, file.name + ".clean")
        file.inputStream().buffered().use { input ->
            temp.outputStream().buffered().use { output ->
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
            }
        }
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
        private val cookiesByHost = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
        private val globalCookies = ConcurrentHashMap<String, String>()

        fun setGlobalCookies(newCookies: Map<String, String>) {
            globalCookies.putAll(newCookies)
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostMap = cookiesByHost.getOrPut(url.host) { ConcurrentHashMap() }
            cookies.forEach { hostMap[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = mutableListOf<Cookie>()
            cookiesByHost[url.host]?.values?.filter { it.matches(url) }?.let(list::addAll)
            globalCookies.forEach { (name, value) ->
                if (list.none { it.name == name }) {
                    runCatching {
                        Cookie.Builder().name(name).value(value).domain(url.host).build()
                    }.getOrNull()?.let(list::add)
                }
            }
            return list
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FORM_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}
