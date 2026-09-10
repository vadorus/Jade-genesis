package com.jadegenesis.mobile.state

/**
 * Cache-side compaction policy for Shared Genesis State.
 *
 * Operational snapshots are latest-state signals, not an append-only history.
 * Keeping every new UUID for those snapshots caused the 500-event phone cache
 * to churn continuously and could evict rarer learning/night-cycle reports.
 * Durable/non-operational events still deduplicate only by eventId.
 */
object SharedStateCachePolicy {
    val COALESCED_OPERATIONAL_KINDS = setOf(
        "identity_presence",
        "config_snapshot",
        "phone_node_snapshot",
        "memory_cursor",
        "resource_lease_snapshot",
        "runtime_eval_snapshot",
        "evolution_snapshot"
    )

    fun compact(
        events: List<SharedStateEvent>,
        maxEvents: Int
    ): List<SharedStateEvent> {
        if (events.isEmpty() || maxEvents <= 0) return emptyList()

        val durableById = linkedMapOf<String, SharedStateEvent>()
        val latestOperational = linkedMapOf<String, SharedStateEvent>()

        events.forEach { event ->
            if (event.kind in COALESCED_OPERATIONAL_KINDS) {
                val key = semanticKey(event)
                val previous = latestOperational[key]
                if (previous == null || isNewer(event, previous)) {
                    latestOperational[key] = event
                }
            } else {
                val previous = durableById[event.eventId]
                if (previous == null || isNewer(event, previous)) {
                    durableById[event.eventId] = event
                }
            }
        }

        return (durableById.values + latestOperational.values)
            .sortedWith(
                compareBy<SharedStateEvent> { it.serverRevision }
                    .thenBy { it.createdAt }
                    .thenBy { it.eventId }
            )
            .takeLast(maxEvents)
    }

    private fun semanticKey(event: SharedStateEvent): String =
        listOf(event.originNode, event.kind, event.entityId).joinToString("|")

    private fun isNewer(
        candidate: SharedStateEvent,
        previous: SharedStateEvent
    ): Boolean =
        compareValuesBy(
            candidate,
            previous,
            { it.serverRevision },
            { it.createdAt },
            { it.eventId }
        ) >= 0
}
