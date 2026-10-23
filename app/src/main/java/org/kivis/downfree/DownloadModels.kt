package org.kivis.downfree

import org.json.JSONObject

internal enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, ERROR, CANCELLED }

internal data class DownloadItem(
    val fileName: String,
    val urlData: String,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val publicUri: String? = null,
) {
    val progress: Int get() = if (totalBytes <= 0) 0 else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

    fun toJson(): JSONObject = JSONObject().put("url_data", urlData).put("filename", fileName)
        .put("file_path", "/").put("display_name", fileName).put("total_size", totalBytes)
        .put("downloaded", downloadedBytes).put("speed", speedBytesPerSecond)
        .put("state", state.name).put("error", error).put("public_uri", publicUri)

    companion object {
        fun fromJson(json: JSONObject) = DownloadItem(
            fileName = json.optString("filename"), urlData = json.optString("url_data"),
            totalBytes = json.optLong("total_size"), downloadedBytes = json.optLong("downloaded"),
            state = runCatching { DownloadState.valueOf(json.optString("state", "QUEUED")) }.getOrDefault(DownloadState.QUEUED),
            error = json.optString("error").takeIf(String::isNotBlank),
            publicUri = json.optString("public_uri").takeIf(String::isNotBlank),
        )
    }
}

internal data class DownloadRequest(
    val fileName: String,
    val size: Long,
    val method: String,
    val urls: List<String>,
    val partSizeMb: Int,
    val connector: String,
    val username: String,
    val password: String,
    val id: String,
    val raw: JSONObject,
) {
    companion object {
        fun parse(raw: String): DownloadRequest {
            val json = JSONObject(normalizeLegacyObject(raw.trim()))
            val urlsValue = json.opt("urls")
            val urls = when (urlsValue) {
                is org.json.JSONArray -> (0 until urlsValue.length()).map { urlsValue.optString(it) }
                is String -> listOf(urlsValue)
                else -> emptyList()
            }
            require(json.optString("fn").isNotBlank()) { "Falta fn en los metadatos" }
            require(urls.isNotEmpty()) { "Falta urls en los metadatos" }
            return DownloadRequest(
                fileName = json.getString("fn"), size = json.optLong("fs"), method = json.optString("m"), urls = urls,
                partSizeMb = json.optInt("t", 1), connector = json.optString("c"), username = json.optString("u"),
                password = json.optString("p"), id = json.optString("id"), raw = json,
            )
        }
    }
}

internal fun normalizeLegacyObject(raw: String): String {
    if (raw.startsWith("{") && raw.contains('\'')) {
        val normalized = StringBuilder(raw.length)
        var inDouble = false
        var inSingle = false
        var escaped = false
        raw.forEachIndexed { index, char ->
            when {
                escaped -> { normalized.append(char); escaped = false }
                char == '\\' -> { normalized.append(char); escaped = true }
                char == '"' && !inSingle -> { inDouble = !inDouble; normalized.append(char) }
                char == '\'' && !inDouble -> { inSingle = !inSingle; normalized.append('"') }
                char == '"' && inSingle -> normalized.append("\\\"")
                else -> normalized.append(char)
            }
        }
        return normalizePythonLiterals(normalized.toString())
    }
    return raw
}

private fun normalizePythonLiterals(json: String): String {
    val result = StringBuilder(json.length)
    var inString = false
    var escaped = false
    var index = 0
    while (index < json.length) {
        val char = json[index]
        if (escaped) {
            result.append(char)
            escaped = false
            index++
        } else if (char == '\\' && inString) {
            result.append(char)
            escaped = true
            index++
        } else if (char == '"') {
            inString = !inString
            result.append(char)
            index++
        } else if (!inString && (json.startsWith("True", index) || json.startsWith("False", index) || json.startsWith("None", index))) {
            val literal = listOf("True", "False", "None").first { json.startsWith(it, index) }
            result.append(when (literal) { "True" -> "true"; "False" -> "false"; else -> "null" })
            index += literal.length
        } else {
            result.append(char)
            index++
        }
    }
    return result.toString()
}

internal fun formatBytes(value: Long): String {
    if (value < 1024) return "%.2f B".format(value.toDouble())
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var n = value.toDouble()
    for (unit in units) {
        n /= 1024.0
        if (n < 1024 || unit == units.last()) return "%.2f %s".format(n, unit)
    }
    return "%.2f TiB".format(n)
}

internal fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return "--"
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
