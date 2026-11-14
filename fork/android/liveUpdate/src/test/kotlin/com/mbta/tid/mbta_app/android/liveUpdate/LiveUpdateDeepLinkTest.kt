package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.LineOrRoute
import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.routes.DeepLinkState
import com.mbta.tid.mbta_app.routes.SheetRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LiveUpdateDeepLinkTest {
    private val f = LiveUpdateFixtures()

    // Fixture stop IDs contain a slash, which a URL can't hold, so URL tests use a plain ID.
    private val plainRequest =
        f.requestWithDestination.copy(stopId = "place-pktrm", destinationStopId = "place-alfcl")

    @Test
    fun `waiting opens stop details with the trip selected`() {
        assertEquals(
            "https://go.mbta.com/s/${f.stopC.id}/r/Red/d/0/t/trip1",
            LiveUpdateDeepLink.path(f.request, LiveUpdatePhase.Waiting, "v1", f.global),
        )
        assertEquals(
            DeepLinkState.Stop(f.stopC.id, "Red", 0, "trip1"),
            LiveUpdateDeepLink.state(f.request, LiveUpdatePhase.Waiting, "v1", f.global),
        )
    }

    @Test
    fun `no phase yet opens stop details`() {
        val state = LiveUpdateDeepLink.state(f.request, null, "v1", f.global)

        assertFalse(state.follow)
        assertNull(state.followRoute)
    }

    @Test
    fun `parsed waiting path gives the same state`() {
        assertEquals(
            LiveUpdateDeepLink.state(plainRequest, LiveUpdatePhase.Waiting, "v1", f.global),
            DeepLinkState.from(
                LiveUpdateDeepLink.path(plainRequest, LiveUpdatePhase.Waiting, "v1", f.global),
            ),
        )
    }

    @Test
    fun `riding opens the trip page for the destination over the boarding station`() {
        val path = LiveUpdateDeepLink.path(plainRequest, LiveUpdatePhase.Riding, "v1", f.global)

        assertEquals(
            "https://go.mbta.com/s/place-pktrm/r/Red/d/0/t/trip1/v/v1/follow/place-alfcl",
            path,
        )
        val parsed = DeepLinkState.from(path) as DeepLinkState.Stop
        // Back from the trip page goes to the boarding station.
        assertEquals("place-pktrm", parsed.sheetRoute.stopId)
        assertEquals(
            LiveUpdateDeepLink.state(plainRequest, LiveUpdatePhase.Riding, "v1", f.global),
            parsed,
        )
        assertEquals(
            SheetRoutes.TripDetails(
                TripDetailsPageFilter(
                    "trip1",
                    "v1",
                    LineOrRoute.Id.fromString("Red"),
                    0,
                    "place-alfcl",
                    null,
                ),
            ),
            parsed.followRoute,
        )
    }

    @Test
    fun `the trip page targets the destination's station`() {
        val objects = ObjectCollectionBuilder("LiveUpdateDeepLinkTest")
        val station = objects.stop {
            id = "place-alfcl"
            locationType = com.mbta.tid.mbta_app.model.LocationType.STATION
        }
        val platform = objects.stop {
            id = "70061"
            parentStationId = station.id
        }
        val global = GlobalResponse(objects)
        val request = plainRequest.copy(destinationStopId = platform.id)

        assertEquals(station.id, LiveUpdateDeepLink.followStopId(request, global))
        assertNull(LiveUpdateDeepLink.followStopId(f.request, global))
        assertNull(
            LiveUpdateDeepLink.state(request, LiveUpdatePhase.Waiting, "v1", global).followStopId,
        )
    }

    @Test
    fun `riding without a vehicle still opens the trip page`() {
        val path = LiveUpdateDeepLink.path(plainRequest, LiveUpdatePhase.Riding, null, f.global)

        assertEquals("https://go.mbta.com/s/place-pktrm/r/Red/d/0/t/trip1/follow/place-alfcl", path)
        val parsed = DeepLinkState.from(path) as DeepLinkState.Stop
        assertNull(parsed.vehicleId)
        assertEquals("trip1", parsed.followRoute?.filter?.tripId)
    }

    @Test
    fun `riding without a destination targets the boarding stop`() {
        val request = plainRequest.copy(destinationStopId = null)
        val path = LiveUpdateDeepLink.path(request, LiveUpdatePhase.Riding, "v1", f.global)

        assertEquals("https://go.mbta.com/s/place-pktrm/r/Red/d/0/t/trip1/v/v1/follow", path)
        val parsed = DeepLinkState.from(path) as DeepLinkState.Stop
        assertEquals("place-pktrm", parsed.followRoute?.filter?.stopId)
    }

    @Test
    fun `the trip page follows the latest vehicle on the trip`() {
        val snapshot = f.snapshot(f.vehicle(Vehicle.CurrentStatus.InTransitTo, 40))
        assertEquals("v1", LiveUpdateDeepLink.vehicleId(f.request.copy(vehicleId = null), snapshot))

        val otherTrip = f.snapshot(f.vehicle(Vehicle.CurrentStatus.InTransitTo, 40, "trip2"))
        assertEquals(
            "v0",
            LiveUpdateDeepLink.vehicleId(f.request.copy(vehicleId = "v0"), otherTrip),
        )
        assertEquals("v0", LiveUpdateDeepLink.vehicleId(f.request.copy(vehicleId = "v0"), null))
    }

    @Test
    fun `platforms resolve to their station`() {
        val objects = ObjectCollectionBuilder("LiveUpdateDeepLinkTest")
        val station = objects.stop {
            id = "place-alfcl"
            locationType = com.mbta.tid.mbta_app.model.LocationType.STATION
        }
        val platform = objects.stop {
            id = "70061"
            parentStationId = station.id
        }
        val global = GlobalResponse(objects)

        assertEquals(station.id, LiveUpdateDeepLink.stationId(platform.id, global))
        assertEquals("unknown", LiveUpdateDeepLink.stationId("unknown", global))
    }

    @Test
    fun `grouped line routes use the line ID`() {
        val objects = ObjectCollectionBuilder("LiveUpdateDeepLinkTest")
        objects.line { id = "line-Green" }
        objects.route {
            id = "Green-B"
            lineId = "line-Green"
        }
        objects.line { id = "line-Red" }
        objects.route {
            id = "Red"
            lineId = "line-Red"
        }
        val global = GlobalResponse(objects)

        assertEquals("line-Green", LiveUpdateDeepLink.routeOrLineId("Green-B", global))
        assertEquals("Red", LiveUpdateDeepLink.routeOrLineId("Red", global))
        assertEquals("Unknown", LiveUpdateDeepLink.routeOrLineId("Unknown", global))
        assertEquals("Green-B", LiveUpdateDeepLink.routeOrLineId("Green-B", null))
    }
}
