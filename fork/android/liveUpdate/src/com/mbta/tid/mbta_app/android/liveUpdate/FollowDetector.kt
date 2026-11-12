package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Detects a Follow tap without changes to upstream UI code.
 *
 * The map view model is in the "trip selected, following" state exactly when the trip page
 * (SheetRoutes.TripDetails) is open, and the trip details view model holds the filter of that page.
 */
object FollowDetector {
    fun isFollowingPage(mapState: MapViewModel.State): Boolean =
        (mapState.layerState as? MapViewModel.LayerState.TripSelected)?.following == true

    fun followedFilter(
        isFollowingPage: Boolean,
        tripState: TripDetailsViewModel.State,
    ): TripDetailsPageFilter? =
        if (isFollowingPage && tripState.context == TripDetailsViewModel.Context.TripDetails) {
            tripState.tripData?.tripFilter
        } else {
            null
        }

    /** Emits the filter each time the user opens the trip page for a trip. */
    fun follows(
        isFollowingPage: Flow<Boolean>,
        tripStates: Flow<TripDetailsViewModel.State>,
    ): Flow<TripDetailsPageFilter> =
        combine(isFollowingPage, tripStates, ::followedFilter)
            .distinctUntilChanged()
            .filterNotNull()

    fun followsFromViewModels(
        mapStates: Flow<MapViewModel.State>,
        tripStates: Flow<TripDetailsViewModel.State>,
    ): Flow<TripDetailsPageFilter> = follows(mapStates.map(::isFollowingPage), tripStates)
}
