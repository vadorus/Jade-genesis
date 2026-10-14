package com.jadegenesis.mobile

import android.app.Application
import com.jadegenesis.mobile.cognitive.ConversationLearningRuntime
import com.jadegenesis.mobile.cognitive.RightHandLearningRuntime
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.evolution.EvolutionRuntime
import com.jadegenesis.mobile.night.NightCycleWorker
import com.jadegenesis.mobile.state.SharedGenesisStateWorker

class JadeGenesisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JadeConfigRuntime.initialize(this)
        RuntimeEvalRuntime.initialize(this)
        EvolutionRuntime.initialize(this)
        ConversationLearningRuntime.initialize(this)
        RightHandLearningRuntime.initialize(this)
        // This separate validation application must never schedule its normal
        // VPS sync or autonomous night work on the owner\'s live infrastructure.
        if (!BuildConfig.APPLICATION_ID.endsWith(".validation")) {
            SharedGenesisStateWorker.schedule(this)
            NightCycleWorker.schedule(this)
        }
    }
}
