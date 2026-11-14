package com.mbta.tid.mbta_app.android.liveUpdate

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decides whether the tracking connection is dead, and whether tracking should end.
 *
 * Liveness comes from socket frames, not trip data. A quiet channel is normal: predictions for a
 * trip that leaves in 20 minutes may not change for a long time. But a healthy Phoenix socket gets
 * a heartbeat reply every 30 seconds. So a socket that has been silent for [FRAME_STALE_AFTER] is
 * dead (for example, the server closed it while the phone slept), and the service rebuilds it. A
 * quiet but live socket is left alone, which keeps the radio idle.
 *
 * While the network is down, rebuilds back off from [FRAME_STALE_AFTER] up to
 * [MAX_RECONNECT_INTERVAL]. The service also checks at once when the network comes back.
 */
object LiveUpdateWatchdog {
    enum class Action {
        None,
        Reconnect,
        /** No data at all since the user started tracking. */
        FinishNoData,
        /** The connection or the data stopped, and the trip should be over. */
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
        /** Since the last socket frame of any kind, on any connection of this session. */
        val sinceLastFrame: Duration?,
        val sinceLastConnect: Duration,
        /** Rebuilds since the last socket frame. Each one doubles the wait before the next. */
        val reconnectsSinceFrame: Int,
        val totalDuration: Duration,
        /** How long ago the last known arrival or departure time was. Negative if it's ahead. */
        val pastTarget: Duration?,
        /** True if the session was restored after the process died. */
        val restored: Boolean,
    )

    val CHECK_INTERVAL = 1.minutes
    val FIRST_DATA_TIMEOUT = 2.minutes
    /** Three missed Phoenix heartbeats (30 s each). */
    val FRAME_STALE_AFTER = 90.seconds
    val MAX_RECONNECT_INTERVAL = 10.minutes
    /** Data this old, long after the trip's last known time, means the trip is over. */
    val DATA_STALE_AFTER = 3.minutes
    val PAST_TARGET_GRACE = 30.minutes
    val LOST_TIMEOUT = 45.minutes
    val MAX_DURATION = 3.hours

    fun decide(input: Input): Action {
        if (input.totalDuration > MAX_DURATION) return Action.FinishMaxDuration
        if (
            input.sinceLastData == null && !input.restored && input.sinceStart > FIRST_DATA_TIMEOUT
        ) {
            return Action.FinishNoData
        }
        val silent = input.sinceLastFrame ?: input.sinceStart
        val dataAge = input.sinceLastData ?: input.sinceStart
        val pastTarget = input.pastTarget ?: Duration.ZERO
        return when {
            silent > LOST_TIMEOUT -> Action.FinishLostConnection
            dataAge > DATA_STALE_AFTER && pastTarget > PAST_TARGET_GRACE ->
                Action.FinishLostConnection
            silent > FRAME_STALE_AFTER &&
                input.sinceLastConnect > reconnectInterval(input.reconnectsSinceFrame) ->
                Action.Reconnect
            else -> Action.None
        }
    }

    /** 90 s, 3 min, 6 min, then 10 min between rebuilds while no frame arrives. */
    internal fun reconnectInterval(reconnectsSinceFrame: Int): Duration {
        val doublings = reconnectsSinceFrame.coerceIn(0, 3)
        return (FRAME_STALE_AFTER * (1 shl doublings)).coerceAtMost(MAX_RECONNECT_INTERVAL)
    }
}
