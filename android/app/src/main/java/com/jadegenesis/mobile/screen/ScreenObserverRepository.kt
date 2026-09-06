package com.jadegenesis.mobile.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

data class ScreenFrame(
    val bytes: ByteArray,
    val capturedAt: Long,
    val sha256: String,
    val source: String,
    val focusInstruction: String
)

class ScreenObserverRepository(context: Context) {
    companion object {
        private const val MAX_CAPTURE_WIDTH = 960
        private const val TARGET_JPEG_BYTES = 1_050_000
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "screen-observer")
    private val latestFile = File(directory, "latest.jpg")
    private val metadataFile = File(directory, "latest.json")

    fun latestCaptureTimestamp(): Long =
        latestFrame()?.capturedAt ?: 0L

    fun latestImageFile(): File? = latestFile.takeIf { it.isFile }

    fun latestBitmap(): Bitmap? =
        latestFile.takeIf { it.isFile }?.let { file ->
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }

    fun latestSource(): String = readMetadata().optString("source", "pixel_screen")

    fun latestFocusInstruction(): String =
        readMetadata().optString("focus_instruction", "").trim()

    fun latestFrame(): ScreenFrame? {
        if (!latestFile.isFile) return null
        val bytes = runCatching { latestFile.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val metadata = readMetadata()
        val capturedAt = metadata.optLong("captured_at", latestFile.lastModified())
            .takeIf { it > 0L } ?: latestFile.lastModified()
        return ScreenFrame(
            bytes = bytes,
            capturedAt = capturedAt,
            sha256 = sha256(bytes),
            source = metadata.optString("source", "pixel_screen").ifBlank { "pixel_screen" },
            focusInstruction = metadata.optString("focus_instruction", "").trim()
        )
    }

    suspend fun awaitFrameAfter(
        requestedAt: Long,
        timeoutMs: Long = 12_000L
    ): ScreenFrame {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = latestFrame()
            if (frame != null && frame.capturedAt >= requestedAt) {
                return frame
            }
            delay(180L)
        }
        error("La capture d'écran Pixel n'a pas été produite à temps.")
    }

    fun saveBitmap(
        bitmap: Bitmap,
        source: String,
        focusInstruction: String = ""
    ): ScreenFrame {
        directory.mkdirs()
        val normalized = normalizeWidth(bitmap)
        val encoded = encodeBoundedJpeg(normalized)
        if (normalized !== bitmap) normalized.recycle()

        val temp = File(directory, "latest.tmp.jpg")
        FileOutputStream(temp).use { stream ->
            stream.write(encoded)
            stream.flush()
        }
        if (latestFile.exists()) latestFile.delete()
        check(temp.renameTo(latestFile)) { "Impossible de finaliser l'image pour Jade." }

        val capturedAt = System.currentTimeMillis()
        latestFile.setLastModified(capturedAt)
        metadataFile.writeText(
            JSONObject().apply {
                put("source", source.trim().ifBlank { "unknown_image" })
                put("focus_instruction", focusInstruction.trim().take(1_200))
                put("captured_at", capturedAt)
            }.toString()
        )
        return latestFrame() ?: error("Impossible de relire l'image enregistrée.")
    }

    fun importSharedImage(uri: Uri): ScreenFrame {
        val bitmap = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Impossible de lire l'image partagée.")
        return try {
            saveBitmap(
                bitmap = bitmap,
                source = "shared_image",
                focusInstruction = ""
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun normalizeWidth(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_CAPTURE_WIDTH) return bitmap
        val ratio = MAX_CAPTURE_WIDTH.toDouble() / bitmap.width.toDouble()
        return Bitmap.createScaledBitmap(
            bitmap,
            MAX_CAPTURE_WIDTH,
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun encodeBoundedJpeg(bitmap: Bitmap): ByteArray {
        var working = bitmap
        var ownsWorking = false
        var last = ByteArray(0)
        try {
            val widths = listOf(
                working.width,
                minOf(working.width, 840),
                minOf(working.width, 720)
            ).distinct()
            widths.forEach { targetWidth ->
                if (working.width != targetWidth) {
                    if (ownsWorking) working.recycle()
                    val ratio = targetWidth.toDouble() / working.width.toDouble()
                    working = Bitmap.createScaledBitmap(
                        working,
                        targetWidth,
                        (working.height * ratio).roundToInt().coerceAtLeast(1),
                        true
                    )
                    ownsWorking = true
                }
                listOf(80, 72, 64, 56, 48).forEach { quality ->
                    val output = ByteArrayOutputStream()
                    check(bitmapOrWorkingCompress(working, quality, output)) {
                        "Échec compression JPEG."
                    }
                    val bytes = output.toByteArray()
                    last = bytes
                    if (bytes.size <= TARGET_JPEG_BYTES) return bytes
                }
            }
            return last
        } finally {
            if (ownsWorking) working.recycle()
        }
    }

    private fun bitmapOrWorkingCompress(
        bitmap: Bitmap,
        quality: Int,
        output: ByteArrayOutputStream
    ): Boolean = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)

    private fun readMetadata(): JSONObject = runCatching {
        if (!metadataFile.isFile) return@runCatching JSONObject()
        JSONObject(metadataFile.readText())
    }.getOrDefault(JSONObject())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
