package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.android.liveUpdate.LiveUpdateWatchdog.Action
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateWatchdogTest {
    private fun input(
        sinceStart: Duration = 30.minutes,
        sinceLastData: Duration? = 10.seconds,
        sinceLastFrame: Duration? = 10.seconds,
        sinceLastConnect: Duration = 30.minutes,
        reconnectsSinceFrame: Int = 0,
        totalDuration: Duration = 30.minutes,
        pastTarget: Duration? = (-10).minutes,
        restored: Boolean = false,
    ) =
        LiveUpdateWatchdog.Input(
            sinceStart,
            sinceLastData,
            sinceLastFrame,
            sinceLastConnect,
            reconnectsSinceFrame,
            totalDuration,
            pastTarget,
            restored,
        )

    @Test
    fun `fresh data does nothing`() {
        assertEquals(Action.None, LiveUpdateWatchdog.decide(input()))
    }

    @Test
    fun `a quiet channel on a live socket does nothing`() {
        // Heartbeat replies keep coming, so the socket is fine. Rebuilding it would only wake the
        // radio and repeat the trip and schedule requests.
        assertEquals(
            Action.None,
            LiveUpdateWatchdog.decide(
                input(sinceLastData = 25.minutes, sinceLastFrame = 20.seconds),
            ),
        )
    }

    @Test
    fun `a silent socket reconnects`() {
        assertEquals(
            Action.Reconnect,
            LiveUpdateWatchdog.decide(input(sinceLastData = 2.minutes, sinceLastFrame = 2.minutes)),
        )
    }

    @Test
    fun `does not reconnect again right after a reconnect`() {
        assertEquals(
            Action.None,
            LiveUpdateWatchdog.decide(
                input(sinceLastFrame = 5.minutes, sinceLastConnect = 1.minutes),
            ),
        )
    }

    @Test
    fun `reconnects back off while no frame arrives`() {
        val offline =
            input(
                sinceLastFrame = 20.minutes,
                sinceLastData = 20.minutes,
                sinceLastConnect = 4.minutes,
            )
        // After one failed rebuild the wait is 3 min, after two it is 6 min.
        assertEquals(
            Action.Reconnect,
            LiveUpdateWatchdog.decide(offline.copy(reconnectsSinceFrame = 1)),
        )
        assertEquals(Action.None, LiveUpdateWatchdog.decide(offline.copy(reconnectsSinceFrame = 2)))
        assertEquals(10.minutes, LiveUpdateWatchdog.reconnectInterval(10))
    }

    @Test
    fun `no data at all after a fresh start finishes`() {
        assertEquals(
            Action.FinishNoData,
            LiveUpdateWatchdog.decide(input(sinceStart = 3.minutes, sinceLastData = null)),
        )
    }

    @Test
    fun `a restored session without frames reconnects instead`() {
        assertEquals(
            Action.Reconnect,
            LiveUpdateWatchdog.decide(
                input(
                    sinceStart = 4.minutes,
                    sinceLastData = null,
                    sinceLastFrame = null,
                    sinceLastConnect = 4.minutes,
                    restored = true,
                ),
            ),
        )
    }

    @Test
    fun `stale data long after the target time finishes`() {
        assertEquals(
            Action.FinishLostConnection,
            LiveUpdateWatchdog.decide(input(sinceLastData = 5.minutes, pastTarget = 40.minutes)),
        )
    }

    @Test
    fun `a socket silent for a very long time finishes`() {
        assertEquals(
            Action.FinishLostConnection,
            LiveUpdateWatchdog.decide(
                input(sinceLastFrame = 50.minutes, sinceLastData = 50.minutes, pastTarget = null),
            ),
        )
    }

    @Test
    fun `max duration finishes`() {
        assertEquals(
            Action.FinishMaxDuration,
            LiveUpdateWatchdog.decide(input(totalDuration = 3.hours + 1.minutes)),
        )
    }
}
