package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.LocationType
import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.routes.DeepLinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateDeepLinkTest {
    private val f = LiveUpdateFixtures()

    // Fixture stop IDs contain a slash, which a URL can't hold, so URL tests use a plain ID.
    private val plainRequest =
        f.requestWithDestination.copy(stopId = "place-pktrm", destinationStopId = "place-alfcl")

    /** A station with one platform, to check that the trip page targets the station. */
    private fun stationAndPlatform(): Triple<String, String, GlobalResponse> {
        val objects = ObjectCollectionBuilder("LiveUpdateDeepLinkTest")
        val station = objects.stop {
            id = "place-alfcl"
            locationType = LocationType.STATION
        }
        val platform = objects.stop {
            id = "70061"
            parentStationId = station.id
        }
        return Triple(station.id, platform.id, GlobalResponse(objects))
    }

    @Test
    fun `the stop link opens stop details with the trip selected`() {
        assertEquals(
            "https://go.mbta.com/s/${f.stopC.id}/r/Red/d/0/t/trip1",
            LiveUpdateDeepLink.path(f.request, f.global),
        )
        assertEquals(
            DeepLinkState.Stop(f.stopC.id, "Red", 0, "trip1"),
            LiveUpdateDeepLink.state(f.request, f.global),
        )
    }

    @Test
    fun `the parsed stop link gives the same state`() {
        assertEquals(
            LiveUpdateDeepLink.state(plainRequest, f.global),
            DeepLinkState.from(LiveUpdateDeepLink.path(plainRequest, f.global)),
        )
    }

    @Test
    fun `the plain stop link opens stop details with nothing selected`() {
        val path = LiveUpdateDeepLink.stopPath("place-alfcl")

        assertEquals("https://go.mbta.com/s/place-alfcl", path)
        assertEquals(DeepLinkState.Stop("place-alfcl", null, null, null), DeepLinkState.from(path))
    }

    @Test
    fun `waiting or no phase opens stop details, riding opens the trip page`() {
        val stopDetails =
            LiveUpdateDeepLink.Target.StopDetails(LiveUpdateDeepLink.path(f.request, f.global))

        assertEquals(stopDetails, LiveUpdateDeepLink.target(f.request, null, "v1", f.global))
        assertEquals(
            stopDetails,
            LiveUpdateDeepLink.target(f.request, LiveUpdatePhase.Waiting, "v1", f.global),
        )
        assertEquals(
            LiveUpdateDeepLink.Target.TripPage(f.request, "v1"),
            LiveUpdateDeepLink.target(f.request, LiveUpdatePhase.Riding, "v1", f.global),
        )
    }

    @Test
    fun `the trip page targets the destination's station`() {
        val (stationId, platformId, global) = stationAndPlatform()
        val request = plainRequest.copy(destinationStopId = platformId)

        assertEquals(
            TripDetailsPageFilter(
                "trip1",
                "v1",
                Route.Id("Red"),
                0,
                stationId,
                request.destinationStopSequence,
            ),
            LiveUpdateDeepLink.tripPageFilter(request, "v1", global),
        )
    }

    @Test
    fun `the trip page without a destination targets the boarding stop`() {
        val request = plainRequest.copy(destinationStopId = null)

        val filter = LiveUpdateDeepLink.tripPageFilter(request, null, f.global)

        assertEquals("place-pktrm", filter.stopId)
        assertEquals(request.stopSequence, filter.stopSequence)
        assertEquals(null, filter.vehicleId)
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
        val (stationId, platformId, global) = stationAndPlatform()

        assertEquals(stationId, LiveUpdateDeepLink.stationId(platformId, global))
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
