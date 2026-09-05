package com.jadegenesis.mobile.diagnostics

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

class AdminGate(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_genesis_admin",
        Context.MODE_PRIVATE
    )

    private var unlocked = false

    companion object {
        private const val KEY_SALT = "admin_pin_salt"
        private const val KEY_HASH = "admin_pin_hash"
    }

    fun isConfigured(): Boolean =
        !prefs.getString(KEY_HASH, null).isNullOrBlank()

    fun isUnlocked(): Boolean = unlocked

    fun configure(pin: String) {
        validatePin(pin)
        val salt = UUID.randomUUID().toString()
        prefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash(salt, pin))
            .apply()
        unlocked = true
    }

    fun unlock(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        val actual = hash(salt, pin)
        unlocked = MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )
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

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
}
