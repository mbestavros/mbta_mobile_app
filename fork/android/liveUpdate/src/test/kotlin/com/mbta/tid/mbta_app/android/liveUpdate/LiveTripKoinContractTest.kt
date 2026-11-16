package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mbta.tid.mbta_app.AppVariant
import com.mbta.tid.mbta_app.analytics.MockAnalytics
import com.mbta.tid.mbta_app.android.MainApplication
import com.mbta.tid.mbta_app.android.util.SettingsCache
import com.mbta.tid.mbta_app.dependencyInjection.makeNativeModule
import com.mbta.tid.mbta_app.initKoin
import com.mbta.tid.mbta_app.network.MockPhoenixSocket
import com.mbta.tid.mbta_app.network.NetworkConnectivityMonitor
import com.mbta.tid.mbta_app.network.PhoenixSocket
import com.mbta.tid.mbta_app.repositories.AccessibilityStatusRepository
import com.mbta.tid.mbta_app.repositories.CurrentAppVersionRepository
import com.mbta.tid.mbta_app.repositories.IDebugRepository
import com.mbta.tid.mbta_app.repositories.IErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import com.mbta.tid.mbta_app.viewModel.IErrorBannerViewModel
import com.mbta.tid.mbta_app.viewModel.MapViewModel
import com.mbta.tid.mbta_app.viewModel.TripDetailsViewModel
import kotlin.reflect.KClass
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.currentKoinScope
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.core.scope.Scope
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Builds the trip page's view models from upstream's real Koin modules, as `MainApplication` starts
 * them. The view models take positional `get()` calls and named dispatchers. The compiler checks
 * neither against upstream's module, so a renamed qualifier or a missing definition would only
 * crash the page at runtime. The other tests use hand-built modules, which can't see that.
 */
// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveTripKoinContractTest {
    @get:Rule val compose = createComposeRule()

    private val store = ViewModelStore()

    @Before
    fun startUpstreamKoin() {
        val context = RuntimeEnvironment.getApplication()
        initKoin(
            AppVariant.Staging,
            listOf(
                makeNativeModule(
                    AccessibilityStatusRepository(context),
                    MockAnalytics(),
                    CurrentAppVersionRepository("0.0.0"),
                    NetworkConnectivityMonitor(context),
                    MockPhoenixSocket(),
                ),
                MainApplication.koinViewModelModule(),
            ),
            context,
        )
    }

    @After
    fun tearDown() {
        store.clear()
        stopKoin()
    }

    private fun <T : ViewModel> build(type: KClass<T>, create: () -> T): T =
        ViewModelProvider(store, viewModelFactory { addInitializer(type) { create() } })[type.java]

    /** The scope the page resolves from, as it gets it. */
    private fun pageScope(): Scope {
        var scope: Scope? = null
        compose.setContent { scope = currentKoinScope() }
        compose.waitForIdle()
        return checkNotNull(scope)
    }

    @Test
    fun `upstream's modules build the page's trip details view model`() {
        val scope = pageScope()

        assertNotNull(build(TripDetailsViewModel::class) { scope.newLiveTripDetailsViewModel() })
    }

    @Test
    fun `upstream's modules build the page's map view model`() {
        val scope = pageScope()

        assertNotNull(build(MapViewModel::class) { scope.newLiveTripMapViewModel() })
    }

    @Test
    fun `upstream's modules have what the page injects`() {
        val koin = GlobalContext.get()

        koin.get<PhoenixSocket>()
        koin.get<IGlobalRepository>()
        koin.get<IErrorBannerViewModel>()
        koin.get<IErrorBannerStateRepository>()
        koin.get<IDebugRepository>()
        koin.get<SettingsCache>()
    }
}
