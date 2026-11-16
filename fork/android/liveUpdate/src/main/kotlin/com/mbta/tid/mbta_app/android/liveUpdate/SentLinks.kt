package com.mbta.tid.mbta_app.android.liveUpdate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last link the fork sent to MainActivity, until the overlay there has checked that upstream
 * opened it. The More page ignores links, so a link can stay pending. The overlay sees upstream's
 * page and decides what to do (see [pendingLinkOutcome]).
 *
 * The pill sends links from inside MainActivity. [LiveTripActivity] sends them from outside, with
 * an intent, before MainActivity resumes. Both go through here, so one check covers both.
 */
internal object SentLinks {
    private val _pending = MutableStateFlow<LiveUpdateDeepLink.Link?>(null)
    val pending: StateFlow<LiveUpdateDeepLink.Link?> = _pending.asStateFlow()

    fun sent(link: LiveUpdateDeepLink.Link) {
        _pending.value = link
    }

    /** The overlay checked [link]. A link sent since then stays pending. */
    fun checked(link: LiveUpdateDeepLink.Link) {
        _pending.compareAndSet(link, null)
    }
}
