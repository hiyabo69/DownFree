package org.kivis.downfree

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.AtomicFile

internal class DownloadRepository(private val context: Context) {
    private val root = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "")
    private val downloadsFile = File(root, "downloads.json")
    private val configFile = File(root, "download_path.json")
    private val statusFile = File(root, "download_status.json")

    fun getDownloadRoot(selectedPath: String?): File = File(resolveSelectedPath(selectedPath), "Down_Free")

    private fun resolveSelectedPath(value: String?): String =
        value?.takeIf { it.isNotBlank() && !it.startsWith("content://") } ?: root.absolutePath

    suspend fun exportCompletedFile(source: File, selectedTree: String?): Uri? = withContext(Dispatchers.IO) {
        if (selectedTree.isNullOrBlank() || !selectedTree.startsWith("content://")) return@withContext null
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(selectedTree)) ?: return@runCatching null
            val downFree = tree.findFile("Down_Free") ?: tree.createDirectory("Down_Free") ?: return@runCatching null
            val downloads = downFree.findFile("Downloads") ?: downFree.createDirectory("Downloads") ?: return@runCatching null
            downloads.findFile(source.name)?.delete()
            val document = downloads.createFile("application/octet-stream", source.name) ?: return@runCatching null
            context.contentResolver.openOutputStream(document.uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            document.uri
        }.getOrNull()
    }

    fun getTreeFileUri(name: String, selectedTree: String?): Uri? {
        if (selectedTree.isNullOrBlank() || !selectedTree.startsWith("content://")) return null
        return runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(selectedTree)) ?: return@runCatching null
            tree.findFile("Down_Free")?.findFile("Downloads")?.findFile(File(name).name)?.uri
        }.getOrNull()
    }
    fun downloadsDir(selectedPath: String?): File = File(getDownloadRoot(selectedPath), "Downloads")
    fun partsDir(selectedPath: String?): File = File(getDownloadRoot(selectedPath), "Parts")

    suspend fun loadDownloads(): List<DownloadItem> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(downloadsFile.readText())
            (0 until array.length()).mapNotNull { i ->
                val old = array.optJSONObject(i) ?: return@mapNotNull null
                DownloadItem(
                    fileName = old.optString("filename"), urlData = old.optString("url_data"),
                    totalBytes = old.optLong("total_size"),
                ).takeIf { it.fileName.isNotBlank() && it.urlData.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveDownloads(downloads: List<DownloadItem>) = withContext(Dispatchers.IO) {
        writeAtomic(downloadsFile, JSONArray().also { array -> downloads.forEach { array.put(it.toJson()) } }.toString())
    }

    suspend fun loadConfig(): JSONObject = withContext(Dispatchers.IO) {
        runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
    }

    suspend fun saveConfig(path: String?, theme: String) = withContext(Dispatchers.IO) {
        writeAtomic(configFile, JSONObject().put("selected_path", path ?: "").put("theme_mode", theme).toString(2))
    }

    suspend fun loadStatuses(): JSONObject = withContext(Dispatchers.IO) {
        runCatching { JSONObject(statusFile.readText()) }.getOrDefault(JSONObject())
    }

    suspend fun saveStatus(filename: String, state: DownloadState, error: String? = null) = withContext(Dispatchers.IO) {
        val current = runCatching { JSONObject(statusFile.readText()) }.getOrDefault(JSONObject())
        current.put(filename, JSONObject().put("status", when (state) {
            DownloadState.COMPLETED -> "completed"
            DownloadState.ERROR -> "error"
            else -> state.name.lowercase()
        }).put("error", error).put("time", System.currentTimeMillis()))
        writeAtomic(statusFile, current.toString(2))
    }

    suspend fun removeStatus(filename: String) = withContext(Dispatchers.IO) {
        val current = runCatching { JSONObject(statusFile.readText()) }.getOrDefault(JSONObject())
        current.remove(filename)
        writeAtomic(statusFile, current.toString(2))
    }

    private fun writeAtomic(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
