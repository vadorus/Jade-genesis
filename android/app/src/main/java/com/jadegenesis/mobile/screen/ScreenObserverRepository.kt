package com.jadegenesis.mobile.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
        private const val MAX_IMPORT_DECODE_WIDTH = MAX_CAPTURE_WIDTH * 2
        private const val TARGET_JPEG_BYTES = 1_050_000

        // Une capture locale reste disponible assez longtemps pour permettre
        // une analyse différée lorsque le PC/serveur vision est hors ligne.
        private const val CAPTURE_TTL_MS = 24L * 60L * 60L * 1_000L

        // Les fichiers temporaires ne doivent pas rester indéfiniment après
        // une interruption de processus pendant une sauvegarde.
        private const val TEMP_FILE_TTL_MS = 5L * 60L * 1_000L

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CAPTURE_KEY_ALIAS = "jade_screen_capture_aes_v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val ENCRYPTED_MAGIC = byteArrayOf(
            'J'.code.toByte(),
            'G'.code.toByte(),
            'E'.code.toByte(),
            '1'.code.toByte()
        )
    }

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "screen-observer")
    private val latestFile = File(directory, "latest.jpg")
    private val metadataFile = File(directory, "latest.json")
    private val tempImageFile = File(directory, "latest.tmp.jpg")
    private val tempMetadataFile = File(directory, "latest.tmp.json")

    init {
        purgeExpiredCapture()
        cleanupTemporaryFiles()
    }

    fun latestCaptureTimestamp(): Long =
        latestFrame()?.capturedAt ?: 0L

    fun latestImageFile(): File? {
        purgeExpiredCapture()
        return latestFile.takeIf { it.isFile }
    }

    fun latestBitmap(): Bitmap? =
        latestFrame()?.bytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

    fun latestSource(): String {
        purgeExpiredCapture()
        if (!latestFile.isFile) return "pixel_screen"
        return readMetadata().optString("source", "pixel_screen")
            .ifBlank { "pixel_screen" }
    }

    fun latestFocusInstruction(): String {
        purgeExpiredCapture()
        if (!latestFile.isFile) return ""
        return readMetadata().optString("focus_instruction", "").trim()
    }

    fun latestFrame(): ScreenFrame? {
        purgeExpiredCapture()
        if (!latestFile.isFile) return null

        val storedBytes = runCatching { latestFile.readBytes() }.getOrNull()
            ?: return null
        if (storedBytes.isEmpty()) return null

        val bytes = try {
            decryptFromStorage(storedBytes)
        } catch (_: Exception) {
            deleteCaptureFiles()
            return null
        }
        if (bytes.isEmpty()) return null

        val currentSha256 = sha256(bytes)
        val metadata = readMetadata()
        val metadataSha256 = metadata.optString("image_sha256").trim()
        val metadataMatchesImage =
            metadataSha256.isBlank() ||
                metadataSha256.equals(currentSha256, ignoreCase = true)

        val capturedAt = if (metadataMatchesImage) {
            metadata.optLong("captured_at", latestFile.lastModified())
                .takeIf { it > 0L }
                ?: latestFile.lastModified()
        } else {
            latestFile.lastModified()
        }

        val source = if (metadataMatchesImage) {
            metadata.optString("source", "pixel_screen")
                .ifBlank { "pixel_screen" }
        } else {
            "pixel_screen"
        }

        val focusInstruction = if (metadataMatchesImage) {
            metadata.optString("focus_instruction", "").trim()
        } else {
            ""
        }

        return ScreenFrame(
            bytes = bytes,
            capturedAt = capturedAt,
            sha256 = currentSha256,
            source = source,
            focusInstruction = focusInstruction
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
        cleanupTemporaryFiles()

        val normalized = normalizeWidth(bitmap)
        val encoded = try {
            encodeBoundedJpeg(normalized)
        } finally {
            if (normalized !== bitmap) normalized.recycle()
        }

        val capturedAt = System.currentTimeMillis()
        val imageSha256 = sha256(encoded)
        val cleanSource = source.trim().ifBlank { "unknown_image" }
        val cleanFocus = focusInstruction.trim().take(1_200)
        val encryptedImage = encryptForStorage(encoded)
        val metadataJson = JSONObject().apply {
            put("source", cleanSource)
            put("focus_instruction", cleanFocus)
            put("captured_at", capturedAt)
            put("image_sha256", imageSha256)
            put("storage_encrypted", true)
            put("storage_format", "AES_GCM_V1")
        }.toString()
        val encryptedMetadata = encryptForStorage(
            metadataJson.toByteArray(Charsets.UTF_8)
        )

        runCatching { tempImageFile.delete() }
        runCatching { tempMetadataFile.delete() }

        FileOutputStream(tempImageFile).use { stream ->
            stream.write(encryptedImage)
            stream.flush()
        }

        FileOutputStream(tempMetadataFile).use { stream ->
            stream.write(encryptedMetadata)
            stream.flush()
        }

        if (latestFile.exists()) {
            check(latestFile.delete()) {
                "Impossible de remplacer l'ancienne image de Jade."
            }
        }
        check(tempImageFile.renameTo(latestFile)) {
            "Impossible de finaliser l'image pour Jade."
        }

        if (metadataFile.exists() && !metadataFile.delete()) {
            // L'image est déjà valide. Le hash présent dans le nouveau metadata
            // empêche une ancienne metadata incohérente d'être prise pour vraie.
        }
        if (!tempMetadataFile.renameTo(metadataFile)) {
            runCatching { tempMetadataFile.delete() }
        }

        latestFile.setLastModified(capturedAt)
        metadataFile.takeIf { it.isFile }?.setLastModified(capturedAt)
        ScreenCaptureRetention.schedule(appContext)

        return ScreenFrame(
            bytes = encoded,
            capturedAt = capturedAt,
            sha256 = imageSha256,
            source = cleanSource,
            focusInstruction = cleanFocus
        )
    }

    fun importSharedImage(uri: Uri): ScreenFrame {
        require(uri.scheme.equals("content", ignoreCase = true)) {
            "Jade accepte uniquement les images partagées via content://."
        }

        val bitmap = decodeSharedImage(uri)

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

    /**
     * Supprime la capture uniquement si elle correspond encore exactement
     * à celle qui vient d'être analysée.
     *
     * Cela évite d'effacer une nouvelle capture créée pendant qu'un nœud
     * distant analysait l'image précédente.
     */
    fun deleteLatestIfMatches(expectedSha256: String): Boolean {
        val expected = expectedSha256.trim().lowercase()
        if (expected.isBlank()) return false

        purgeExpiredCapture()
        if (!latestFile.isFile) return false

        val storedBytes = runCatching { latestFile.readBytes() }.getOrNull()
            ?: return false
        if (storedBytes.isEmpty()) return false

        val currentBytes = runCatching {
            decryptFromStorage(storedBytes)
        }.getOrNull() ?: return false
        val currentSha256 = sha256(currentBytes)
        if (currentSha256 != expected) return false

        return deleteCaptureFiles()
    }

    /**
     * Garde-fou opportuniste en complément du Worker planifié : tout accès
     * au repository purge aussi immédiatement une capture âgée de 24 h.
     */
    fun purgeExpiredCapture(now: Long = System.currentTimeMillis()): Boolean {
        if (!latestFile.isFile) {
            if (metadataFile.isFile) {
                runCatching { metadataFile.delete() }
            }
            return false
        }

        val metadata = readMetadata()
        val capturedAt = metadata.optLong("captured_at", latestFile.lastModified())
            .takeIf { it > 0L }
            ?: latestFile.lastModified()

        if (capturedAt <= 0L) return false

        val ageMs = now - capturedAt
        if (ageMs < 0L || ageMs < CAPTURE_TTL_MS) return false

        return deleteCaptureFiles()
    }

    private fun deleteCaptureFiles(): Boolean {
        val imageDeleted = !latestFile.exists() || latestFile.delete()
        val metadataDeleted = !metadataFile.exists() || metadataFile.delete()

        runCatching { tempImageFile.delete() }
        runCatching { tempMetadataFile.delete() }

        return imageDeleted && metadataDeleted
    }

    private fun cleanupTemporaryFiles(now: Long = System.currentTimeMillis()) {
        listOf(tempImageFile, tempMetadataFile).forEach { file ->
            if (!file.isFile) return@forEach

            val modifiedAt = file.lastModified()
            val ageMs = if (modifiedAt > 0L) now - modifiedAt else Long.MAX_VALUE
            if (ageMs >= TEMP_FILE_TTL_MS) {
                runCatching { file.delete() }
            }
        }
    }

    private fun decodeSharedImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("Impossible de lire l'image partagée.")

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Le fichier partagé n'est pas une image décodable."
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_IMPORT_DECODE_WIDTH) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("Impossible de décoder l'image partagée.")
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
                    val previous = working
                    val ratio = targetWidth.toDouble() / previous.width.toDouble()
                    val scaled = Bitmap.createScaledBitmap(
                        previous,
                        targetWidth,
                        (previous.height * ratio).roundToInt().coerceAtLeast(1),
                        true
                    )
                    if (ownsWorking) previous.recycle()
                    working = scaled
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
        val stored = metadataFile.readBytes()
        if (stored.isEmpty()) return@runCatching JSONObject()
        val clear = decryptFromStorage(stored)
        JSONObject(clear.toString(Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    private fun encryptForStorage(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, captureKey())
        val iv = cipher.iv
        require(iv.isNotEmpty() && iv.size <= 255) {
            "IV de chiffrement invalide."
        }
        val encrypted = cipher.doFinal(clear)

        return ByteArrayOutputStream(
            ENCRYPTED_MAGIC.size + 1 + iv.size + encrypted.size
        ).apply {
            write(ENCRYPTED_MAGIC)
            write(iv.size)
            write(iv)
            write(encrypted)
        }.toByteArray()
    }

    private fun decryptFromStorage(stored: ByteArray): ByteArray {
        if (!hasEncryptedMagic(stored)) {
            // Migration transparente des captures créées avant le chiffrement.
            return stored
        }

        require(stored.size > ENCRYPTED_MAGIC.size + 1) {
            "Capture chiffrée tronquée."
        }
        val ivLength = stored[ENCRYPTED_MAGIC.size].toInt() and 0xff
        require(ivLength in 8..32) {
            "IV de capture invalide."
        }
        val ivStart = ENCRYPTED_MAGIC.size + 1
        val cipherStart = ivStart + ivLength
        require(cipherStart < stored.size) {
            "Capture chiffrée incomplète."
        }

        val iv = stored.copyOfRange(ivStart, cipherStart)
        val encrypted = stored.copyOfRange(cipherStart, stored.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            captureKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(encrypted)
    }

    private fun hasEncryptedMagic(value: ByteArray): Boolean =
        value.size >= ENCRYPTED_MAGIC.size &&
            value.copyOfRange(0, ENCRYPTED_MAGIC.size)
                .contentEquals(ENCRYPTED_MAGIC)

    private fun captureKey(): SecretKey = synchronized(ScreenObserverRepository::class.java) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
        val existing = keyStore.getKey(CAPTURE_KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return@synchronized existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                CAPTURE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
