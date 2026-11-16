package com.mbta.tid.mbta_app.android.liveUpdate

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/**
 * What the fork assumes about upstream that the compiler can't check. A change in one of these
 * keeps the build green and breaks the feature at runtime, so each has a guard here: a test that
 * runs real upstream code, or a runtime check that [report]s a break.
 *
 * Where each assumption is used, and what guards it:
 * - A page is the trip page when the map is in `TripSelected` and the trip details context is
 *   `TripDetails` (`TripPage.kt`). `TripDetailsViewContractTest` checks that upstream's
 *   `TripDetailsView` sets the context. The map state is not checked against a real `MapViewModel`.
 * - `MainActivity.handleIntent` opens the `data` URL of an `ACTION_VIEW` intent
 *   (`LiveUpdateDeepLink.intent`). `UpstreamContractTest` checks it.
 * - `MainActivity.onCreate` calls `setContent` before the overlay is installed
 *   (`LiveUpdateOverlayInstaller`). [contentProblem] checks it at runtime.
 * - Only the map and sheet page handles deep links (`returnToTrip` in `LiveUpdateOverlay.kt`).
 *   [DEEP_LINK_HANDLED_TIMEOUT_MS] after a link, a runtime check reports a link that is still
 *   pending.
 * - The fork's trip page (`LiveTripScreen.kt`) rebuilds upstream's `TripDetailsPage` around
 *   upstream's `TripDetailsView`. It relies on `TripDetailsView` using the view model it is given,
 *   on the app's socket opening only while an activity attaches it (as `ContentView` does), and on
 *   the error banner showing errors only for the sheet route set with `setSheetRoute`.
 *   `LiveTripScreenTest` and `TripDetailsViewContractTest` check the first. The others are not
 *   checked: a page that never loads, or never shows errors, is the sign.
 * - The stop layers have a layer whose ID ends in [SELECTED_PIN_LAYER_SUFFIX] (`LiveUpdateMapPin`).
 *   `LiveUpdateMapPinTest` checks it on the real `StopLayerGenerator`, and the pin reports at
 *   runtime when it can't show.
 * - The socket's parse error message starts with [PARSE_ERROR_PREFIX] (`TripTracker`).
 *   `UpstreamContractTest` checks it.
 * - [TAB_BAR_HEIGHT]. Nothing checks it. Look at the pill above the tabs after a rebase.
 *
 * A break is recorded in [LiveUpdateDiagnostics] (`contract_violation` entries). A debuggable build
 * also shows a toast, so a developer sees it right away.
 */
internal object UpstreamContract {
    /** The ID ending of upstream's selected stop pin layer. The fork copies that layer. */
    const val SELECTED_PIN_LAYER_SUFFIX = "-selected-pin"

    /**
     * The start of upstream's parse error message (SocketError.FAILED_TO_PARSE in shared). That
     * constant is internal to shared, so it is copied here. If it changes, parse errors also
     * reconnect, at most once per `TripTracker.RECONNECT_DELAY_MS`.
     */
    const val PARSE_ERROR_PREFIX = "channel failed to parse"

    /** How long the map and sheet page may take to handle a deep link before we assume it can't. */
    const val DEEP_LINK_HANDLED_TIMEOUT_MS = 1_500L

    /** Height of the upstream bottom tab row (a Material 3 tab with an icon and a label). */
    val TAB_BAR_HEIGHT: Dp = 72.dp

    // Check names. They appear in diagnostics and in the toast.
    const val OVERLAY_ORDER = "overlay_install_order"
    const val DEEP_LINK_UNHANDLED = "deep_link_unhandled"
    const val MAP_PIN_LAYER = "map_pin_layer"
    const val MAP_PIN_NOT_SHOWN = "map_pin_not_shown"

    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Records that upstream no longer behaves as the fork assumes: to the log, to
     * [LiveUpdateDiagnostics] and, when [debuggable], as a toast. Each [check] is reported once per
     * process, so a check that fails every second doesn't flood the log.
     */
    fun report(
        context: Context,
        check: String,
        detail: String,
        debuggable: Boolean = context.isDebuggable(),
    ) {
        if (!reported.add(check)) return
        Log.e(LIVE_UPDATE_TAG, "Upstream changed: $check: $detail")
        val app = context.applicationContext
        LiveUpdateDiagnostics(app).record("contract_violation check=$check detail=$detail")
        if (debuggable) {
            // Debug builds only, for the developer, so the text is not a localized string.
            val toast = {
                Toast.makeText(
                        app,
                        "Live Update: upstream changed ($check). See adb logcat -s LiveUpdate",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) toast()
            else Handler(Looper.getMainLooper()).post(toast)
        }
    }

    /** Lets tests report the same check again. */
    internal fun resetReported() = reported.clear()

    /**
     * Null if [content] (MainActivity's `android.R.id.content`) looks as it does once `setContent`
     * has run: its first child is upstream's ComposeView. Otherwise, what is wrong.
     *
     * `setContent` reuses a ComposeView that is already the first child, so the overlay installed
     * on an empty content view would be reused as upstream's UI, and replace it.
     */
    fun contentProblem(content: ViewGroup, overlayTag: Any): String? {
        if (content.childCount == 0) return "the content view is empty: setContent has not run"
        val first = content.getChildAt(0)
        return when {
            first.tag == overlayTag -> "the first child is the overlay: setContent has not run"
            first !is ComposeView ->
                "the first child is ${first.javaClass.name}, not upstream's ComposeView"
            else -> null
        }
    }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
