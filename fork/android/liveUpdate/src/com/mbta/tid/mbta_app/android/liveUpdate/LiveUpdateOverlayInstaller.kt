package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.mbta.tid.mbta_app.android.MainActivity
import com.mbta.tid.mbta_app.android.MyApplicationTheme

/**
 * Adds the Live Update overlay to MainActivity without changes to upstream UI code.
 *
 * The overlay is added after MainActivity.onCreate has called setContent. setContent reuses a
 * ComposeView that is already the first child of the content view, so the overlay must not be added
 * before it.
 */
object LiveUpdateOverlayInstaller : Application.ActivityLifecycleCallbacks {
    private val overlayTag = Any()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        activity.window.decorView.post { install(activity) }
    }

    private fun install(activity: MainActivity) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<ComposeView>(overlayTag) != null) return
        val overlay =
            ComposeView(activity).apply {
                tag = overlayTag
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent { MyApplicationTheme { LiveUpdateOverlay() } }
            }
        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
