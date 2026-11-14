package com.mbta.tid.mbta_app.android.liveUpdate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishDebouncerTest {
    private val tripEnded = LiveUpdatePhase.Finished(FinishReason.TripEnded)
    private val arrived = LiveUpdatePhase.Finished(FinishReason.ArrivedDestination)

    @Test
    fun `one bad update does not finish`() {
        val debouncer = FinishDebouncer()
        assertFalse(debouncer.shouldFinish(tripEnded, Any(), 0))
        // The next update shows the vehicle on the trip again.
        assertFalse(debouncer.shouldFinish(LiveUpdatePhase.Riding, Any(), 5_000))
        assertFalse(debouncer.shouldFinish(tripEnded, Any(), 30_000))
    }

    @Test
    fun `the ticker repeating one snapshot does not confirm`() {
        val debouncer = FinishDebouncer()
        val snapshot = Any()
        assertFalse(debouncer.shouldFinish(tripEnded, snapshot, 0))
        assertFalse(debouncer.shouldFinish(tripEnded, snapshot, 15_000))
        assertFalse(debouncer.shouldFinish(tripEnded, snapshot, 30_000))
    }

    @Test
    fun `finishes after two snapshots and the confirm time`() {
        val debouncer = FinishDebouncer()
        assertFalse(debouncer.shouldFinish(tripEnded, Any(), 0))
        assertFalse(debouncer.shouldFinish(tripEnded, Any(), 5_000))
        assertTrue(debouncer.shouldFinish(tripEnded, Any(), 21_000))
    }

    @Test
    fun `arrival finishes on the second snapshot without waiting`() {
        val debouncer = FinishDebouncer()
        assertFalse(debouncer.shouldFinish(arrived, Any(), 0))
        assertTrue(debouncer.shouldFinish(arrived, Any(), 3_000))
    }

    @Test
    fun `a different reason starts over`() {
        val debouncer = FinishDebouncer()
        assertFalse(debouncer.shouldFinish(tripEnded, Any(), 0))
        assertFalse(debouncer.shouldFinish(arrived, Any(), 25_000))
    }
}
