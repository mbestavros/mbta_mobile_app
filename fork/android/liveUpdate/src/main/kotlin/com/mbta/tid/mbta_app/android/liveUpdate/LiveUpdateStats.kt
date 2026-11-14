package com.mbta.tid.mbta_app.android.liveUpdate

import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session traffic counts, written to [LiveUpdateDiagnostics] when tracking ends. They show how
 * often the server pushes, which drives the radio's battery cost. Socket callbacks run on other
 * threads, so the counts are atomic.
 */
class LiveUpdateStats {
    val frames = AtomicInteger()
    val predictionMessages = AtomicInteger()
    val vehicleMessages = AtomicInteger()
    val reconnects = AtomicInteger()
    val posts = AtomicInteger()

    fun summary(minutes: Long): String =
        "minutes=$minutes frames=${frames.get()} predictions=${predictionMessages.get()} " +
            "vehicles=${vehicleMessages.get()} reconnects=${reconnects.get()} posts=${posts.get()}"
}
