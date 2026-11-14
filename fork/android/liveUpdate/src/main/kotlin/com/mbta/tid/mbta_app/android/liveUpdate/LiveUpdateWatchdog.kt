package com.mbta.tid.mbta_app.android.liveUpdate

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decides what to do when trip data stops arriving.
 *
 * A quiet channel is normal: predictions for a trip that leaves in 20 minutes may not change for a
 * long time. A dead socket looks the same. The phone sleeps with the screen off, Phoenix heartbeats
 * stop, and the server closes the socket. So a stale session reconnects, which also brings fresh
 * data on join. It ends only when the trip should clearly be over.
 */
object LiveUpdateWatchdog {
    enum class Action {
        None,
        Reconnect,
        /** No data at all since the user started tracking. */
        FinishNoData,
        /** Data stopped, reconnects didn't help, and the trip should be over. */
        FinishLostConnection,
        FinishMaxDuration,
    }

    /**
     * Durations are measured with SystemClock.elapsedRealtime, which keeps counting while the phone
     * sleeps, except [totalDuration], which is wall-clock time since the user tapped track.
     */
    data class Input(
        /** Since this service instance started the session (a fresh start or a restore). */
        val sinceStart: Duration,
        /** Since the last snapshot, or null if none arrived in this service instance. */
        val sinceLastData: Duration?,
        val sinceLastConnect: Duration,
        val totalDuration: Duration,
        /** How long ago the last known arrival or departure time was. Negative if it's ahead. */
        val pastTarget: Duration?,
        /** True if the session was restored after the process died. */
        val restored: Boolean,
    )

    val CHECK_INTERVAL = 1.minutes
    val FIRST_DATA_TIMEOUT = 2.minutes
    val STALE_AFTER = 3.minutes
    /** A shorter limit when the screen turns on, so the notification is fresh when seen. */
    val STALE_AFTER_ON_WAKE = 90.seconds
    val MIN_RECONNECT_INTERVAL = 30.seconds
    val PAST_TARGET_GRACE = 30.minutes
    val LOST_TIMEOUT = 45.minutes
    val MAX_DURATION = 3.hours

    fun decide(input: Input, staleAfter: Duration = STALE_AFTER): Action {
        if (input.totalDuration > MAX_DURATION) return Action.FinishMaxDuration
        if (
            input.sinceLastData == null && !input.restored && input.sinceStart > FIRST_DATA_TIMEOUT
        ) {
            return Action.FinishNoData
        }
        val quiet = input.sinceLastData ?: input.sinceStart
        val pastTarget = input.pastTarget ?: Duration.ZERO
        return when {
            quiet > LOST_TIMEOUT -> Action.FinishLostConnection
            quiet > STALE_AFTER && pastTarget > PAST_TARGET_GRACE -> Action.FinishLostConnection
            quiet > staleAfter &&
                input.sinceLastConnect > maxOf(MIN_RECONNECT_INTERVAL, staleAfter) ->
                Action.Reconnect
            else -> Action.None
        }
    }
}
