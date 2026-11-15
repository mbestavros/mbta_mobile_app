package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.StopDetailsPageFilters
import com.mbta.tid.mbta_app.model.TripDetailsFilter
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.stopDetailsPage.TripData
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.StopDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPageTest {
    private val filterA =
        TripDetailsPageFilter("tripA", "v1", Route.Id("Red"), 0, "place-pktrm", 30)

    private val objects = ObjectCollectionBuilder("TripPageTest")

    private fun state(
        filter: TripDetailsPageFilter?,
        context: Context?,
        tripLoaded: Boolean = true,
        vehicleTripId: String? = null,
    ) =
        TripDetailsViewModel.State(
            tripData =
                filter?.let {
                    val trip =
                        if (tripLoaded) {
                            objects.trip {
                                id = it.tripId
                                directionId = 0
                            }
                        } else {
                            null
                        }
                    val vehicle = vehicleTripId?.let { vehicleTrip ->
                        objects.vehicle {
                            id = "v1"
                            tripId = vehicleTrip
                            directionId = 0
                            currentStatus = Vehicle.CurrentStatus.StoppedAt
                        }
                    }
                    TripData(it, trip, null, null, vehicle)
                },
            context = context,
        )

    @Test
    fun `filter is only returned on the trip page`() {
        assertEquals(filterA, TripPage.trackableFilter(true, state(filterA, Context.TripDetails)))
        assertNull(TripPage.trackableFilter(false, state(filterA, Context.TripDetails)))
        assertNull(TripPage.trackableFilter(true, state(filterA, Context.StopDetails)))
        assertNull(TripPage.trackableFilter(true, state(null, Context.TripDetails)))
    }

    @Test
    fun `filter is not returned for a vehicle with no trip data`() {
        assertNull(
            TripPage.trackableFilter(true, state(filterA, Context.TripDetails, tripLoaded = false)),
        )
    }

    @Test
    fun `overview and stop selected are not the trip page`() {
        // The trip details context stays TripDetails after the trip page closes.
        val staleContext = state(filterA, Context.TripDetails)
        assertFalse(
            TripPage.isOpen(
                MapViewModel.State(MapViewModel.LayerState.Overview, layersInitialized = true),
                staleContext,
            ),
        )
        assertFalse(
            TripPage.isOpen(
                MapViewModel.State(
                    MapViewModel.LayerState.StopSelected(objects.stop { id = "place-pktrm" }, null),
                    layersInitialized = true,
                ),
                staleContext,
            ),
        )
    }

    private val overview =
        MapViewModel.State(MapViewModel.LayerState.Overview, layersInitialized = true)

    /** Stop details' focus on [filterA]'s trip and vehicle. */
    private val focusA = TripDetailsFilter(filterA.tripId, filterA.vehicleId, filterA.stopSequence)

    private fun stopDetails(stopId: String, focus: TripDetailsFilter? = null) =
        StopDetailsViewModel.State(
            routeData =
                StopDetailsViewModel.RouteData.Unfiltered(
                    StopDetailsPageFilters(stopId, null, focus),
                    emptyList(),
                ),
        )

    @Test
    fun `overlay page hides stale trip and stop details state on the entry screen`() {
        val page =
            OverlayPage.from(
                overview,
                state(filterA, Context.TripDetails),
                stopDetails("place-pktrm"),
            )

        assertEquals(
            OverlayPage(
                tripPageOpen = false,
                trackableFilter = null,
                pageTripId = null,
                pageTargetStopId = null,
                pageRouteId = null,
                isStopDetails = false,
                stopDetailsStopId = null,
                onEntryScreen = true,
            ),
            page,
        )
    }

    @Test
    fun `overlay page reads the stop of open stop details`() {
        val map =
            MapViewModel.State(
                MapViewModel.LayerState.StopSelected(objects.stop { id = "place-pktrm" }, null),
                layersInitialized = true,
            )
        val page =
            OverlayPage.from(map, state(filterA, Context.StopDetails), stopDetails("place-pktrm"))

        assertFalse(page.tripPageOpen)
        assertNull(page.pageTripId)
        assertTrue(page.isStopDetails)
        assertEquals("place-pktrm", page.stopDetailsStopId)
        assertFalse(page.onEntryScreen)
    }

    private val focusedOnA = stopDetails("place-pktrm", focusA)

    @Test
    fun `stop details offers the selected trip whatever its vehicle is doing`() {
        // At a bus origin: no vehicle yet, or the bus is still on its previous trip.
        assertEquals(
            filterA,
            StationPage.trackableFilter(true, state(filterA, Context.StopDetails), focusedOnA),
        )
        assertEquals(
            filterA,
            StationPage.trackableFilter(
                true,
                state(filterA, Context.StopDetails, vehicleTripId = "previousTrip"),
                focusedOnA,
            ),
        )
        // The vehicle is on the trip, so upstream also shows Follow.
        assertEquals(
            filterA,
            StationPage.trackableFilter(
                true,
                state(filterA, Context.StopDetails, vehicleTripId = filterA.tripId),
                focusedOnA,
            ),
        )
    }

    @Test
    fun `stop details tracks the focused vehicle`() {
        val filter =
            StationPage.trackableFilter(
                true,
                state(filterA, Context.StopDetails, vehicleTripId = filterA.tripId),
                focusedOnA,
            )
        assertEquals("v1", filter?.vehicleId)
        assertEquals("tripA", filter?.tripId)
    }

    @Test
    fun `stop details offers nothing while the trip model still holds another selection`() {
        // The user picked another departure, and the trip model hasn't caught up yet.
        val otherTrip = stopDetails("place-pktrm", TripDetailsFilter("tripB", "v2", 40))
        assertNull(
            StationPage.trackableFilter(true, state(filterA, Context.StopDetails), otherTrip),
        )
        // Same trip, another vehicle tapped on the map.
        val otherVehicle =
            stopDetails("place-pktrm", TripDetailsFilter("tripA", "v2", 30, selectionLock = true))
        assertNull(
            StationPage.trackableFilter(true, state(filterA, Context.StopDetails), otherVehicle),
        )
    }

    @Test
    fun `stop details offers nothing off stop details or before the trip loads`() {
        val tripModel = state(filterA, Context.StopDetails)
        assertNull(StationPage.trackableFilter(false, tripModel, focusedOnA))
        assertNull(
            StationPage.trackableFilter(true, state(filterA, Context.TripDetails), focusedOnA),
        )
        assertNull(
            StationPage.trackableFilter(true, state(null, Context.StopDetails), focusedOnA),
        )
        assertNull(
            StationPage.trackableFilter(
                true,
                state(filterA, Context.StopDetails, tripLoaded = false),
                focusedOnA,
            ),
        )
        // No trip selected on stop details.
        assertNull(StationPage.trackableFilter(true, tripModel, stopDetails("place-pktrm")))
    }

    @Test
    fun `overlay page offers Track on stop details without opening the trip page`() {
        val trip = objects.trip {
            id = "tripA"
            routeId = "47"
            directionId = 0
        }
        val tripState =
            TripDetailsViewModel.State(
                tripData = TripData(filterA, trip, null, null, null),
                context = Context.StopDetails,
            )
        val map =
            MapViewModel.State(
                MapViewModel.LayerState.StopSelected(objects.stop { id = "place-pktrm" }, null),
                layersInitialized = true,
            )
        val page = OverlayPage.from(map, tripState, focusedOnA)

        assertEquals(filterA, page.trackableFilter)
        assertEquals(Route.Id("47"), page.pageRouteId)
        assertFalse(page.tripPageOpen)
        assertNull(page.pageTripId)
        assertNull(page.pageTargetStopId)
    }

    @Test
    fun `overlay page is equal across prediction updates`() {
        // derivedStateOf skips recomposition when the derived value is equal.
        val before =
            OverlayPage.from(overview, state(filterA, Context.TripDetails), stopDetails("a"))
        val after =
            OverlayPage.from(overview, state(filterA, Context.TripDetails), stopDetails("a"))
        assertEquals(before, after)
    }
}
