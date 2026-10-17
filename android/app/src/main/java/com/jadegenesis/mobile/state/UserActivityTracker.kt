package com.jadegenesis.mobile.state

import android.content.Context

/**
 * Last time Alexandre really used Jade: app brought to the foreground or a
 * message sent. Background work (shared-state sync every 15 min, night cycle)
 * never updates it.
 *
 * #78: the VPS night cycle used the age of the phone snapshot as "Pixel in
 * use". The background worker publishes one every 15 minutes, all night while
 * charging, so the VPS never ran at night. The snapshot now carries this
 * timestamp and the VPS decides on it.
 *
 * Stored locally only; sent to the user's own VPS inside the snapshot.
 */
object UserActivityTracker {
    private const val PREFS = "jade_user_activity"
    private const val KEY_LAST_ACTIVITY = "last_user_activity_at"

    fun markActive(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getLong(KEY_LAST_ACTIVITY, 0L)
        if (UserActivityPolicy.shouldStore(previous, nowMs)) {
            prefs.edit().putLong(KEY_LAST_ACTIVITY, nowMs).apply()
        }
    }

    /** 0 when Jade has never been used since this version was installed. */
    fun lastActivityAt(context: Context): Long =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ACTIVITY, 0L)
}

/** Pure rules, covered by JVM tests. */
internal object UserActivityPolicy {
    /** At most one write per minute; a clock set backwards is accepted (never stuck in the future). */
    const val MIN_STEP_MS = 60_000L

    fun shouldStore(previousMs: Long, nowMs: Long): Boolean {
        if (nowMs <= 0L) return false
        if (previousMs <= 0L) return true
        if (nowMs < previousMs) return true
        return nowMs - previousMs >= MIN_STEP_MS
    }
}
