package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel

/**
 * Finds the open trip page without changes to upstream UI code.
 *
 * The map view model is in the "trip selected, following" state exactly when the trip page
 * (SheetRoutes.TripDetails) is open, and the trip details view model holds the filter of that page.
 */
object TripPage {
    fun isOpen(mapState: MapViewModel.State): Boolean =
        (mapState.layerState as? MapViewModel.LayerState.TripSelected)?.following == true

    /**
     * The filter of the open trip page. Null until the trip loads. The page can show a vehicle with
     * no trip data ("not available"), and the service can't track that.
     */
    fun trackableFilter(
        isOpen: Boolean,
        tripState: TripDetailsViewModel.State,
    ): TripDetailsPageFilter? {
        if (!isOpen || tripState.context != TripDetailsViewModel.Context.TripDetails) return null
        val tripData = tripState.tripData ?: return null
        return tripData.tripFilter.takeIf { tripData.trip?.id == it.tripId }
    }
}
