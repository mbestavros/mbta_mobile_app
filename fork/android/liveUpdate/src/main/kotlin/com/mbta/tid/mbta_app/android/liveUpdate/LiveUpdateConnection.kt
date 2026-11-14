package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.android.appVariant
import com.mbta.tid.mbta_app.android.phoenix.wrapped
import com.mbta.tid.mbta_app.android.util.decodeMessage
import com.mbta.tid.mbta_app.dependencyInjection.makeNativeModule
import com.mbta.tid.mbta_app.network.PhoenixSocket
import com.mbta.tid.mbta_app.repositories.DebugRepository
import com.mbta.tid.mbta_app.repositories.ErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.IDebugRepository
import com.mbta.tid.mbta_app.repositories.IErrorBannerStateRepository
import com.mbta.tid.mbta_app.repositories.ITripPredictionsRepository
import com.mbta.tid.mbta_app.repositories.IVehicleRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.phoenixframework.Socket

/**
 * A websocket and channel repositories that only the Live Update service uses.
 *
 * The app detaches its shared socket when it goes to the background (ContentView), which would also
 * close the service's channels. A separate socket keeps the service independent of the UI. The
 * repositories come from a separate Koin application, so they use this socket, a private error
 * banner repository (no banners in the UI) and a private debug repository (no entries in the
 * channel debug view).
 *
 * Two settings differ from upstream's socket, which only runs while the app is open:
 * - [onFrame] runs for every frame, heartbeat replies included, so the service can tell a quiet
 *   channel from a dead socket (see [LiveUpdateWatchdog]).
 * - Reconnects back off to [MAX_RECONNECT_DELAY_MS] instead of retrying every 2 seconds forever. In
 *   a tunnel, that saves a DNS, TCP and TLS attempt every 2 seconds. The service reconnects at once
 *   when the network comes back, so recovery isn't slower.
 */
class LiveUpdateConnection
private constructor(private val socket: PhoenixSocket, private val koinApp: KoinApplication) {
    val tripPredictions: ITripPredictionsRepository = koinApp.koin.get()
    val vehicle: IVehicleRepository = koinApp.koin.get()

    fun open() = socket.attach()

    fun close() {
        tripPredictions.disconnect()
        vehicle.disconnect()
        socket.detach()
        koinApp.close()
    }

    companion object {
        private val RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L)
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        fun create(
            context: Context,
            globalKoin: Koin,
            onFrame: () -> Unit = {},
        ): LiveUpdateConnection {
            val rawSocket =
                Socket(
                    appVariant.socketUrl,
                    params = mapOf("locale" to context.getString(R.string.current_locale)),
                    decode = ::decodeMessage,
                )
            // The wrapper sets upstream's reconnect delays in its init, so override them after.
            val socket = rawSocket.wrapped()
            rawSocket.reconnectAfterMs = { tries ->
                RECONNECT_DELAYS_MS.getOrElse(tries - 1) { MAX_RECONNECT_DELAY_MS }
            }
            rawSocket.onMessage { onFrame() }
            val koinApp = koinApplication {
                modules(
                    makeNativeModule(
                        globalKoin.get(),
                        globalKoin.get(),
                        globalKoin.get(),
                        globalKoin.get(),
                        socket,
                    ),
                    module {
                        single<IDebugRepository> { DebugRepository() }
                        single<IErrorBannerStateRepository> { ErrorBannerStateRepository() }
                        single<CoroutineDispatcher>(named("coroutineDispatcherIO")) {
                            Dispatchers.IO
                        }
                    },
                )
            }
            return LiveUpdateConnection(socket, koinApp)
        }
    }
}
