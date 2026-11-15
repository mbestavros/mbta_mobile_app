package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import android.content.Intent
import com.mbta.tid.mbta_app.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs real upstream code to check what the fork assumes about it (see [UpstreamContract]). The
 * other tests build upstream's types by hand, so they can't see upstream change its mind.
 */
// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class UpstreamContractTest {
    private val f = LiveUpdateFixtures()

    // Not created: onCreate needs Firebase and Sentry. handleIntent needs only the intent.
    private fun mainActivity() = Robolectric.buildActivity(MainActivity::class.java).get()

    @Test
    fun `MainActivity opens the link of a view intent as the deep link we built`() {
        val context = RuntimeEnvironment.getApplication()
        val activity = mainActivity()
        // Real-style stop IDs: the fixture IDs contain a slash, which no MBTA stop ID has.
        val request = f.request.copy(stopId = "place-pktrm")
        val cases =
            listOf(
                Triple(request, LiveUpdatePhase.Waiting, null),
                Triple(request.withDestination("place-harsq", 50), LiveUpdatePhase.Riding, "v1"),
            )
        for ((request, phase, vehicleId) in cases) {
            val path = LiveUpdateDeepLink.path(request, phase, vehicleId, f.global)

            activity.handleIntent(LiveUpdateDeepLink.intent(context, path))

            assertEquals(
                "MainActivity.handleIntent for $path",
                LiveUpdateDeepLink.state(request, phase, vehicleId, f.global),
                activity.deepLinkStateFlow.value,
            )
            activity.clearDeepLink()
            assertNull(activity.deepLinkStateFlow.value)
        }
    }

    @Test
    fun `MainActivity ignores an intent that is not a link`() {
        val activity = mainActivity()

        activity.handleIntent(Intent(Intent.ACTION_MAIN))

        assertNull(activity.deepLinkStateFlow.value)
    }

    @Test
    fun `the parse error prefix still starts upstream's parse error message`() {
        // SocketError is internal to shared. Its message is "<FAILED_TO_PARSE> - <details>".
        val upstream =
            Class.forName("com.mbta.tid.mbta_app.model.SocketError")
                .getDeclaredField("FAILED_TO_PARSE")
                .apply { isAccessible = true }
                .get(null) as String

        assertTrue(
            "upstream's FAILED_TO_PARSE is \"$upstream\"",
            upstream.startsWith(UpstreamContract.PARSE_ERROR_PREFIX),
        )
    }
}
