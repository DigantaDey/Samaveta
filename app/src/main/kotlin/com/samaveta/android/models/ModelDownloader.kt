package com.samaveta.android.models

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Result of an integrity check. */
sealed interface IntegrityResult {
    data object Verified : IntegrityResult
    data class Mismatch(val expected: String, val actual: String) : IntegrityResult
    data class Rejected(val reason: String) : IntegrityResult
}

/**
 * Streaming download + verification. Kept free of Android service plumbing so every failure path
 * is unit-testable with an in-memory stream.
 */
class ModelDownloader(
    private val modelDirectory: File,
    private val fetch: (String) -> InputStream,
) {

    data class Progress(val bytesDownloaded: Long, val bytesTotal: Long) {
        val fraction: Float
            get() = if (bytesTotal <= 0) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    /**
     * Downloads [url] for [modelId] and verifies it against [expectedSha256].
     *
     * @return the installed file, or null when integrity failed (the partial file is removed).
     */
    fun download(modelId: String, url: String, expectedSha256: String, onProgress: (Progress) -> Unit = {}): Result<File> {
        // Validation returns a failure rather than throwing: a caller must never be able to treat
        // a refused download as a successful one by catching broadly.
        if (!url.startsWith("https://")) {
            return Result.failure(IntegrityException("model downloads must use HTTPS, got: $url"))
        }
        if (!isSha256(expectedSha256)) {
            return Result.failure(IntegrityException("expected checksum must be 64 hex characters"))
        }
        if (!isSafeIdentifier(modelId)) {
            return Result.failure(IntegrityException("model id must not contain path separators: $modelId"))
        }
        if (modelId.contains("..")) {
            return Result.failure(IntegrityException("model id must not contain '..': $modelId"))
        }

        val partFile = File(modelDirectory, "$modelId.part")
        val target = File(modelDirectory, modelId)
        modelDirectory.mkdirs()

        return try {
            fetch(url).use { input ->
                partFile.outputStream().use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                        onProgress(Progress(total, -1))
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        // Never leave a file whose contents we do not trust.
                        partFile.delete()
                        return Result.failure(IntegrityException("SHA-256 mismatch: expected $expectedSha256, got $actual"))
                    }
                    if (target.exists()) target.delete()
                    if (!partFile.renameTo(target)) {
                        return Result.failure(IntegrityException("could not move verified download into place"))
                    }
                    Result.success(target)
                }
            }
        } catch (e: IntegrityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Re-verifies an already-installed file (called on every runtime load). */
    fun verifyInstalled(file: File, expectedSha256: String): IntegrityResult {
        if (!file.exists()) return IntegrityResult.Rejected("file is missing")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            IntegrityResult.Verified
        } else {
            IntegrityResult.Mismatch(expectedSha256, actual)
        }
    }

    private fun isSha256(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{64}"))

    private fun isSafeIdentifier(value: String): Boolean =
        value.isNotBlank() && !value.contains("..") && value.none { it == '/' || it == '\\' }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

class IntegrityException(message: String) : IllegalStateException(message)
