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
        sinceLastConnect: Duration = 30.minutes,
        totalDuration: Duration = 30.minutes,
        pastTarget: Duration? = (-10).minutes,
        restored: Boolean = false,
    ) =
        LiveUpdateWatchdog.Input(
            sinceStart,
            sinceLastData,
            sinceLastConnect,
            totalDuration,
            pastTarget,
            restored,
        )

    @Test
    fun `fresh data does nothing`() {
        assertEquals(Action.None, LiveUpdateWatchdog.decide(input()))
    }

    @Test
    fun `a quiet channel reconnects instead of finishing`() {
        // The old watchdog stopped silently after 10 minutes without data.
        assertEquals(Action.Reconnect, LiveUpdateWatchdog.decide(input(sinceLastData = 12.minutes)))
    }

    @Test
    fun `does not reconnect again right after a reconnect`() {
        assertEquals(
            Action.None,
            LiveUpdateWatchdog.decide(
                input(sinceLastData = 12.minutes, sinceLastConnect = 1.minutes),
            ),
        )
    }

    @Test
    fun `screen on uses the shorter stale limit`() {
        val stale = input(sinceLastData = 2.minutes, sinceLastConnect = 2.minutes)
        assertEquals(Action.None, LiveUpdateWatchdog.decide(stale))
        assertEquals(
            Action.Reconnect,
            LiveUpdateWatchdog.decide(stale, LiveUpdateWatchdog.STALE_AFTER_ON_WAKE),
        )
    }

    @Test
    fun `no data at all after a fresh start finishes`() {
        assertEquals(
            Action.FinishNoData,
            LiveUpdateWatchdog.decide(input(sinceStart = 3.minutes, sinceLastData = null)),
        )
    }

    @Test
    fun `a restored session without data reconnects instead`() {
        assertEquals(
            Action.Reconnect,
            LiveUpdateWatchdog.decide(
                input(sinceStart = 4.minutes, sinceLastData = null, restored = true),
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
    fun `no data for a very long time finishes`() {
        assertEquals(
            Action.FinishLostConnection,
            LiveUpdateWatchdog.decide(input(sinceLastData = 50.minutes, pastTarget = null)),
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
