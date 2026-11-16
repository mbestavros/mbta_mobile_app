package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import com.mbta.tid.mbta_app.model.ErrorBannerState
import com.mbta.tid.mbta_app.network.MockPhoenixSocket
import com.mbta.tid.mbta_app.network.PhoenixSocket
import com.mbta.tid.mbta_app.repositories.MockDebugRepository
import com.mbta.tid.mbta_app.repositories.MockErrorBannerStateRepository
import com.mbta.tid.mbta_app.routes.DeepLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the rider leaves [LiveTripActivity], and what it sets up for the app's socket. */
// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveTripExitTest {
    private val f = LiveUpdateFixtures()

    @After fun clearSentLinks() = SentLinks.pending.value?.let(SentLinks::checked) ?: Unit

    @Test
    fun `Back over MainActivity returns to it as the rider left it`() {
        // The pill opens the page from any MainActivity page, the More page too. Back must not
        // send a link there: the More page ignores links.
        assertNull(LiveTripActivity.backLink(isTaskRoot = false, f.request, f.global))
    }

    @Test
    fun `Back with no MainActivity below opens stop details at the boarding station`() {
        assertEquals(
            LiveUpdateDeepLink.Link(
                LiveUpdateDeepLink.path(f.request, f.global),
                LiveUpdateDeepLink.state(f.request, f.global),
            ),
            LiveTripActivity.backLink(isTaskRoot = true, f.request, f.global),
        )
    }

    @Test
    fun `Back without a trip just finishes`() {
        assertNull(LiveTripActivity.backLink(isTaskRoot = true, request = null, f.global))
    }

    @Test
    fun `a stop link is the stop as upstream parses it`() {
        assertEquals(
            LiveUpdateDeepLink.Link(
                LiveUpdateDeepLink.stopPath("place-harsq"),
                DeepLinkState.Stop("place-harsq", null, null, null),
            ),
            LiveUpdateDeepLink.stopLink("place-harsq"),
        )
    }

    @Test
    fun `a sent link stays pending until it is checked, and a newer one survives the check`() {
        val first = LiveUpdateDeepLink.stopLink("place-harsq")
        val second = LiveUpdateDeepLink.stopLink("place-pktrm")

        SentLinks.sent(first)
        assertEquals(first, SentLinks.pending.value)
        SentLinks.sent(second)
        SentLinks.checked(first)
        assertEquals(second, SentLinks.pending.value)
        SentLinks.checked(second)
        assertNull(SentLinks.pending.value)
    }

    /** Keeps the callbacks the handlers register, as Phoenix does. */
    private class CallbackSocket : PhoenixSocket by MockPhoenixSocket() {
        var onError: ((Throwable, String) -> Unit)? = null
        var onAttach: (() -> Unit)? = null
        var attaches = 0

        override fun onError(callback: (Throwable, String) -> Unit) {
            onError = callback
        }

        override fun onAttach(callback: () -> Unit): String {
            onAttach = callback
            return "attach"
        }

        override fun attach() {
            attaches++
        }
    }

    @Test
    fun `a socket error shows in the banner with a retry, and attaching clears it`() {
        val socket = CallbackSocket()
        val errorBanner = MockErrorBannerStateRepository()

        installSocketHandlers(
            socket,
            errorBanner,
            MockDebugRepository(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        checkNotNull(socket.onError)(RuntimeException("offline"), "no message")

        val error = errorBanner.state.value
        assertTrue("banner shows $error", error is ErrorBannerState.DataError)
        // The banner's retry attaches the socket again.
        checkNotNull(error?.action)()
        assertEquals(1, socket.attaches)

        checkNotNull(socket.onAttach)()
        assertNull(errorBanner.state.value)
    }
}
