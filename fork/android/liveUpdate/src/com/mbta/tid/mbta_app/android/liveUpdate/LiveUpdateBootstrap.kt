package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mbta.tid.mbta_app.analytics.Analytics
import com.mbta.tid.mbta_app.viewModel.IMapViewModel
import com.mbta.tid.mbta_app.viewModel.ITripDetailsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

object LiveUpdateBootstrap {
    private const val TAG = "LiveUpdate"
    private const val RETRY_DELAY_MS = 50L
    private const val MAX_ATTEMPTS = 100

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var followJob: Job? = null
    private val tripPageEntry = TripPageEntry()

    fun start(application: Application, attempt: Int = 0) {
        if (started) return
        if (GlobalContext.getOrNull() == null) {
            if (attempt < MAX_ATTEMPTS) {
                handler.postDelayed({ start(application, attempt + 1) }, RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "Koin did not start; Live Updates are off")
            }
            return
        }
        started = true
        loadKoinModules(module { single { LiveUpdateController(application) } })
        observeVehicleTaps()
        LiveUpdateNotification.ensureChannel(application)
        application.registerActivityLifecycleCallbacks(LiveUpdateOverlayInstaller)
    }

    /**
     * Starts Follow detection when the app UI first opens. The service can also start the process
     * (for example, from a notification action). Then the map view model is not needed.
     */
    internal fun startFollowDetection() {
        if (followJob != null) return
        val koin = GlobalContext.getOrNull() ?: return
        val controller = koin.get<LiveUpdateController>()
        val mapViewModel = koin.get<IMapViewModel>()
        val tripDetailsViewModel = koin.get<ITripDetailsViewModel>()
        val isFollowingPage = mapViewModel.models.map(FollowDetector::isFollowingPage)
        followJob = scope.launch {
            launch {
                isFollowingPage.distinctUntilChanged().collect { open ->
                    if (!open) tripPageEntry.pageClosed()
                }
            }
            FollowDetector.follows(isFollowingPage, tripDetailsViewModel.models).collect {
                if (tripPageEntry.consumeOpenedFromMap()) {
                    controller.onOpenedFromMap(it)
                } else {
                    controller.onFollow(it)
                }
            }
        }
    }

    /** Must run before the UI first gets Analytics from Koin. */
    private fun observeVehicleTaps() {
        val koin = GlobalContext.get()
        val analytics =
            ObservingAnalytics.wrap(koin.get<Analytics>()) { name ->
                if (name == TripPageEntry.VEHICLE_TAP_EVENT) {
                    handler.post { tripPageEntry.vehicleTapped() }
                }
            } ?: return
        loadKoinModules(module { single { analytics } })
    }
}
