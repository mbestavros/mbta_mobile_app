package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.routes.DeepLinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the fork's edit to upstream DeepLinkState: the vehicle and follow segments. */
class DeepLinkFollowTest {
    @Test
    fun `links without follow parse as before`() {
        val state = DeepLinkState.from("https://go.mbta.com/s/place-pktrm/r/Red/d/0/t/trip1")

        assertEquals(DeepLinkState.Stop("place-pktrm", "Red", 0, "trip1"), state)
        state as DeepLinkState.Stop
        assertFalse(state.follow)
        assertNull(state.vehicleId)
        assertNull(state.followRoute)
    }

    @Test
    fun `follow with a trip, route and direction opens the trip page`() {
        val state =
            DeepLinkState.from("https://go.mbta.com/s/place-pktrm/r/Red/d/1/t/trip1/v/v9/follow")
                as DeepLinkState.Stop

        assertTrue(state.follow)
        assertEquals("v9", state.vehicleId)
        val filter = state.followRoute?.filter
        assertEquals("trip1", filter?.tripId)
        assertEquals("v9", filter?.vehicleId)
        assertEquals(1, filter?.directionId)
        assertEquals("place-pktrm", filter?.stopId)
        // Stop details still opens first, so Back returns to the station.
        assertEquals("place-pktrm", state.sheetRoute.stopId)
    }

    @Test
    fun `follow with a stop targets that stop on the trip page`() {
        val state =
            DeepLinkState.from(
                "https://go.mbta.com/s/place-pktrm/r/Red/d/0/t/trip1/v/v9/follow/place-alfcl",
            ) as DeepLinkState.Stop

        assertEquals("place-alfcl", state.followStopId)
        assertEquals("place-alfcl", state.followRoute?.filter?.stopId)
        assertEquals("place-pktrm", state.sheetRoute.stopId)
    }

    @Test
    fun `follow without a trip does not open the trip page`() {
        val state =
            DeepLinkState.from("https://go.mbta.com/s/place-pktrm/r/Red/d/0/follow")
                as DeepLinkState.Stop

        assertTrue(state.follow)
        assertNull(state.followRoute)
    }
}
