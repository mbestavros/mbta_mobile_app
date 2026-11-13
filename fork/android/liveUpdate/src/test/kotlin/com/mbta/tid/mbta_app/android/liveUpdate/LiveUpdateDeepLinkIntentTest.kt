package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Application
import android.content.Intent
import com.mbta.tid.mbta_app.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// A plain Application: MainApplication needs Firebase, which unit tests don't have.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveUpdateDeepLinkIntentTest {
    private val f = LiveUpdateFixtures()

    @Test
    fun `intent is a view intent for this app's MainActivity`() {
        val context = RuntimeEnvironment.getApplication()
        val path = LiveUpdateDeepLink.path(f.request, f.global)

        val intent = LiveUpdateDeepLink.intent(context, path)

        // MainActivity.handleIntent reads the data of an ACTION_VIEW intent as a deep link.
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(path, intent.dataString)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }
}
