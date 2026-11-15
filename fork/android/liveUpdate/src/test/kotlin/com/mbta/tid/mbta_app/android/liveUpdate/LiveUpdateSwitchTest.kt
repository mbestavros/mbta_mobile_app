package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.Vehicle.CurrentStatus.InTransitTo
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateSwitchTest {
    private val f = LiveUpdateFixtures()

    private fun filter(tripId: String) =
        TripDetailsPageFilter(tripId, "v2", Route.Id("Red"), 0, f.stopC.id, 30)

    private val session: LiveUpdateSession by lazy {
        val snapshot = f.snapshot(f.vehicle(InTransitTo, 20), mapOf(30 to 4.minutes))
        val anchor = checkNotNull(TrackingAnchor.from(snapshot, f.request))
        val state =
            LiveUpdateStateCalculator.compute(snapshot, f.request, anchor, wasOnTrip = true, f.now)
        LiveUpdateSession(f.request, snapshot, anchor, state)
    }

    @Test
    fun `tracking another trip while one is tracked asks first`() {
        assertTrue(LiveUpdateSwitch.needsConfirmation(session, filter("trip2")))
        // Still starting: the trip has no state yet, but it is tracked.
        assertTrue(
            LiveUpdateSwitch.needsConfirmation(LiveUpdateSession(f.request), filter("trip2")),
        )
    }

    @Test
    fun `nothing tracked or the same trip doesn't ask`() {
        assertFalse(LiveUpdateSwitch.needsConfirmation(null, filter("trip2")))
        assertFalse(LiveUpdateSwitch.needsConfirmation(session, filter(f.trip.id)))
    }

    @Test
    fun `the dialog names the tracked trip once it loads`() {
        assertEquals("Red Line" to "Alewife", LiveUpdateSwitch.currentTripName(session))
        assertNull(LiveUpdateSwitch.currentTripName(LiveUpdateSession(f.request)))
        assertNull(LiveUpdateSwitch.currentTripName(null))
    }
}
