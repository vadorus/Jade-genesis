package com.jadegenesis.mobile.cognitive

import org.json.JSONObject
import java.util.Locale

internal fun isStructuredCognitiveReview(json: JSONObject): Boolean {
    val verdict = json.opt("verdict") as? String ?: return false
    val note = json.opt("note") as? String ?: return false
    val confidence = (json.opt("confidence") as? Number)?.toDouble() ?: return false
    return verdict.lowercase(Locale.ROOT) in setOf("ok", "caution", "revise") &&
        note.isNotBlank() && confidence.isFinite() && confidence in 0.0..1.0
}
