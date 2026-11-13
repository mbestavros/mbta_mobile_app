package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.stopDetailsPage.TripData
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `overview map state is not the trip page`() {
        assertFalse(
            TripPage.isOpen(
                MapViewModel.State(MapViewModel.LayerState.Overview, layersInitialized = true),
            ),
        )
    }
}
