package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.LocationType
import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.StopDetailsPageFilters
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.StopDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationPageTest {
    private val objects = ObjectCollectionBuilder("StationPageTest")
    private val boarding = objects.stop {
        id = "place-pktrm"
        locationType = LocationType.STATION
    }
    private val destination = objects.stop {
        id = "place-alfcl"
        locationType = LocationType.STATION
    }
    private val destinationPlatform = objects.stop {
        id = "70061"
        parentStationId = destination.id
    }
    private val other = objects.stop {
        id = "place-harsq"
        locationType = LocationType.STATION
    }
    private val global = GlobalResponse(objects)

    private val request =
        LiveUpdateRequest(
            tripId = "trip1",
            vehicleId = "v1",
            routeId = "Red",
            directionId = 0,
            stopId = boarding.id,
            stopSequence = 30,
        )
    private val stationIds =
        StationPage.trackedStationIds(request.withDestination(destinationPlatform.id, 50), global)

    private fun stopDetails(stopId: String?) =
        StopDetailsViewModel.State(
            routeData =
                stopId?.let {
                    StopDetailsViewModel.RouteData.Unfiltered(
                        StopDetailsPageFilters(it, null, null),
                        emptyList(),
                    )
                },
        )

    @Test
    fun `tracked stations are the boarding and destination stations`() {
        assertEquals(setOf(boarding.id, destination.id), stationIds)
        assertEquals(setOf(boarding.id), StationPage.trackedStationIds(request, global))
    }

    @Test
    fun `stop selected is stop details and overview is not`() {
        val tripState = TripDetailsViewModel.State()
        assertTrue(
            StationPage.isStopDetails(
                MapViewModel.State(
                    MapViewModel.LayerState.StopSelected(boarding, null),
                    layersInitialized = true,
                ),
                tripState,
            ),
        )
        assertFalse(
            StationPage.isStopDetails(
                MapViewModel.State(MapViewModel.LayerState.Overview, layersInitialized = true),
                tripState,
            ),
        )
    }

    @Test
    fun `open at the boarding or destination station`() {
        assertTrue(StationPage.isOpen(true, stopDetails(boarding.id), stationIds, global))
        assertTrue(StationPage.isOpen(true, stopDetails(destination.id), stationIds, global))
    }

    @Test
    fun `a platform page resolves to its station`() {
        assertTrue(
            StationPage.isOpen(true, stopDetails(destinationPlatform.id), stationIds, global),
        )
    }

    @Test
    fun `not open at another station`() {
        assertFalse(StationPage.isOpen(true, stopDetails(other.id), stationIds, global))
    }

    @Test
    fun `not open when stop details is closed or not loaded`() {
        // The stop details view model keeps its last filters after the page closes.
        assertFalse(StationPage.isOpen(false, stopDetails(boarding.id), stationIds, global))
        assertFalse(StationPage.isOpen(true, stopDetails(null), stationIds, global))
    }
}
