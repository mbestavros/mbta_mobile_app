package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.StopDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel

/**
 * Finds the open trip page without changes to upstream UI code.
 *
 * The map view model is in "trip selected" on the trip page (SheetRoutes.TripDetails) and on stop
 * details with a trip selected. The trip details view model's context tells them apart: upstream's
 * TripDetailsView sets it when either page opens, and it holds the filter of that page.
 *
 * Don't use `TripSelected.following`. It turns false on the trip page when the user moves the map.
 */
object TripPage {
    fun isOpen(mapState: MapViewModel.State, tripState: TripDetailsViewModel.State): Boolean =
        mapState.layerState is MapViewModel.LayerState.TripSelected &&
            tripState.context == TripDetailsViewModel.Context.TripDetails

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

/**
 * Finds an open stop details page (SheetRoutes.StopDetails) without changes to upstream UI code.
 *
 * The map view model is in "stop selected", or "trip selected" off the trip page, exactly when stop
 * details is open. The stop details view model keeps its last filters after the page closes, so
 * both are needed.
 */
object StationPage {
    fun isStopDetails(
        mapState: MapViewModel.State,
        tripState: TripDetailsViewModel.State,
    ): Boolean =
        when (mapState.layerState) {
            is MapViewModel.LayerState.StopSelected -> true
            is MapViewModel.LayerState.TripSelected -> !TripPage.isOpen(mapState, tripState)
            MapViewModel.LayerState.Overview -> false
        }

    /**
     * True when stop details is open at one of [stationIds]. The page's stop resolves to its
     * station first. False until stop details loads its route data.
     */
    fun isOpen(
        isStopDetails: Boolean,
        stopDetailsState: StopDetailsViewModel.State,
        stationIds: Set<String>,
        global: GlobalResponse?,
    ): Boolean {
        if (!isStopDetails) return false
        val stopId = stopDetailsState.routeData?.filters?.stopId ?: return false
        return LiveUpdateDeepLink.stationId(stopId, global) in stationIds
    }

    /** The boarding station and, with a destination, the destination station. */
    fun trackedStationIds(request: LiveUpdateRequest, global: GlobalResponse?): Set<String> =
        listOfNotNull(request.stopId, request.destinationStopId)
            .map { LiveUpdateDeepLink.stationId(it, global) }
            .toSet()
}
