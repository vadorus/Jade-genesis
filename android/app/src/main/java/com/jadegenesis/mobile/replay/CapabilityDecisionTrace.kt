package com.jadegenesis.mobile.replay

import org.json.JSONArray
import org.json.JSONObject

data class CapabilityAlternativeTrace(
    val providerId: String,
    val displayName: String,
    val nodeId: String?,
    val providerType: String,
    val costClass: String,
    val available: Boolean,
    val eligible: Boolean,
    val requiresNetwork: Boolean,
    val operations: List<String>
)

data class CapabilityDecisionTrace(
    val traceId: String,
    val decisionKind: String,
    val operation: String,
    val policyName: String,
    val preferLocalFree: Boolean,
    val allowCloudFreeFallback: Boolean,
    val allowPaidProviders: Boolean,
    val alternatives: List<CapabilityAlternativeTrace>,
    val chosenProviderId: String?,
    val chosenNodeId: String?,
    val startedAt: Long,
    val completedAt: Long
)

/**
 * Stable JSON codec for free-first capability routing traces.
 *
 * No user prompt, task payload, media or tool output is persisted here.
 */
object CapabilityDecisionTraceCodec {

    fun toJson(trace: CapabilityDecisionTrace): JSONObject =
        JSONObject().apply {
            put("schema_version", 1)
            put("trace_id", trace.traceId)
            put("decision_kind", trace.decisionKind)
            put("operation", trace.operation)
            put("policy_name", trace.policyName)
            put("prefer_local_free", trace.preferLocalFree)
            put(
                "allow_cloud_free_fallback",
                trace.allowCloudFreeFallback
            )
            put("allow_paid_providers", trace.allowPaidProviders)
            put("chosen_provider_id", trace.chosenProviderId ?: "")
            put("chosen_node_id", trace.chosenNodeId ?: "")
            put("started_at", trace.startedAt)
            put("completed_at", trace.completedAt)

            put(
                "alternatives",
                JSONArray().apply {
                    trace.alternatives.forEach { alternative ->
                        put(
                            JSONObject().apply {
                                put(
                                    "provider_id",
                                    alternative.providerId
                                )
                                put(
                                    "display_name",
                                    alternative.displayName
                                )
                                put(
                                    "node_id",
                                    alternative.nodeId ?: ""
                                )
                                put(
                                    "provider_type",
                                    alternative.providerType
                                )
                                put(
                                    "cost_class",
                                    alternative.costClass
                                )
                                put(
                                    "available",
                                    alternative.available
                                )
                                put(
                                    "eligible",
                                    alternative.eligible
                                )
                                put(
                                    "requires_network",
                                    alternative.requiresNetwork
                                )
                                put(
                                    "operations",
                                    JSONArray(alternative.operations)
                                )
                            }
                        )
                    }
                }
            )
        }

    fun fromJson(json: JSONObject): CapabilityDecisionTrace {
        require(json.optInt("schema_version", 0) == 1) {
            "Unsupported CapabilityDecisionTrace schema."
        }

        val alternativesJson =
            json.optJSONArray("alternatives") ?: JSONArray()
        val alternatives = buildList {
            for (index in 0 until alternativesJson.length()) {
                val item = alternativesJson.getJSONObject(index)
                val operationsJson =
                    item.optJSONArray("operations") ?: JSONArray()
                val operations = buildList {
                    for (
                        operationIndex in 0 until operationsJson.length()
                    ) {
                        val operation =
                            operationsJson.optString(operationIndex).trim()
                        if (operation.isNotBlank()) {
                            add(operation)
                        }
                    }
                }

                add(
                    CapabilityAlternativeTrace(
                        providerId =
                            item.optString("provider_id"),
                        displayName =
                            item.optString("display_name"),
                        nodeId = item.optString("node_id")
                            .takeIf { it.isNotBlank() },
                        providerType =
                            item.optString("provider_type"),
                        costClass =
                            item.optString("cost_class"),
                        available =
                            item.optBoolean("available", false),
                        eligible =
                            item.optBoolean("eligible", false),
                        requiresNetwork =
                            item.optBoolean(
                                "requires_network",
                                false
                            ),
                        operations = operations
                    )
                )
            }
        }

        return CapabilityDecisionTrace(
            traceId = json.optString("trace_id"),
            decisionKind =
                json.optString("decision_kind"),
            operation = json.optString("operation"),
            policyName = json.optString("policy_name"),
            preferLocalFree =
                json.optBoolean("prefer_local_free", true),
            allowCloudFreeFallback =
                json.optBoolean(
                    "allow_cloud_free_fallback",
                    true
                ),
            allowPaidProviders =
                json.optBoolean(
                    "allow_paid_providers",
                    false
                ),
            alternatives = alternatives,
            chosenProviderId =
                json.optString("chosen_provider_id")
                    .takeIf { it.isNotBlank() },
            chosenNodeId =
                json.optString("chosen_node_id")
                    .takeIf { it.isNotBlank() },
            startedAt = json.optLong("started_at", 0L),
            completedAt = json.optLong("completed_at", 0L)
        )
    }
}
