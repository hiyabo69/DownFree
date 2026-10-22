package org.kivis.downfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModelsTest {
    @Test
    fun parsesLegacyDownloadMetadataWithUrlArray() {
        val request = DownloadRequest.parse(
            """{"fn":"video.mp4","fs":2048,"m":"moodle","urls":["https://example.test/part"],"t":2,"c":"https://example.test","u":"user","p":"pass","id":"42"}""",
        )
        assertEquals("video.mp4", request.fileName)
        assertEquals(2048L, request.size)
        assertEquals("moodle", request.method)
        assertEquals("https://example.test/part", request.urls.single())
        assertEquals("user", request.username)
    }

    @Test
    fun parsesPythonStyleSingleQuotedPayloads() {
        val parsed = DownloadRequest.parse("{'fn': 'archive.tar', 'fs': 32, 'm': 'm', 'urls': ['https://example.test/a']}")
        assertEquals("archive.tar", parsed.fileName)
        assertEquals("https://example.test/a", parsed.urls.single())
    }

    @Test
    fun acceptsLegacySingleStringUrl() {
        assertEquals("https://example.test/file", DownloadRequest.parse(
            """{"fn":"file.bin","urls":"https://example.test/file","m":"m"}""",
        ).urls.single())
    }

    @Test
    fun progressIsClampedAndUnknownSizeIsSafe() {
        assertEquals(0, DownloadItem("a", "url").progress)
        assertEquals(100, DownloadItem("a", "url", totalBytes = 50, downloadedBytes = 70).progress)
        assertTrue(formatDuration(null) == "--")
    }
}
