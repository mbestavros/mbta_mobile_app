package com.mbta.tid.mbta_app.android.liveUpdate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mbta.tid.mbta_app.android.ModalRoutes
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.alertDetails.AlertDetailsPage
import com.mbta.tid.mbta_app.android.component.ChannelDebugView
import com.mbta.tid.mbta_app.android.component.DragHandle
import com.mbta.tid.mbta_app.android.component.ErrorBanner
import com.mbta.tid.mbta_app.android.component.ScrollSeparatorColumn
import com.mbta.tid.mbta_app.android.component.sheet.BottomSheetScaffold
import com.mbta.tid.mbta_app.android.component.sheet.SheetValue
import com.mbta.tid.mbta_app.android.component.sheet.rememberBottomSheetScaffoldState
import com.mbta.tid.mbta_app.android.component.sheet.rememberStandardBottomSheetState
import com.mbta.tid.mbta_app.android.pages.ExplainerPage
import com.mbta.tid.mbta_app.android.state.getGlobalData
import com.mbta.tid.mbta_app.android.state.subscribeToAlerts
import com.mbta.tid.mbta_app.android.stopDetails.TripDetailsView
import com.mbta.tid.mbta_app.android.stopDetails.TripRouteAccents
import com.mbta.tid.mbta_app.android.tripDetails.TripDetailsPageHeader
import com.mbta.tid.mbta_app.android.util.IsLoadingSheetContents
import com.mbta.tid.mbta_app.android.util.SettingsCache
import com.mbta.tid.mbta_app.android.util.fromHex
import com.mbta.tid.mbta_app.android.util.timer
import com.mbta.tid.mbta_app.model.Alert
import com.mbta.tid.mbta_app.model.Direction
import com.mbta.tid.mbta_app.model.ObjectCollectionBuilder
import com.mbta.tid.mbta_app.model.Route
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.response.AlertsStreamDataResponse
import com.mbta.tid.mbta_app.network.PhoenixSocket
import com.mbta.tid.mbta_app.repositories.ErrorKey
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.repositories.Settings
import com.mbta.tid.mbta_app.routes.SheetRoutes
import com.mbta.tid.mbta_app.utils.NavigationCallbacks
import com.mbta.tid.mbta_app.viewModel.IErrorBannerViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsPageViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsPageViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import org.koin.compose.currentKoinScope
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * The trip page of [LiveTripActivity] for the trip it was opened for. While that trip is tracked,
 * the page follows the session: a new destination retargets it, and a new vehicle on the trip is
 * followed.
 */
@Composable
internal fun LiveTripRoute(
    request: LiveUpdateRequest,
    vehicleId: String?,
    onBack: () -> Unit,
    onOpenStop: (String) -> Unit,
    controller: LiveUpdateController = koinInject(),
    globalRepository: IGlobalRepository = koinInject(),
) {
    val session by controller.session.collectAsState()
    val global by globalRepository.state.collectAsState()
    val tracked = session?.takeIf { it.request.tripId == request.tripId }
    val currentRequest = tracked?.request ?: request

    // Keep the last vehicle known: a vehicle missing from one update must not reload the page.
    var currentVehicleId by remember(request.tripId) { mutableStateOf(vehicleId) }
    val latestVehicleId = tracked?.let { LiveUpdateDeepLink.vehicleId(it.request, it.snapshot) }
    LaunchedEffect(latestVehicleId) { latestVehicleId?.let { currentVehicleId = it } }

    // The target is a station, which needs the global data. Wait for it, so the page loads once.
    val filter =
        remember(currentRequest, currentVehicleId, global) {
            global?.let { LiveUpdateDeepLink.tripPageFilter(currentRequest, currentVehicleId, it) }
        }

    val (tripDetailsVM, tripDetailsPageVM) = rememberLiveTripViewModels()
    // Upstream's "Hide maps" setting shows the list alone, as on upstream's pages.
    val mapVM = if (SettingsCache.get(Settings.HideMaps)) null else rememberLiveTripMapViewModel()
    LiveTripScreen(
        filter,
        onBack = onBack,
        onOpenStop = onOpenStop,
        tripDetailsVM = tripDetailsVM,
        tripDetailsPageVM = tripDetailsPageVM,
        map =
            mapVM?.let { vm ->
                { sheetPadding, alerts ->
                    LiveTripMap(filter, sheetPadding, alerts, tripDetailsVM, vm, onOpenStop)
                }
            },
        pill = { LiveTripPill(request.tripId, controller) },
    )
}

