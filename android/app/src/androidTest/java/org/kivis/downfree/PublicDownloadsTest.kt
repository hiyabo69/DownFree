package org.kivis.downfree

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
                assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Down_Free/Downloads/", cursor.getString(1))
            } ?: throw AssertionError("MediaStore no devolvió el archivo publicado")
            assertEquals(published, repository.getDownloadUri(name, selectedTree = null))
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }
}
