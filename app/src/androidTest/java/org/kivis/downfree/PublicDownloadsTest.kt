package org.kivis.downfree

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublicDownloadsTest {
    @Test
    fun completedFileIsPublishedToPublicDownloadSubfolder() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DownloadRepository(context)
        val name = "downfree-media-store-${System.currentTimeMillis()}.txt"
        val source = File(context.cacheDir, name).apply { writeText("public downloads smoke test") }
        var published: android.net.Uri? = null
        try {
            published = repository.publishCompletedFile(source, selectedTree = null)
            assertNotNull(published)
            context.contentResolver.query(
                published!!,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(name, cursor.getString(0))
                assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Down_Free/", cursor.getString(1))
            } ?: throw AssertionError("MediaStore no devolvió el archivo publicado")
            assertEquals(published, repository.getDownloadUri(name, selectedTree = null))
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test
    fun previousDownloadsSubfolderIsMigratedToDownFreeRoot() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DownloadRepository(context)
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "downfree-legacy-path-${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Down_Free/Downloads/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val oldUri = context.contentResolver.insert(collection, values) ?: throw AssertionError("No se pudo preparar la ruta antigua")
        try {
            context.contentResolver.openOutputStream(oldUri, "w")?.use { it.write("legacy".toByteArray()) }
                ?: throw AssertionError("No se pudo escribir el fixture")
            context.contentResolver.update(oldUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            repository.preparePublicDownloadsFolder()
            context.contentResolver.query(oldUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Down_Free/", cursor.getString(0))
            } ?: throw AssertionError("No se pudo consultar la ruta migrada")
        } finally {
            context.contentResolver.delete(oldUri, null, null)
            repository.removePublicFolderSeed()
        }
    }
}
