package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.mbta.tid.mbta_app.analytics.Analytics
import com.mbta.tid.mbta_app.analytics.MockAnalytics
import com.mbta.tid.mbta_app.android.MyApplicationTheme
import com.mbta.tid.mbta_app.android.map.IMapboxConfigManager
import com.mbta.tid.mbta_app.android.util.SettingsCache
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.repositories.IErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.repositories.IRailRouteShapeRepository
import com.mbta.tid.mbta_app.repositories.ISentryRepository
import com.mbta.tid.mbta_app.repositories.IStopRepository
import com.mbta.tid.mbta_app.repositories.ITripRepository
import com.mbta.tid.mbta_app.repositories.MockErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.MockGlobalRepository
import com.mbta.tid.mbta_app.repositories.MockRailRouteShapeRepository
import com.mbta.tid.mbta_app.repositories.MockSentryRepository
import com.mbta.tid.mbta_app.repositories.MockSettingsRepository
import com.mbta.tid.mbta_app.repositories.MockStopRepository
import com.mbta.tid.mbta_app.repositories.MockTripRepository
import com.mbta.tid.mbta_app.routes.SheetRoutes
import com.mbta.tid.mbta_app.utils.EasternTimeInstant
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.IRouteCardDataViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.MockMapViewModel
import com.mbta.tid.mbta_app.viewModel.MockRouteCardDataViewModel
import com.mbta.tid.mbta_app.viewModel.MockTripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the fork's map glue with upstream's real `HomeMapView`. Without a Mapbox config,
 * `HomeMapView` draws a placeholder grid in place of Mapbox, so this runs on Robolectric.
 */
// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveTripMapTest {
    @get:Rule val compose = createComposeRule()

    private val f = LiveUpdateFixtures()
    private val filter = TripDetailsPageFilter(f.trip.id, "v1", f.route.id, 0, f.stopC.id, 30)
    private val sharedMapVM = MockMapViewModel()

    /** Never loads a config, so `HomeMapView` doesn't start Mapbox. */
    private class NoMapboxConfig : IMapboxConfigManager {
        var loads = 0
        override val configLoadAttempted: StateFlow<Boolean> = MutableStateFlow(false)
        override var lastMapboxErrorTimestamp: Flow<EasternTimeInstant?> = MutableStateFlow(null)

        override suspend fun loadConfig() {
            loads++
        }
    }

    private val config = NoMapboxConfig()

    @Before
    fun setUpKoin() {
        startKoin {
            modules(
                module {
                    single<IGlobalRepository> { MockGlobalRepository(f.global) }
                    single<IErrorBannerStateRepository> { MockErrorBannerStateRepository() }
                    single { SettingsCache(MockSettingsRepository()) }
                    single<Analytics> { MockAnalytics() }
                    single<IMapboxConfigManager> { config }
                    single<Clock> { Clock.System }
                    single<IRouteCardDataViewModel> { MockRouteCardDataViewModel() }
                    single<IRailRouteShapeRepository> { MockRailRouteShapeRepository() }
                    single<ISentryRepository> { MockSentryRepository() }
                    single<IStopRepository> { MockStopRepository() }
                    factory<ITripRepository> { MockTripRepository() }
                    single(named("coroutineDispatcherDefault")) { Dispatchers.Unconfined }
                    single(named("coroutineDispatcherIO")) { Dispatchers.Unconfined }
                    single<IMapViewModel> { sharedMapVM }
                },
            )
        }
    }

    @After fun tearDownKoin() = stopKoin()

    private class Calls {
        val navEntries = mutableListOf<SheetRoutes?>()
        val vehicles = mutableListOf<Pair<Vehicle?, Boolean>>()
        val tripStops = mutableListOf<List<String>?>()
        var viewportManagers = 0
        var alertUpdates = 0
        val permissions = mutableListOf<Boolean>()
    }

    @Test
    fun `the map shows the trip and gets the trip's vehicle and stops`() {
        val calls = Calls()
        val mapVM =
            MockMapViewModel().apply {
                onNavChanged = { calls.navEntries += it }
                onSelectedVehicleUpdated = { vehicle, follow ->
                    calls.vehicles += vehicle to follow
                }
                onTripStopsUpdated = { calls.tripStops += it }
                onSetViewportManager = { calls.viewportManagers++ }
                onAlertsChanged = { calls.alertUpdates++ }
                onLocationPermissionsChanged = { calls.permissions += it }
            }
        // Replays the latest update, so the test can send one without suspending.
        val updates = MutableSharedFlow<TripDetailsViewModel.MapUpdate>(replay = 1)
        val tripDetailsVM =
            object : ITripDetailsViewModel by MockTripDetailsViewModel() {
                override val mapUpdates = updates
            }

        compose.setContent {
            MyApplicationTheme {
                LiveTripMap(
                    filter,
                    PaddingValues(),
                    allAlerts = null,
                    tripDetailsVM = tripDetailsVM,
                    mapVM = mapVM,
                    onOpenStop = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("Empty map grid").assertExists()
        assertEquals(1, config.loads)
        assertEquals(1, calls.viewportManagers)
        assertEquals(1, calls.alertUpdates)
        assertEquals(listOf<SheetRoutes?>(SheetRoutes.TripDetails(filter)), calls.navEntries)

        val vehicle = f.vehicle(Vehicle.CurrentStatus.InTransitTo, 40)
        updates.tryEmit(TripDetailsViewModel.MapUpdate.SelectedVehicle(vehicle))
        compose.waitForIdle()
        updates.tryEmit(TripDetailsViewModel.MapUpdate.TripStops(listOf("a", "b")))
        compose.waitForIdle()

        assertEquals(listOf(vehicle), calls.vehicles.map { it.first })
        assertEquals(listOf<List<String>?>(listOf("a", "b")), calls.tripStops)
        // Location updates never start: the trip page hides the rider's location.
        assertTrue(calls.permissions.none { it })
    }

    @Test
    fun `the page's map view model is not upstream's singleton`() {
        var created: IMapViewModel? = null
        compose.setContent { created = rememberLiveTripMapViewModel() }
        compose.waitForIdle()

        val mapVM = checkNotNull(created)
        assertTrue(mapVM is MapViewModel)
        assertNotSame(sharedMapVM, mapVM)
    }
}