/**
 * This page's own trip view models. Upstream's are Koin singletons, which MainActivity's trip page
 * and stop details also use. If this page set their filter, upstream's trip page would show this
 * trip when the rider goes back to it. These live in this activity's ViewModelStore, so they stop
 * when it finishes.
 */
@Composable
internal fun rememberLiveTripViewModels(): Pair<ITripDetailsViewModel, ITripDetailsPageViewModel> {
    // The scope koinInject uses.
    val koin = currentKoinScope()
    val tripDetailsVM =
        viewModel(key = "liveTrip.tripDetails") {
            TripDetailsViewModel(
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(named("coroutineDispatcherIO")),
            )
        }
    val tripDetailsPageVM =
        viewModel(key = "liveTrip.tripDetailsPage") { TripDetailsPageViewModel(tripDetailsVM) }
    return tripDetailsVM to tripDetailsPageVM
}

/**
 * Upstream's trip page (`TripDetailsPage`), rebuilt around upstream's `TripDetailsView` with this
 * page's own view models. `TripDetailsPage` takes a trip details view model, but its
 * `TripDetailsView` uses the Koin singleton, so it can't be reused as a whole.
 *
 * With a [map], the page is a sheet over it, in upstream's sheet. Without one, it fills the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTripScreen(
    filter: TripDetailsPageFilter?,
    onBack: () -> Unit,
    onOpenStop: (String) -> Unit,
    tripDetailsVM: ITripDetailsViewModel,
    tripDetailsPageVM: ITripDetailsPageViewModel,
    errorBannerViewModel: IErrorBannerViewModel = koinInject(),
    socket: PhoenixSocket = koinInject(),
    allAlerts: AlertsStreamDataResponse? = subscribeToAlerts(),
    map: (@Composable (sheetPadding: PaddingValues, AlertsStreamDataResponse?) -> Unit)? = null,
    pill: @Composable () -> Unit = {},
) {
    val now by timer(updateInterval = 5.seconds)
    val global = getGlobalData(ErrorKey(setOf(SheetRoutes.TripDetails::class), "LiveTripScreen"))
    val pageState by tripDetailsPageVM.models.collectAsState()
    val tripState by tripDetailsVM.models.collectAsState()

    // MainActivity's ContentView opens the app's socket only while MainActivity is resumed. Open it
    // while this page is resumed in the same way. MainActivity pauses before this page resumes,
    // and the other way around, so the two never fight over it.
    LifecycleResumeEffect(socket) {
        socket.attach()
        onPauseOrDispose { socket.detach() }
    }
    // The error banner shows only errors for the current page.
    LifecycleResumeEffect(filter) {
        errorBannerViewModel.setSheetRoute(filter?.let { SheetRoutes.TripDetails(it) })
        onPauseOrDispose {}
    }
    LaunchedEffect(allAlerts) { tripDetailsPageVM.setAlerts(allAlerts) }
    LaunchedEffect(filter) { tripDetailsPageVM.setFilter(filter) }
    LaunchedEffect(filter, tripState) {
        tripDetailsPageVM.setContextualTrip(
            filter?.let { tripState.stopList?.contextualTrip(it, global) },
        )
    }
    LaunchedEffect(tripState.awaitingPredictionsAfterBackground) {
        errorBannerViewModel.setIsLoadingWhenPredictionsStale(
            tripState.awaitingPredictionsAfterBackground,
        )
    }

    val route = global?.getRoute(pageState.trip?.routeId ?: filter?.routeId as? Route.Id)
    val accents = route?.let { TripRouteAccents(it) } ?: TripRouteAccents.default
    val direction = pageState.direction
    val navCallbacks =
        NavigationCallbacks(
            onBack = onBack,
            onClose = onBack,
            backButtonPresentation = NavigationCallbacks.BackButtonPresentation.Header,
        )
    var modal by remember { mutableStateOf<ModalRoutes?>(null) }

    fun openAlertDetails(alert: Alert) {
        modal =
            ModalRoutes.AlertDetails(
                alertId = alert.id,
                lineId = null,
                routeIds = listOfNotNull(route?.id),
                stopId = filter?.stopId,
            )
    }

    val pageColor = route?.color?.let { Color.fromHex(it) } ?: colorResource(R.color.fill2)

    @Composable
    fun Page(topInset: Modifier) {
        Column(
            Modifier.fillMaxSize().background(pageColor).then(topInset).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (route != null && direction != null) {
                TripDetailsPageHeader(route, direction, navCallbacks)
            } else {
                CompositionLocalProvider(IsLoadingSheetContents provides true) {
                    TripDetailsPageHeader(
                        route ?: ObjectCollectionBuilder.Single.route(),
                        Direction(
                            stringResource(R.string.loading),
                            stringResource(R.string.loading),
                            0,
                        ),
                        navCallbacks,
                    )
                }
            }
            ErrorBanner(errorBannerViewModel, Modifier.padding(bottom = 8.dp))
            ChannelDebugView()
            ScrollSeparatorColumn {
                TripDetailsView(
                    filter,
                    allAlerts = allAlerts,
                    onOpenAlertDetails = ::openAlertDetails,
                    openSheetRoute = { route -> openedStopId(route)?.let(onOpenStop) },
                    openModal = { modal = it },
                    now = now,
                    routeAccents = accents,
                    isTripDetailsPage = true,
                    // Room below the last stop for the pill.
                    modifier =
                        Modifier.padding(horizontal = 10.dp)
                            .navigationBarsPadding()
                            .padding(bottom = PILL_CLEARANCE),
                    tripDetailsVM = tripDetailsVM,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (map == null) {
            Page(Modifier.statusBarsPadding())
        } else {
            val scaffoldState =
                rememberBottomSheetScaffoldState(
                    rememberStandardBottomSheetState(SheetValue.Medium),
                )
            val sheetValue = scaffoldState.bottomSheetState.currentValue
            BottomSheetScaffold(
                sheetContent = {
                    // As upstream's sheet: only the full sheet reaches the status bar.
                    Page(
                        if (sheetValue == SheetValue.Large) Modifier.statusBarsPadding()
                        else Modifier,
                    )
                },
                sheetDragHandle = { DragHandle() },
                sheetContainerColor = pageColor,
                scaffoldState = scaffoldState,
            ) { sheetPadding ->
                map(sheetPadding, allAlerts)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { pill() }
    }

    modal?.let { current ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        fun close() {
            scope.launch { sheetState.hide() }.invokeOnCompletion { modal = null }
        }
        ModalBottomSheet(
            onDismissRequest = { modal = null },
            modifier = Modifier.fillMaxSize(),
            sheetState = sheetState,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    is ModalRoutes.AlertDetails ->
                        AlertDetailsPage(
                            current.alertId,
                            current.lineId,
                            current.routeIds,
                            current.stopId,
                            allAlerts,
                            goBack = ::close,
                        )
                    is ModalRoutes.Explainer ->
                        ExplainerPage(current.type, current.routeAccents, goBack = ::close)
                    // The trip page doesn't save favorites.
                    is ModalRoutes.SaveFavorite -> LaunchedEffect(Unit) { modal = null }
                }
            }
        }
    }
}

private val PILL_CLEARANCE = 72.dp

/**
 * The stop that a tap in the trip list opens, or null. Upstream opens stop details for a tapped
 * stop. This page sends the stop to MainActivity, which owns stop details.
 */
internal fun openedStopId(route: SheetRoutes): String? = (route as? SheetRoutes.StopDetails)?.stopId
