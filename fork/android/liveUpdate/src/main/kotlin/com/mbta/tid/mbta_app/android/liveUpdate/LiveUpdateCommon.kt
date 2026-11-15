package com.mbta.tid.mbta_app.android.liveUpdate

import androidx.core.graphics.toColorInt

/** The log tag of every Live Update class. Read it with `adb logcat -s LiveUpdate:*`. */
internal const val LIVE_UPDATE_TAG = "LiveUpdate"

/** A route color from the API, a hex string without `#`. Null if it doesn't parse. */
internal fun parseRouteColor(hex: String): Int? =
    try {
        "#$hex".toColorInt()
    } catch (e: IllegalArgumentException) {
        null
    }
