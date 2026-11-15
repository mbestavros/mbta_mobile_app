package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import com.mbta.tid.mbta_app.analytics.MockAnalytics
import com.mbta.tid.mbta_app.android.MyApplicationTheme
import com.mbta.tid.mbta_app.android.stopDetails.TripDetailsView
import com.mbta.tid.mbta_app.android.stopDetails.TripRouteAccents
import com.mbta.tid.mbta_app.android.util.SettingsCache
import com.mbta.tid.mbta_app.model.TripDetailsPageFilter
import com.mbta.tid.mbta_app.repositories.IErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.repositories.MockErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.MockGlobalRepository
import com.mbta.tid.mbta_app.repositories.MockSettingsRepository
import com.mbta.tid.mbta_app.viewModel.MockTripDetailsViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `TripPage` tells the trip page from stop details by the context that upstream's `TripDetailsView`
 * gives the trip details view model. This renders the real `TripDetailsView`, so a change in what
 * it sets fails here and not silently in the app. See [UpstreamContract].
 */
// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TripDetailsViewContractTest {
    @get:Rule val compose = createComposeRule()

    private val f = LiveUpdateFixtures()
    private val filter = TripDetailsPageFilter(f.trip.id, "v1", f.route.id, 0, f.stopC.id, 30)

    @Before
    fun setUpKoin() {
        startKoin {
            modules(
                module {
                    single<IGlobalRepository> { MockGlobalRepository(f.global) }
                    single<IErrorBannerStateRepository> { MockErrorBannerStateRepository() }
                    single { SettingsCache(MockSettingsRepository()) }
                },
            )
        }
    }

    @After fun tearDownKoin() = stopKoin()

    private class Calls {
        val contexts = mutableListOf<TripDetailsViewModel.Context>()
        val filters = mutableListOf<TripDetailsPageFilter?>()
    }

    private fun render(isTripDetailsPage: Boolean): Calls {
        val calls = Calls()
        val viewModel =
            MockTripDetailsViewModel().apply {
                onSetContext = { calls.contexts += it }
                onSetFilters = { calls.filters += it }
            }
        compose.setContent {
            MyApplicationTheme {
                TripDetailsView(
                    tripFilter = filter,
                    allAlerts = null,
                    onOpenAlertDetails = {},
                    openSheetRoute = {},
                    openModal = {},
                    now = f.now,
                    routeAccents = TripRouteAccents.default,
                    isTripDetailsPage = isTripDetailsPage,
                    tripDetailsVM = viewModel,
                    analytics = MockAnalytics(),
                )
            }
        }
        compose.waitForIdle()
        return calls
    }

    @Test
    fun `the trip page sets the TripDetails context and its filter`() {
        val calls = render(isTripDetailsPage = true)

        assertEquals(listOf(TripDetailsViewModel.Context.TripDetails), calls.contexts.distinct())
        assertEquals(listOf<TripDetailsPageFilter?>(filter), calls.filters.distinct())
    }

    @Test
    fun `stop details sets the StopDetails context and its filter`() {
        val calls = render(isTripDetailsPage = false)

        assertEquals(listOf(TripDetailsViewModel.Context.StopDetails), calls.contexts.distinct())
        assertEquals(listOf<TripDetailsPageFilter?>(filter), calls.filters.distinct())
    }
}
