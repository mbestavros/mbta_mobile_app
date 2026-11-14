package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.model.stopDetailsPage.TripData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdatePillModelTest {
    private val fixtures = LiveUpdateFixtures()

    private fun tripData(withTrip: Boolean = true) =
        TripData(
            TripDetailsPageFilter(
                tripId = fixtures.trip.id,
                vehicleId = "v1",
                routeId = fixtures.route.id,
                directionId = 0,
                stopId = fixtures.stopC.id,
                stopSequence = 30,
            ),
            trip = fixtures.trip.takeIf { withTrip },
            tripSchedules = null,
            tripPredictions = null,
            vehicle = null,
        )

    @Test
    fun trackPillWaitsForTheTrip() {
        assertNull(LiveUpdatePillModel.trackRoute(null, fixtures.global))
        assertNull(LiveUpdatePillModel.trackRoute(tripData(withTrip = false), fixtures.global))
    }

    @Test
    fun trackPillWaitsForGlobalData() {
        assertNull(LiveUpdatePillModel.trackRoute(tripData(), null))
    }

    @Test
    fun trackPillUsesTheTripRoute() {
        assertEquals(
            fixtures.route,
            LiveUpdatePillModel.trackRoute(tripData(), fixtures.global)?.route,
        )
    }

    @Test
    fun trackedPillResolvesTheRouteBeforeTheFirstSnapshot() {
        val session = LiveUpdateSession(fixtures.request)
        assertEquals(
            fixtures.route,
            LiveUpdatePillModel.trackedRoute(session, fixtures.global)?.route,
        )
        assertNull(LiveUpdatePillModel.trackedRoute(session, null))
        assertTrue(LiveUpdatePillModel.isStarting(session))
    }

    @Test
    fun trackedPillPrefersTheSnapshotRoute() {
        val session = LiveUpdateSession(fixtures.request, snapshot = fixtures.snapshot(null))
        assertEquals(fixtures.route, LiveUpdatePillModel.trackedRoute(session, null)?.route)
    }

    @Test
    fun trackedPillIsNotStartingOnceThereIsAState() {
        val state =
            LiveUpdateState(
                phase = LiveUpdatePhase.Waiting,
                routeName = "Red Line",
                headsign = "Alewife",
                routeColor = "DA291C",
                routeTextColor = "FFFFFF",
                boardingStopName = "C",
                destinationStopName = null,
                targetStopName = "C",
                label = LiveUpdateLabel.Unknown,
                targetTime = null,
                boardingTime = null,
                destinationArrival = null,
                eta = null,
                rideDuration = null,
                stopsAway = null,
                progress = 0,
                progressMax = 3,
                boardingPosition = 3,
                destinationPosition = null,
            )
        val session = LiveUpdateSession(fixtures.request, state = state)
        assertFalse(LiveUpdatePillModel.isStarting(session))
    }

    @Test
    fun lineIdsResolveToTheLine() {
        val objects = ObjectCollectionBuilder("LiveUpdatePillModelTest")
        val line = objects.line {
            id = "line-Green"
            color = "00843D"
            textColor = "FFFFFF"
        }
        val route = objects.route {
            id = "Green-B"
            lineId = "line-Green"
            color = "00843D"
            textColor = "FFFFFF"
        }
        val global = GlobalResponse(objects)

        val byRoute = LiveUpdatePillModel.resolve("Green-B", global)
        assertEquals(route, byRoute?.route)
        assertEquals(line, byRoute?.line)
        assertEquals(line, LiveUpdatePillModel.resolve("line-Green", global)?.line)
        assertNull(LiveUpdatePillModel.resolve("Nope", global))
    }
}
