package com.jadegenesis.mobile.screen

import android.content.Context
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest

data class ScreenFrame(
    val bytes: ByteArray,
    val capturedAt: Long,
    val sha256: String
)

class ScreenObserverRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "screen-observer")
    private val latestFile = File(directory, "latest.jpg")

    fun latestCaptureTimestamp(): Long =
        if (latestFile.isFile) latestFile.lastModified() else 0L

    fun latestFrame(): ScreenFrame? {
        if (!latestFile.isFile) return null
        val bytes = runCatching { latestFile.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return ScreenFrame(
            bytes = bytes,
            capturedAt = latestFile.lastModified(),
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        )
    }

    suspend fun awaitAndConsumeFrameAfter(
        requestedAt: Long,
        timeoutMs: Long = 10_000L
    ): ScreenFrame {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = latestFrame()
            if (frame != null && frame.capturedAt >= requestedAt) {
                runCatching { latestFile.delete() }
                return frame
            }
            delay(180L)
        }
        error("La capture d'écran Pixel n'a pas été produite à temps.")
    }
}
