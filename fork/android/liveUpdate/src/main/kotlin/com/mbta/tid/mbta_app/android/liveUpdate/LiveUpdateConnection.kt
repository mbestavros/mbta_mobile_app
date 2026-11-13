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
        fun create(context: Context, globalKoin: Koin): LiveUpdateConnection {
            val socket =
                Socket(
                        appVariant.socketUrl,
                        params = mapOf("locale" to context.getString(R.string.current_locale)),
                        decode = ::decodeMessage,
                    )
                    .wrapped()
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
