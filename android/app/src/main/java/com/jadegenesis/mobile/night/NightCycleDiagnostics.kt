package com.jadegenesis.mobile.night

internal fun nightCycleFailureMetadata(steps: List<NightCycleStep>): Map<String, String> {
    val failed = steps.filterNot { it.success }
    return mapOf(
        "failed_phases" to failed.joinToString(",") { it.phase.name },
        "failure_details" to failed.take(3).joinToString(" | ") {
            "${it.phase.name}: ${it.summary.take(120)}"
        }.take(500)
    )
}
