package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mbta.tid.mbta_app.analytics.Analytics
import com.mbta.tid.mbta_app.analytics.MockAnalytics
import com.mbta.tid.mbta_app.android.MyApplicationTheme
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.util.SettingsCache
import com.mbta.tid.mbta_app.model.Direction
import com.mbta.tid.mbta_app.model.StopDetailsFilter
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.model.Vehicle
import com.mbta.tid.mbta_app.model.response.AlertsStreamDataResponse
import com.mbta.tid.mbta_app.network.MockPhoenixSocket
import com.mbta.tid.mbta_app.network.PhoenixSocket
import com.mbta.tid.mbta_app.repositories.IDebugRepository
import com.mbta.tid.mbta_app.repositories.IErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.repositories.ISentryRepository
import com.mbta.tid.mbta_app.repositories.ITripPredictionsRepository
import com.mbta.tid.mbta_app.repositories.ITripRepository
import com.mbta.tid.mbta_app.repositories.IVehicleRepository
import com.mbta.tid.mbta_app.repositories.MockAlertsRepository
import com.mbta.tid.mbta_app.repositories.MockDebugRepository
import com.mbta.tid.mbta_app.repositories.MockErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.MockGlobalRepository
import com.mbta.tid.mbta_app.repositories.MockSentryRepository
import com.mbta.tid.mbta_app.repositories.MockSettingsRepository
import com.mbta.tid.mbta_app.repositories.MockTripPredictionsRepository
import com.mbta.tid.mbta_app.repositories.MockTripRepository
import com.mbta.tid.mbta_app.repositories.MockVehicleRepository
import com.mbta.tid.mbta_app.repositories.Settings
import com.mbta.tid.mbta_app.routes.SheetRoutes
import com.mbta.tid.mbta_app.usecases.AlertsUsecase
import com.mbta.tid.mbta_app.viewModel.IErrorBannerViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsPageViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.MockErrorBannerViewModel
import com.mbta.tid.mbta_app.viewModel.MockTripDetailsPageViewModel
import com.mbta.tid.mbta_app.viewModel.MockTripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsPageViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveTripScreenTest {
    @get:Rule val compose = createComposeRule()

    private val f = LiveUpdateFixtures()
    private val filter = TripDetailsPageFilter(f.trip.id, "v1", f.route.id, 0, f.stopC.id, 30)

    // Upstream's singletons. This page must not use them.
    private val sharedTripDetailsVM = MockTripDetailsViewModel()
    private val sharedTripDetailsPageVM = MockTripDetailsPageViewModel()

    @Before
    fun setUpKoin() {
        startKoin {
            modules(
                module {
                    single<IGlobalRepository> { MockGlobalRepository(f.global) }
                    single<IErrorBannerStateRepository> { MockErrorBannerStateRepository() }
                    // No map: these tests don't provide the map's dependencies.
                    single {
                        SettingsCache(MockSettingsRepository(mapOf(Settings.HideMaps to true)))
                    }
                    single<IDebugRepository> { MockDebugRepository() }
                    single { AlertsUsecase(MockAlertsRepository(), get()) }
                    single<PhoenixSocket> { MockPhoenixSocket() }
                    single<IErrorBannerViewModel> { MockErrorBannerViewModel() }
                    single<Analytics> { MockAnalytics() }
                    single<Clock> { Clock.System }
                    single<ISentryRepository> { MockSentryRepository() }
                    factory<ITripPredictionsRepository> { MockTripPredictionsRepository() }
                    factory<ITripRepository> { MockTripRepository() }
                    factory<IVehicleRepository> { MockVehicleRepository() }
                    single(named("coroutineDispatcherIO")) { Dispatchers.Unconfined }
                    single<ITripDetailsViewModel> { sharedTripDetailsVM }
                    single<ITripDetailsPageViewModel> { sharedTripDetailsPageVM }
                },
            )
        }
    }

    @After fun tearDownKoin() = stopKoin()

    private class Calls {
        val contexts = mutableListOf<TripDetailsViewModel.Context>()
        val tripFilters = mutableListOf<TripDetailsPageFilter?>()
        val pageFilters = mutableListOf<TripDetailsPageFilter?>()
        val sheetRoutes = mutableListOf<SheetRoutes?>()
        var attaches = 0
        var backs = 0
        var foregrounds = 0
    }

    private fun render(
        direction: Direction? = Direction("North", "Alewife", 0),
        map: (@Composable (PaddingValues, AlertsStreamDataResponse?) -> Unit)? = null,
    ): Calls {
        val calls = Calls()
        val tripDetailsVM =
            MockTripDetailsViewModel().apply {
                onSetContext = { calls.contexts += it }
                onSetFilters = { calls.tripFilters += it }
            }
        val pageVM =
            MockTripDetailsPageViewModel(TripDetailsPageViewModel.State(direction, f.trip)).apply {
                onSetFilter = { calls.pageFilters += it }
            }
        val errorBanner =
            object : IErrorBannerViewModel by MockErrorBannerViewModel() {
                override fun setSheetRoute(sheetRoute: SheetRoutes?) {
                    calls.sheetRoutes += sheetRoute
                }

                override fun returnFromBackground() {
                    calls.foregrounds++
                }
            }
        compose.setContent {
            MyApplicationTheme {
                LiveTripScreen(
                    filter,
                    onBack = { calls.backs++ },
                    onOpenStop = {},
                    tripDetailsVM = tripDetailsVM,
                    tripDetailsPageVM = pageVM,
                    errorBannerViewModel = errorBanner,
                    socket = MockPhoenixSocket(onAttachCallback = { calls.attaches++ }),
                    allAlerts = null,
                    map = map,
                )
            }
        }
        compose.waitForIdle()
        return calls
    }

    @Test
    fun `with a map, the page is a sheet over it`() {
        var mapShown = false
        val calls =
            render(map = { _, _ -> Box(Modifier.fillMaxSize().testTag("map")) { mapShown = true } })

        assertTrue(mapShown)
        compose.onNodeWithTag("map").assertExists()
        compose.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, calls.backs)
        assertEquals(listOf(TripDetailsViewModel.Context.TripDetails), calls.contexts.distinct())
    }

    @Test
    fun `the page shows the trip with its own view models`() {
        val calls = render()

        // Upstream's TripDetailsView runs as on upstream's trip page, with this page's VM.
        assertEquals(listOf(TripDetailsViewModel.Context.TripDetails), calls.contexts.distinct())
        assertEquals(listOf<TripDetailsPageFilter?>(filter), calls.tripFilters.distinct())
        assertEquals(listOf<TripDetailsPageFilter?>(filter), calls.pageFilters.distinct())
    }

    @Test
    fun `the page opens the socket and shows errors for the trip page`() {
        val calls = render()

        assertEquals(1, calls.attaches)
        assertEquals(listOf<SheetRoutes?>(SheetRoutes.TripDetails(filter)), calls.sheetRoutes)
        // As ContentView: the banner knows a page is in front.
        assertEquals(1, calls.foregrounds)
    }

    @Test
    fun `the page loads the trip once, for the latest vehicle, while predictions update`() {
        val controller = LiveUpdateController(RuntimeEnvironment.getApplication())
        // The intent named v1. The train's vehicle is now v2.
        val vehicle = f.vehicle(Vehicle.CurrentStatus.InTransitTo, 40, vehicleId = "v2")
        controller.publish(LiveUpdateSession(f.request, f.snapshot(vehicle)))
        val calls = Calls()
        val tripDetailsVM =
            MockTripDetailsViewModel().apply { onSetFilters = { calls.tripFilters += it } }
        val pageVM =
            MockTripDetailsPageViewModel(TripDetailsPageViewModel.State(null, f.trip)).apply {
                onSetFilter = { calls.pageFilters += it }
            }
        compose.setContent {
            MyApplicationTheme {
                LiveTripRoute(
                    f.request,
                    vehicleId = "v1",
                    onBack = {},
                    onOpenStop = {},
                    controller = controller,
                    viewModels = tripDetailsVM to pageVM,
                )
            }
        }
        compose.waitForIdle()
        for (minutes in 1..3) {
            controller.publish(
                LiveUpdateSession(f.request, f.snapshot(vehicle, mapOf(50 to minutes.minutes))),
            )
            compose.waitForIdle()
        }

        val expected = LiveUpdateDeepLink.tripPageFilter(f.request, "v2", f.global)
        assertEquals(listOf(expected), calls.pageFilters.filterNotNull())
        assertEquals(listOf(expected), calls.tripFilters.filterNotNull())
    }

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun renderPill(session: LiveUpdateSession) {
        val controller = LiveUpdateController(RuntimeEnvironment.getApplication())
        controller.publish(session)
        compose.setContent { MyApplicationTheme { LiveTripPill(f.request.tripId, controller) } }
        compose.waitForIdle()
    }

    @Test
    fun `the trip page's pill is a button that opens the destination picker`() {
        renderPill(LiveUpdateSession(f.request))

        compose
            .onNodeWithText(string(R.string.live_update_overlay_choose_destination))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        compose
            .onNodeWithText(string(R.string.live_update_sheet_stop))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
    }

    @Test
    fun `the trip page has no pill once another trip is tracked`() {
        renderPill(LiveUpdateSession(f.request.copy(tripId = "other")))

        compose
            .onNodeWithText(string(R.string.live_update_overlay_choose_destination))
            .assertDoesNotExist()
    }

    @Test
    fun `the header names the route and direction and goes back`() {
        val calls = render()

        compose
            .onAllNodes(isHeading() and hasText("Alewife", substring = true))
            .onFirst()
            .assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Back")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, calls.backs)
    }

    @Test
    fun `the header still has a working back button while loading`() {
        val calls = render(direction = null)

        compose
            .onNode(
                hasContentDescription("Back") and hasClickAction(),
            )
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, calls.backs)
    }

    @Test
    fun `a tapped stop opens its stop details`() {
        assertEquals(f.stopC.id, openedStopId(SheetRoutes.StopDetails(f.stopC.id, null, null)))
        assertEquals(
            f.stopC.id,
            openedStopId(
                SheetRoutes.StopDetails(f.stopC.id, StopDetailsFilter(f.route.id, 0), null),
            ),
        )
        assertNull(openedStopId(SheetRoutes.TripDetails(filter)))
    }

    @Test
    fun `the page's view models are not upstream's singletons`() {
        var created: Pair<ITripDetailsViewModel, ITripDetailsPageViewModel>? = null
        compose.setContent { created = rememberLiveTripViewModels() }
        compose.waitForIdle()

        val (tripDetailsVM, pageVM) = checkNotNull(created)
        assertTrue(tripDetailsVM is TripDetailsViewModel)
        assertTrue(pageVM is TripDetailsPageViewModel)
        assertNotSame(sharedTripDetailsVM, tripDetailsVM)
        assertNotSame(sharedTripDetailsPageVM, pageVM)
    }
}
