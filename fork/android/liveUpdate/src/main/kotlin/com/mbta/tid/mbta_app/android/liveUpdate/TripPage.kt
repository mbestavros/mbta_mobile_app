package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.Route
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
    ): Boolean = isOpen(isStopDetails, stopDetailsStopId(stopDetailsState), stationIds, global)

    /** Like the other [isOpen], with the page's stop ID already read (see [stopDetailsStopId]). */
    fun isOpen(
        isStopDetails: Boolean,
        stopDetailsStopId: String?,
        stationIds: Set<String>,
        global: GlobalResponse?,
    ): Boolean {
        if (!isStopDetails) return false
        val stopId = stopDetailsStopId ?: return false
        return LiveUpdateDeepLink.stationId(stopId, global) in stationIds
    }

    /** The stop of the stop details page, or null until its route data loads. */
    fun stopDetailsStopId(stopDetailsState: StopDetailsViewModel.State): String? =
        stopDetailsState.routeData?.filters?.stopId

    /**
     * The filter of the trip selected on stop details, so the overlay can offer "Track" on the
     * departures without a trip page. This also covers a trip at its origin, where upstream shows
     * no Follow button.
     *
     * The filter must match stop details' focus (its trip and vehicle, including a vehicle tapped
     * on the map). The trip details model updates in an effect, so just after the user picks
     * another departure it can still hold the previous trip. Null until the focused trip loads. The
     * result equals the filter that upstream's Follow opens the trip page with.
     */
    fun trackableFilter(
        isStopDetails: Boolean,
        tripState: TripDetailsViewModel.State,
        stopDetailsState: StopDetailsViewModel.State,
    ): TripDetailsPageFilter? {
        if (!isStopDetails || tripState.context != TripDetailsViewModel.Context.StopDetails) {
            return null
        }
        val focused = stopDetailsState.routeData?.filters?.tripFilter ?: return null
        val tripData = tripState.tripData ?: return null
        return tripData.tripFilter.takeIf {
            tripData.trip?.id == it.tripId &&
                it.tripId == focused.tripId &&
                it.vehicleId == focused.vehicleId
        }
    }

    /** The boarding station and, with a destination, the destination station. */
    fun trackedStationIds(request: LiveUpdateRequest, global: GlobalResponse?): Set<String> =
        listOfNotNull(request.stopId, request.destinationStopId)
            .map { LiveUpdateDeepLink.stationId(it, global) }
            .toSet()
}

/**
 * What the overlay needs from upstream's page view models. The trip and stop details models change
 * on every prediction, but these few values rarely do. The overlay reads the models only through
 * this class (in derivedStateOf), so a prediction push recomposes it only when a value here
 * changes.
 *
 * The trip page fields are null while the trip page is closed, and [stopDetailsStopId] is null
 * while stop details is closed.
 */
internal data class OverlayPage(
    val tripPageOpen: Boolean,
    /**
     * The filter to offer "Track" for: the trip page's (see [TripPage.trackableFilter]), or the
     * trip selected on stop details (see [StationPage.trackableFilter]).
     */
    val trackableFilter: TripDetailsPageFilter?,
    val pageTripId: String?,
    /** The stop that the trip page targets. */
    val pageTargetStopId: String?,
    /** The route of [trackableFilter]'s trip, for the Track pill's badge. */
    val pageRouteId: Route.Id?,
    val isStopDetails: Boolean,
    val stopDetailsStopId: String?,
    /** The map entry screen (or the More page, which looks the same here). */
    val onEntryScreen: Boolean,
) {
    companion object {
        fun from(
            mapState: MapViewModel.State,
            tripState: TripDetailsViewModel.State,
            stopDetailsState: StopDetailsViewModel.State,
        ): OverlayPage {
            val tripPageOpen = TripPage.isOpen(mapState, tripState)
            val tripData = tripState.tripData?.takeIf { tripPageOpen }
            val isStopDetails = StationPage.isStopDetails(mapState, tripState)
            val stopDetailsFilter =
                if (tripPageOpen) null
                else StationPage.trackableFilter(isStopDetails, tripState, stopDetailsState)
            val tripPageRouteId = tripData?.trip?.routeId
            val stopDetailsRouteId =
                if (stopDetailsFilter != null) tripState.tripData?.trip?.routeId else null
            return OverlayPage(
                tripPageOpen = tripPageOpen,
                trackableFilter =
                    TripPage.trackableFilter(tripPageOpen, tripState) ?: stopDetailsFilter,
                pageTripId = tripData?.tripFilter?.tripId,
                pageTargetStopId = tripData?.tripFilter?.stopId,
                pageRouteId = tripPageRouteId ?: stopDetailsRouteId,
                isStopDetails = isStopDetails,
                stopDetailsStopId =
                    if (isStopDetails) StationPage.stopDetailsStopId(stopDetailsState) else null,
                onEntryScreen = mapState.layerState is MapViewModel.LayerState.Overview,
            )
        }
    }
}
