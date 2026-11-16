package com.mbta.tid.mbta_app.android.liveUpdate

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mbta.tid.mbta_app.android.location.LocationDataManager
import com.mbta.tid.mbta_app.android.location.ViewportProvider
import com.mbta.tid.mbta_app.android.map.HomeMapView
import com.mbta.tid.mbta_app.android.map.ManageMapboxConfig
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.response.AlertsStreamDataResponse
import com.mbta.tid.mbta_app.routes.SheetRoutes
import com.mbta.tid.mbta_app.utils.NavigationCallbacks
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import org.koin.compose.currentKoinScope
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/**
 * This page's own map view model. Upstream's is a Koin singleton that MainActivity's map uses. If
 * this page moved it to this trip, MainActivity's map would show this trip when the rider goes
 * back. It lives in this activity's ViewModelStore, so it stops when the activity finishes.
 */
@Composable
internal fun rememberLiveTripMapViewModel(): IMapViewModel {
    // The scope koinInject uses.
    val koin = currentKoinScope()
    return viewModel(key = "liveTrip.map") { koin.newLiveTripMapViewModel() }
}

/**
 * A map view model with the dependencies upstream's Koin module gives its singleton. The qualifiers
 * are checked against upstream's real module by `LiveTripKoinContractTest`.
 */
internal fun Scope.newLiveTripMapViewModel(): MapViewModel =
    MapViewModel(
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(),
        get(named("coroutineDispatcherDefault")),
        get(named("coroutineDispatcherIO")),
    )

/**
 * Upstream's map for the trip page, with this page's view models. Upstream's `ContentView` and
 * `MapAndSheetPage` connect `HomeMapView` to its view models. This does the same for one trip:
 * - the viewport, as `ContentView` sets it up
 * - the trip details view model's map updates (the trip's vehicle and stops), as `MapAndSheetPage`
 *   forwards them
 * - the alerts, for the stop pins
 *
 * It shows only the trip's vehicle, so it opens no vehicles channel of its own. It never starts
 * location updates: upstream's trip page hides the rider's location. A tap on a stop pin opens that
 * stop's details, as a tap in the list does.
 *
 * Two differences from `MapAndSheetPage`, both because this page only ever shows a trip:
 * - It forwards the selected vehicle whatever the filter. Upstream forwards it only while a trip
 *   filter is set.
 * - It follows the vehicle when the viewport is in vehicle overview. Upstream also checks that the
 *   trip page is the current page.
 *
 * As upstream, it reads the map updates as state, so of two updates in one frame only the second
 * reaches the map.
 */
@Composable
internal fun LiveTripMap(
    filter: TripDetailsPageFilter?,
    sheetPadding: PaddingValues,
    allAlerts: AlertsStreamDataResponse?,
    tripDetailsVM: ITripDetailsViewModel,
    mapVM: IMapViewModel,
    onOpenStop: (String) -> Unit,
) {
    // MainActivity loads the Mapbox token. This page can open in a new process before it does.
    ManageMapboxConfig()

    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            center(ViewportProvider.Companion.Defaults.center)
            zoom(ViewportProvider.Companion.Defaults.zoom)
            pitch(0.0)
            bearing(0.0)
        }
    }
    val viewportProvider = remember(mapViewportState) { ViewportProvider(mapViewportState) }
    // Set during composition: HomeMapView's first navChanged moves the viewport, and the view model
    // outlives a rotation, which makes a new viewport.
    remember(mapVM, viewportProvider) {
        mapVM.setViewportManager(viewportProvider)
        Unit
    }
    val locationDataManager = remember { LocationDataManager() }
    val isTargetingState = remember { mutableStateOf(false) }

    LaunchedEffect(allAlerts) { mapVM.alertsChanged(allAlerts) }
    val mapUpdate by tripDetailsVM.mapUpdates.collectAsStateWithLifecycle(null)
    LaunchedEffect(mapUpdate) {
        when (val update = mapUpdate) {
            is TripDetailsViewModel.MapUpdate.SelectedVehicle ->
                mapVM.selectedVehicleUpdated(update.vehicle, viewportProvider.isVehicleOverview)
            is TripDetailsViewModel.MapUpdate.TripStops -> mapVM.tripStopsUpdated(update.tripStops)
            else -> {}
        }
    }

    HomeMapView(
        sheetPadding = sheetPadding,
        lastLoadedLocation = null,
        isTargetingState = isTargetingState,
        locationDataManager = locationDataManager,
        viewportProvider = viewportProvider,
        currentNavEntry = filter?.let { SheetRoutes.TripDetails(it) },
        handleStopNavigation = onOpenStop,
        handleVehicleTap = {},
        vehiclesData = emptyList(),
        viewModel = mapVM,
        // The page header has the back button.
        navCallbacks =
            NavigationCallbacks(
                onBack = null,
                onClose = null,
                backButtonPresentation = NavigationCallbacks.BackButtonPresentation.Header,
            ),
    )
}
