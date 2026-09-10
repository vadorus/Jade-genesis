package com.jadegenesis.mobile.cognitive

import android.content.Context

internal interface ConversationLearningStorage {
    fun getString(key: String): String?
    fun putStrings(values: Map<String, String>)
}

internal class SharedPreferencesConversationLearningStorage(
    context: Context
) : ConversationLearningStorage {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_conversation_learning",
        Context.MODE_PRIVATE
    )

    override fun getString(key: String): String? =
        prefs.getString(key, null)

    override fun putStrings(values: Map<String, String>) {
        if (values.isEmpty()) return
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            editor.putString(key, value)
        }
        editor.apply()
    }
}
