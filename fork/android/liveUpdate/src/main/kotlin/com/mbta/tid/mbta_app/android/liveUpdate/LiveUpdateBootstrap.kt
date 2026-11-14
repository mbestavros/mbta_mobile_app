package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

object LiveUpdateBootstrap {
    private const val TAG = "LiveUpdate"
    private const val RETRY_DELAY_MS = 50L
    private const val MAX_ATTEMPTS = 100

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    fun start(application: Application, attempt: Int = 0) {
        if (started) return
        if (GlobalContext.getOrNull() == null) {
            if (attempt < MAX_ATTEMPTS) {
                handler.postDelayed({ start(application, attempt + 1) }, RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "Koin did not start; Live Updates are off")
            }
            return
        }
        started = true
        loadKoinModules(module { single { LiveUpdateController(application) } })
        LiveUpdateNotification.ensureChannel(application)
        application.registerActivityLifecycleCallbacks(LiveUpdateOverlayInstaller)
        // Explain earlier process deaths: was tracking active when the system killed the app?
        // The exit reasons come from a binder call, so read them off the main thread.
        Thread {
            LiveUpdateDiagnostics(application)
                .recordProcessExits(sessionSaved = LiveUpdateStore(application).load() != null)
        }
            .start()
    }
}
