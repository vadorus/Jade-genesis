package com.jadegenesis.mobile

import android.app.Application
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.state.SharedGenesisStateWorker

class JadeGenesisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JadeConfigRuntime.initialize(this)
        RuntimeEvalRuntime.initialize(this)
        SharedGenesisStateWorker.schedule(this)
    }
}
