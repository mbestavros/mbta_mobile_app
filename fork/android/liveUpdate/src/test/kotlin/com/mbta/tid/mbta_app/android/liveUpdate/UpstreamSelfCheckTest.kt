package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import com.mbta.tid.mbta_app.android.map.MapLayerManager
import com.mbta.tid.mbta_app.map.StopFeaturesBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The runtime checks that report a broken assumption about upstream (see [UpstreamContract]). */
// A plain Application: MainApplication needs Firebase, which unit tests don't have.
// SDK 35: Robolectric's SDK 36 runtime needs Java 21, CI uses Java 17.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class UpstreamSelfCheckTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        UpstreamContract.resetReported()
        ShadowToast.reset()
    }

    private fun violations() =
        LiveUpdateDiagnostics(app).entries().filter { "contract_violation" in it }

    @Test
    fun `a break is recorded once per check, and shown only on a debuggable build`() {
        UpstreamContract.report(app, "check_a", "first", debuggable = false)
        UpstreamContract.report(app, "check_a", "again", debuggable = true)
        assertNull(ShadowToast.getLatestToast())

        UpstreamContract.report(app, "check_b", "second", debuggable = true)

        assertTrue(ShadowToast.getTextOfLatestToast().contains("check_b"))
        val entries = violations()
        assertEquals(2, entries.size)
        assertTrue(entries[0].endsWith("contract_violation check=check_a detail=first"))
        assertTrue(entries[1].endsWith("contract_violation check=check_b detail=second"))
    }

    @Test
    fun `the content view is as upstream's once setContent has run`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val overlayTag = Any()

        assertTrue(UpstreamContract.contentProblem(content, overlayTag)!!.contains("empty"))

        activity.setContent {}
        assertNull(UpstreamContract.contentProblem(content, overlayTag))
    }

    @Test
    fun `the content view is wrong with the overlay first or another kind of view first`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val overlayTag = Any()

        content.addView(ComposeView(activity).apply { tag = overlayTag })
        assertTrue(UpstreamContract.contentProblem(content, overlayTag)!!.contains("overlay"))

        content.removeAllViews()
        content.addView(View(activity))
        assertTrue(
            UpstreamContract.contentProblem(content, overlayTag)!!.contains("not upstream's"),
        )
    }

    @Test
    fun `the pin names the part of upstream's map setup that is missing`() {
        fun problem(map: Boolean, layer: Boolean, source: Boolean, anchor: Boolean) =
            LiveUpdateMapPin.pinProblem(map, layer, source, anchor)

        assertTrue(problem(false, false, false, false)!!.contains("MapView"))
        assertTrue(problem(true, false, false, true)!!.contains(StopFeaturesBuilder.stopSourceId))
        assertTrue(
            problem(true, false, true, false)!!.contains(MapLayerManager.puckAnchorLayerId),
        )
        // On the map, or ready to be added: no problem.
        assertNull(problem(true, true, false, false))
        assertNull(problem(true, false, true, true))
    }

    @Test
    fun `a link that stays pending off the entry screen is a break and does not restart`() {
        assertEquals(
            PendingLinkOutcome.ReportBreak,
            pendingLinkOutcome(
                stillPending = true,
                activityFinishing = false,
                mayBeOnMorePage = false,
            ),
        )
    }

    @Test
    fun `a link that stays pending on the entry screen restarts and is no break`() {
        assertEquals(
            PendingLinkOutcome.Restart,
            pendingLinkOutcome(
                stillPending = true,
                activityFinishing = false,
                mayBeOnMorePage = true,
            ),
        )
    }

    @Test
    fun `a link that upstream handled, or a finishing activity, needs nothing`() {
        for (mayBeOnMorePage in listOf(false, true)) {
            assertEquals(
                PendingLinkOutcome.None,
                pendingLinkOutcome(
                    stillPending = false,
                    activityFinishing = false,
                    mayBeOnMorePage = mayBeOnMorePage,
                ),
            )
            assertEquals(
                PendingLinkOutcome.None,
                pendingLinkOutcome(
                    stillPending = true,
                    activityFinishing = true,
                    mayBeOnMorePage = mayBeOnMorePage,
                ),
            )
        }
    }
}
