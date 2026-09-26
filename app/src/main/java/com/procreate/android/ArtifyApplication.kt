package com.procreate.android

import android.app.Application
import com.procreate.android.ai.AiKeyStore
import com.procreate.android.debug.AiBugReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

class ArtifyApplication : Application() {
    /** Project writes must survive an Activity being destroyed immediately after onPause. */
    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes autosaves across old/new CanvasActivity instances opening the same project. */
    val projectSaveMutex = Mutex()

    /** Survives the Gallery -> Canvas transition so matchmaking does not reopen the socket. */
    val collaborationClient by lazy {
        com.procreate.android.collaboration.CollaborationClient(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Must precede any analysis request: PlanVisionClient runs on background threads with no
        // Context, so it reads the keys through AiKeyStore's cached fields rather than prefs.
        AiKeyStore.init(this)
        // Loads the last known subscription state so a gated feature can answer instantly at
        // launch; BillingManager then confirms it against Play from CanvasActivity.
        com.procreate.android.billing.Entitlements.init(this)
        // Both no-ops outside a debug build with a key configured - see AiBugReporter.isAvailable.
        AiBugReporter.installCrashHandler(this)
        AiBugReporter.processPendingCrashes(this)
    }
}
