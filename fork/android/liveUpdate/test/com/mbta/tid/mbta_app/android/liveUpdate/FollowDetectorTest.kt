package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.stopDetailsPage.TripData
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FollowDetectorTest {
    private val filterA = filter("tripA")
    private val filterB = filter("tripB")

    private fun filter(tripId: String) =
        TripDetailsPageFilter(tripId, "v1", Route.Id("Red"), 0, "place-pktrm", 30)

    private val objects = ObjectCollectionBuilder("FollowDetectorTest")

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
        assertEquals(
            filterA,
            FollowDetector.followedFilter(true, state(filterA, Context.TripDetails)),
        )
        assertNull(FollowDetector.followedFilter(false, state(filterA, Context.TripDetails)))
        assertNull(FollowDetector.followedFilter(true, state(filterA, Context.StopDetails)))
        assertNull(FollowDetector.followedFilter(true, state(null, Context.TripDetails)))
    }

    @Test
    fun `filter is not returned for a vehicle with no trip data`() {
        assertNull(
            FollowDetector.followedFilter(
                true,
                state(filterA, Context.TripDetails, tripLoaded = false),
            ),
        )
    }

    @Test
    fun `overview map state is not the trip page`() {
        assertFalse(
            FollowDetector.isFollowingPage(
                MapViewModel.State(MapViewModel.LayerState.Overview, layersInitialized = true),
            ),
        )
    }

    @Test
    fun `emits once each time the trip page opens`() = runBlocking {
        val following = MutableStateFlow(false)
        val trip = MutableStateFlow(state(filterA, Context.StopDetails))
        val results = mutableListOf<TripDetailsPageFilter>()
        val job =
            launch(Dispatchers.Default) {
                FollowDetector.follows(following, trip).collect { results += it }
            }

        suspend fun step(block: () -> Unit) {
            block()
            delay(50)
        }

        step {}
        step { trip.value = state(filterA, Context.TripDetails) }
        step { following.value = true }
        // A new state for the same trip does not emit again.
        step { trip.value = trip.value.copy(awaitingPredictionsAfterBackground = true) }
        step { following.value = false }
        step { following.value = true }
        step { trip.value = state(filterB, Context.TripDetails) }
        job.cancel()

        assertEquals(listOf(filterA, filterA, filterB), results)
    }
}
