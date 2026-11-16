package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mbta.tid.mbta_app.android.MyApplicationTheme
import com.mbta.tid.mbta_app.model.response.GlobalResponse
import com.mbta.tid.mbta_app.repositories.IGlobalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.GlobalContext

/**
 * The fork's trip page for the tracked trip. The notification and the pill open it once the rider
 * is aboard. Upstream has no deep link to its own trip page, so the fork hosts upstream's trip view
 * here instead of editing upstream (see [LiveTripScreen]).
 *
 * Back, and the header's back and close buttons, return to where the rider was: the MainActivity
 * below. Opened from the notification with no MainActivity below, Back opens stop details at the
 * boarding station in a new MainActivity, as Back from upstream's trip page does. A tap on a stop
 * in the list opens that stop's details in MainActivity. Either way this activity finishes, so the
 * app keeps one MainActivity.
 */
class LiveTripActivity : ComponentActivity() {
    private val opened = MutableStateFlow<Opened?>(null)

    /** The trip this activity was opened for. */
    internal data class Opened(val request: LiveUpdateRequest, val vehicleId: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = read(intent)
        // Koin starts in Application.onCreate, so it is there unless that failed.
        val koinStarted = GlobalContext.getOrNull() != null
        // In a new process, this can run before the initializer's posted start. The page needs
        // the controller that start registers. Once Koin is up, start registers it right away.
        if (koinStarted) LiveUpdateBootstrap.start(application)
        if (first == null || !koinStarted) {
            Log.w(LIVE_UPDATE_TAG, "LiveTripActivity opened without a trip")
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            finish()
            return
        }
        opened.value = first
        enableEdgeToEdge()
        // As MainActivity, so the map reaches the screen edges the same way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        onBackPressedDispatcher.addCallback(this) { leave() }
        setContent {
            MyApplicationTheme {
                val opened by opened.collectAsState()
                opened?.let {
                    LiveTripRoute(
                        it.request,
                        it.vehicleId,
                        onBack = ::leave,
                        onOpenStop = ::openStop,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)?.let { opened.value = it }
    }

    private fun leave() {
        val global = GlobalContext.getOrNull()?.getOrNull<IGlobalRepository>()?.state?.value
        val link = backLink(isTaskRoot, opened.value?.request, global)
        if (link == null) finish() else openInMainActivity(link)
    }

    private fun openStop(stationId: String) {
        openInMainActivity(LiveUpdateDeepLink.stopLink(stationId))
    }

    /**
     * Opens [link] in the MainActivity below this one, or a new one if there is none. CLEAR_TOP
     * finishes this activity, and SINGLE_TOP hands the link to the existing MainActivity. The
     * overlay there checks that upstream opened it (see [SentLinks]).
     */
    private fun openInMainActivity(link: LiveUpdateDeepLink.Link) {
        SentLinks.sent(link)
        startActivity(
            LiveUpdateDeepLink.intent(this, link.path)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        private const val EXTRA_VEHICLE_ID = "liveTrip.vehicleId"

        /**
         * Opens the trip page of [request]. Also from a notification, so in a new task if needed.
         */
        fun intent(context: Context, request: LiveUpdateRequest, vehicleId: String?): Intent =
            request
                .writeTo(Intent(context, LiveTripActivity::class.java))
                .putExtra(EXTRA_VEHICLE_ID, vehicleId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * Where Back goes: null to finish and show the MainActivity below as the rider left it. As
         * the [isTaskRoot] there is none, so Back opens stop details at the boarding station.
         */
        internal fun backLink(
            isTaskRoot: Boolean,
            request: LiveUpdateRequest?,
            global: GlobalResponse?,
        ): LiveUpdateDeepLink.Link? =
            if (isTaskRoot && request != null) {
                LiveUpdateDeepLink.boardingLink(request, global)
            } else {
                null
            }

        internal fun read(intent: Intent?): Opened? {
            val request = intent?.let { LiveUpdateRequest.readFrom(it) } ?: return null
            return Opened(request, intent.getStringExtra(EXTRA_VEHICLE_ID))
        }
    }
}
