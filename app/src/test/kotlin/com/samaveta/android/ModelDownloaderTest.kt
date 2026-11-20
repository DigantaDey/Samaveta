package com.samaveta.android.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model integrity tests.
 *
 * A release blocker is "model integrity cannot be verified". These tests pin the behaviour that
 * makes that claim true: nothing reaches the model directory without a matching SHA-256, and a
 * mismatch never leaves a usable file behind.
 */
class ModelDownloaderTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempDir(): File =
        File.createTempFile("samaveta-models", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    private val payload = "model-bytes-for-testing".toByteArray()

    private fun downloader(dir: File, stream: () -> java.io.InputStream) =
        ModelDownloader(dir) { ByteArrayInputStream(stream().readBytes()) }

    @Test
    fun installsFileWhenChecksumMatches() {
        val dir = tempDir()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "https://models.example.com/m.bin", sha256(payload))
        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(payload.size.toLong(), result.getOrThrow().length())
    }

    @Test
    fun rejectsAndDeletesFileWhenChecksumMismatches() {
        val dir = tempDir()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "https://models.example.com/m.bin", "0".repeat(64))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IntegrityException)
        assertFalse("target must not exist", File(dir, "test-model").exists())
        assertFalse("partial file must be cleaned up", File(dir, "test-model.part").exists())
    }

    @Test
    fun refusesPlaintextUrl() {
        val dir = tempDir()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "http://models.example.com/m.bin", sha256(payload))
        assertTrue("HTTPS must be mandatory", result.isFailure)
    }

    @Test
    fun refusesPathTraversalInModelId() {
        val dir = tempDir()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("../../etc/passwd", "https://models.example.com/m.bin", sha256(payload))
        assertTrue("path traversal must be impossible", result.isFailure)
    }

    @Test
    fun refusesMalformedChecksum() {
        val dir = tempDir()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "https://models.example.com/m.bin", "not-a-checksum")
        assertTrue(result.isFailure)
    }

    @Test
    fun networkFailureIsReportedNotSwallowed() {
        val dir = tempDir()
        val downloader = ModelDownloader(dir) { throw IOException("connection reset") }
        val result = downloader.download("test-model", "https://models.example.com/m.bin", sha256(payload))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun verifyInstalledDetectsTampering() {
        val dir = tempDir()
        val file = File(dir, "model.bin").apply { writeBytes(payload) }
        val downloader = downloader(dir) { ByteArrayInputStream(payload) }

        assertEquals(IntegrityResult.Verified, downloader.verifyInstalled(file, sha256(payload)))

        file.writeBytes("tampered".toByteArray())
        val check = downloader.verifyInstalled(file, sha256(payload))
        assertTrue(check is IntegrityResult.Mismatch)
    }

    @Test
    fun verifyInstalledHandlesMissingFile() {
        val dir = tempDir()
        val check = downloader(dir) { ByteArrayInputStream(payload) }
            .verifyInstalled(File(dir, "absent.bin"), "a".repeat(64))
        assertTrue(check is IntegrityResult.Rejected)
    }

    @Test
    fun progressIsReportedMonotonically() {
        val dir = tempDir()
        val observed = mutableListOf<Long>()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "https://models.example.com/m.bin", sha256(payload)) { progress ->
                observed += progress.bytesDownloaded
            }
        assertTrue(result.isSuccess)
        assertEquals("all bytes downloaded", payload.size.toLong(), observed.last())
        assertEquals(observed.sorted(), observed)
    }

    @Test
    fun checksumIsCaseInsensitive() {
        val dir = tempDir()
        val upper = sha256(payload).uppercase()
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "https://models.example.com/m.bin", upper)
        assertTrue(result.isSuccess)
    }

    @Test
    fun overwritingAnExistingInstallIsAtomic() {
        val dir = tempDir()
        val existing = File(dir, "test-model").apply { writeBytes("old-version".toByteArray()) }
        val result = downloader(dir) { ByteArrayInputStream(payload) }
            .download("test-model", "https://models.example.com/m.bin", sha256(payload))
        assertTrue(result.isSuccess)
        assertEquals(payload.size.toLong(), existing.length())
    }
}
