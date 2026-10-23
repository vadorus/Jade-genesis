package com.jadegenesis.mobile.memory

import android.content.Context
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.node.NodeManager
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock

class CorrectionExamRunner(context: Context, private val dao: CorrectionDao, private val nodes: NodeManager) {
    private val prefs = context.applicationContext.getSharedPreferences("jade_correction_exam", Context.MODE_PRIVATE)

    suspend fun run(self: SelfModel) {
        val node = self.knownNodes.firstOrNull {
            it.kind == NodeKind.VPS && it.status == NodeStatus.ONLINE && it.brainReady &&
                "correct_mem_exam_v1" in it.capabilities
        } ?: return
        CorrectionExamEngine(dao,
            halted = { !nodes.correctionHaltRunning(node.nodeId) },
            stopped = { prefs.getBoolean("stopped", false) },
            replay = { current, budget ->
                val started = System.nanoTime()
                val facts = CorrectionGate.mutex.withLock {
                    val store = CorrectionStore(dao)
                    check(store.active().any { it.id == current.id && it.fact == current.fact }) {
                        "correction_exam_inactive"
                    }
                    store.relevant(current.question)
                }
                val remaining = budget - (System.nanoTime() - started) / 1000000
                check(remaining >= 100) { "correction_exam_budget" }
                val payload = JSONObject().apply {
                    put("user_input", current.question)
                    put("budget_ms", remaining)
                    put("corrections", CorrectionContextPayload.encode(facts))
                }
                nodes.correctionExam(node.nodeId, payload, remaining.toInt())
            }).run()
    }
}
