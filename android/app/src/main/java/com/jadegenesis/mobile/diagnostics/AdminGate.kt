package com.jadegenesis.mobile.diagnostics

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AdminGate(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_genesis_admin",
        Context.MODE_PRIVATE
    )

    private var unlocked = false

    companion object {
        private const val KEY_SALT = "admin_pin_salt"
        private const val KEY_HASH = "admin_pin_hash"
        private const val KEY_ALGORITHM = "admin_pin_algorithm"
        private const val KEY_FAILED_ATTEMPTS = "admin_pin_failed_attempts"
        private const val KEY_LOCKED_UNTIL = "admin_pin_locked_until"

        private const val ALGORITHM_PBKDF2 = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_BITS = 256
        private const val MAX_BACKOFF_MS = 60_000L
    }

    fun isConfigured(): Boolean =
        !prefs.getString(KEY_HASH, null).isNullOrBlank()

    fun isUnlocked(): Boolean = unlocked

    fun configure(pin: String) {
        validatePin(pin)
        if (isConfigured()) {
            require(unlocked) {
                "Déverrouille d'abord le mode admin avant de modifier le PIN."
            }
        }
        storePin(pin)
        resetFailures()
        unlocked = true
    }

    fun unlock(pin: String): Boolean {
        if (System.currentTimeMillis() < prefs.getLong(KEY_LOCKED_UNTIL, 0L)) {
            unlocked = false
            return false
        }

        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        val algorithm = prefs.getString(KEY_ALGORITHM, null)

        val actual = if (algorithm == ALGORITHM_PBKDF2) {
            pbkdf2(salt, pin)
        } else {
            legacyHash(salt, pin)
        }

        unlocked = MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )

        if (unlocked) {
            resetFailures()
            if (algorithm != ALGORITHM_PBKDF2) {
                storePin(pin)
            }
        } else {
            recordFailure()
        }
        return unlocked
    }

    fun lock() {
        unlocked = false
    }

    private fun validatePin(pin: String) {
        require(pin.length in 4..10 && pin.all { it.isDigit() }) {
            "Le PIN admin doit contenir entre 4 et 10 chiffres."
        }
    }

    private fun storePin(pin: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, pbkdf2(salt, pin))
            .putString(KEY_ALGORITHM, ALGORITHM_PBKDF2)
            .apply()
    }

    private fun pbkdf2(salt: String, pin: String): String {
        val saltBytes = Base64.decode(salt, Base64.NO_WRAP)
        val spec = PBEKeySpec(
            pin.toCharArray(),
            saltBytes,
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_BITS
        )
        return try {
            val bytes = SecretKeyFactory
                .getInstance(ALGORITHM_PBKDF2)
                .generateSecret(spec)
                .encoded
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacyHash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun recordFailure() {
        val failures = (prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1).coerceAtMost(30)
        val backoffMs = if (failures < 3) {
            0L
        } else {
            (1_000L shl (failures - 3).coerceAtMost(6))
                .coerceAtMost(MAX_BACKOFF_MS)
        }
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, failures)
            .putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + backoffMs)
            .apply()
    }

    private fun resetFailures() {
        prefs.edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }
}
