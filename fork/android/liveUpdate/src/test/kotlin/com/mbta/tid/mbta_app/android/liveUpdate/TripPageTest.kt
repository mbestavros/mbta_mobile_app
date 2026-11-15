package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.StopDetailsPageFilters
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
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
    ) =
        TripDetailsViewModel.State(
            tripData =
                filter?.let {
                    val trip = if (tripLoaded) objects.trip { id = it.tripId } else null
                    TripData(it, trip, null, null, null)
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

    private fun stopDetails(stopId: String) =
        StopDetailsViewModel.State(
            routeData =
                StopDetailsViewModel.RouteData.Unfiltered(
                    StopDetailsPageFilters(stopId, null, null),
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
