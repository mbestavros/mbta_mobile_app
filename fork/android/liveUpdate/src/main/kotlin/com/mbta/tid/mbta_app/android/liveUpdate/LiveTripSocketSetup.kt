package com.mbta.tid.mbta_app.android.liveUpdate

import com.mbta.tid.mbta_app.android.phoenix.PhoenixSocketWrapper
import com.mbta.tid.mbta_app.network.PhoenixSocket
import com.mbta.tid.mbta_app.repositories.ErrorKey
import com.mbta.tid.mbta_app.repositories.IDebugRepository
import com.mbta.tid.mbta_app.repositories.IErrorBannerStateRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What upstream's `ContentView` sets up on the app's socket, for [LiveTripActivity]. Opened from
 * the notification in a new process, the trip page runs without MainActivity, so nothing else sets
 * it up: socket errors would never reach the error banner, and its retry would do nothing.
 *
 * The handlers can't be removed, so they are added once per process and live as long as the socket.
 * If MainActivity adds its own as well, both set the same error key, so the banner shows one error.
 */
internal object LiveTripSocketSetup {
    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensure(
        socket: PhoenixSocket,
        errorBannerRepository: IErrorBannerStateRepository,
        debugRepository: IDebugRepository,
    ) {
        if (!installed.compareAndSet(false, true)) return
        installSocketHandlers(socket, errorBannerRepository, debugRepository, scope)
        (socket as? PhoenixSocketWrapper)?.attachLogging()
    }
}

/** A copy of the socket handlers in upstream's `ContentView`, with the same error key. */
internal fun installSocketHandlers(
    socket: PhoenixSocket,
    errorBannerRepository: IErrorBannerStateRepository,
    debugRepository: IDebugRepository,
    scope: CoroutineScope,
) {
    val errorKey = ErrorKey(setOf(), "socket")
    socket.onError { error, response ->
        scope.launch {
            debugRepository.setSocketConnected(false)
            errorBannerRepository.setDataError(errorKey, "$error $response") { socket.attach() }
        }
    }
    socket.onAttach {
        scope.launch {
            errorBannerRepository.clearDataError(errorKey)
            debugRepository.setSocketConnected(true)
        }
    }
    socket.onDetach { scope.launch { debugRepository.setSocketConnected(false) } }
}
