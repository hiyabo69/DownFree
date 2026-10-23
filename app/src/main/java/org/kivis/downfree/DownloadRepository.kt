package org.kivis.downfree

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.AtomicFile

internal class DownloadRepository(private val context: Context) {
    private val root = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "")
    private val downloadsFile = File(root, "downloads.json")
    private val configFile = File(root, "download_path.json")
    private val statusFile = File(root, "download_status.json")

    private fun stagingRoot(): File = File(root, "Down_Free")

    private val publishedUrisFile = File(root, "published_download_uris.json")

    suspend fun preparePublicDownloadsFolder() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            migrateLegacyMediaStoreDownloads()
            if (findInPublicDownloads(".nomedia") != null) return@withContext
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".nomedia")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Down_Free/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("No se pudo preparar Download/Down_Free")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { } ?: error("No se pudo preparar Download/Down_Free")
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Down_Free")
            check(folder.exists() || folder.mkdirs()) { "No se pudo crear Download/Down_Free" }
        }
    }

    suspend fun removePublicFolderSeed() = withContext(Dispatchers.IO) {
        findInPublicDownloads(".nomedia")?.let { context.contentResolver.delete(it, null, null) }
    }

    suspend fun removeEmptyLegacyDownloadsFolder(selectedTree: String?) = withContext(Dispatchers.IO) {
        if (selectedTree.isNullOrBlank() || !selectedTree.startsWith("content://")) return@withContext
        migrateLegacyMediaStoreDownloads()
        runCatching {
            val treeUri = Uri.parse(selectedTree)
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching
            val selectedDocument = android.provider.DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').trimEnd('/')
            val downFree = if (selectedDocument == "Down_Free" || selectedDocument.endsWith("/Down_Free")) tree else tree.findFile("Down_Free")
                ?: return@runCatching
            val oldSubfolder = downFree.findFile("Downloads") ?: return@runCatching
            if (oldSubfolder.isDirectory) {
                oldSubfolder.listFiles().filter { it.isFile }.forEach { oldFile ->
                    val originalName = oldFile.name ?: return@forEach
                    val targetName = uniqueDocumentName(downFree, originalName)
                    val target = downFree.createFile(oldFile.type ?: mimeType(targetName), targetName) ?: return@forEach
                    val copied = runCatching {
                        val input = context.contentResolver.openInputStream(oldFile.uri) ?: error("No se pudo leer ${oldFile.name}")
                        val output = context.contentResolver.openOutputStream(target.uri, "w") ?: error("No se pudo escribir $targetName")
                        input.use { source -> output.use { source.copyTo(it) } }
                    }.getOrNull()
                    if (copied != null && (oldFile.length() <= 0 || copied == oldFile.length())) oldFile.delete() else target.delete()
                }
                if (oldSubfolder.listFiles().isEmpty()) oldSubfolder.delete()
            }
        }
    }

    private fun uniqueDocumentName(folder: DocumentFile, requested: String): String {
        if (folder.findFile(requested) == null) return requested
        val base = requested.substringBeforeLast('.', requested)
        val extension = requested.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        var index = 2
        var candidate: String
        do { candidate = "$base ($index)$extension"; index++ } while (folder.findFile(candidate) != null)
        return candidate
    }

    private fun migrateLegacyMediaStoreDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/Down_Free/Downloads/")
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Down_Free/") }
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                runCatching { context.contentResolver.update(uri, values, null, null) }
            }
        }
    }

    suspend fun publishCompletedFile(source: File, selectedTree: String?): Uri? = withContext(Dispatchers.IO) {
        val published = if (selectedTree?.startsWith("content://") == true) {
            copyIntoSelectedTree(source, selectedTree)
        } else {
            publishToPublicDownloads(source)
        } ?: error("No se pudo publicar el archivo en Downloads")

        val mapping = runCatching { JSONObject(publishedUrisFile.readText()) }.getOrDefault(JSONObject())
        mapping.put(source.name, published.toString())
        writeAtomic(publishedUrisFile, mapping.toString(2))
        published
    }

    fun getDownloadUri(name: String, selectedTree: String?): Uri? {
        val fileName = File(name).name
        val stored = runCatching { JSONObject(publishedUrisFile.readText()).optString(fileName) }
            .getOrNull()?.takeIf(String::isNotBlank)?.let(Uri::parse)
        val storedExists = stored?.let { uri ->
            runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        } ?: false
        if (storedExists) return stored
        val treeUri = findInSelectedTree(fileName, selectedTree)
        if (treeUri != null) return treeUri
        return findInPublicDownloads(fileName)
    }

    private fun copyIntoSelectedTree(source: File, treeValue: String): Uri? = runCatching {
        val treeUri = Uri.parse(treeValue)
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null
        val selectedDocument = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').trimEnd('/') }.getOrDefault("")
        val downFree = if (selectedDocument == "Down_Free" || selectedDocument.endsWith("/Down_Free")) tree
            else tree.findFile("Down_Free") ?: tree.createDirectory("Down_Free") ?: return@runCatching null
        downFree.findFile(source.name)?.delete()
        val destination = downFree.createFile(mimeType(source.name), source.name) ?: return@runCatching null
        context.contentResolver.openOutputStream(destination.uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: return@runCatching null
        destination.uri
    }.getOrNull()

    private fun publishToPublicDownloads(source: File): Uri? {
        findInPublicDownloads(".nomedia")?.let { context.contentResolver.delete(it, null, null) }
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Down_Free/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = findInPublicDownloads(source.name)
            existing?.let { context.contentResolver.delete(it, null, null) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(source.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            return try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("No se pudo abrir el archivo público")
                val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, completed, null, null)
                uri
            } catch (failure: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }

        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Down_Free")
        if (!publicDir.exists() && !publicDir.mkdirs()) return null
        val target = File(publicDir, source.name)
        source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    private fun findInPublicDownloads(fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(fileName, "${Environment.DIRECTORY_DOWNLOADS}/Down_Free/")
        return context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) else null
        }
    }

    private fun findInSelectedTree(fileName: String, selectedTree: String?): Uri? {
        if (selectedTree.isNullOrBlank() || !selectedTree.startsWith("content://")) return null
        return runCatching {
            val treeUri = Uri.parse(selectedTree)
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null
            val selectedDocument = android.provider.DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':').trimEnd('/')
            val downFree = if (selectedDocument == "Down_Free" || selectedDocument.endsWith("/Down_Free")) tree else tree.findFile("Down_Free")
            downFree?.findFile(fileName)?.uri
        }.getOrNull()
    }

    private fun mimeType(fileName: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(fileName).extension.lowercase()) ?: "application/octet-stream"
    fun downloadsDir(selectedPath: String?): File = File(stagingRoot(), "Complete")
    fun partsDir(selectedPath: String?): File = File(stagingRoot(), "Parts")

    suspend fun loadDownloads(): List<DownloadItem> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(downloadsFile.readText())
            (0 until array.length()).mapNotNull { i ->
                val old = array.optJSONObject(i) ?: return@mapNotNull null
                DownloadItem.fromJson(old).takeIf { it.fileName.isNotBlank() && it.urlData.isNotBlank() }
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
