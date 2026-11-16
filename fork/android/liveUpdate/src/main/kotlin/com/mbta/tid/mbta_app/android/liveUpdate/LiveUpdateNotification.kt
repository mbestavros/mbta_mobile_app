package com.mbta.tid.mbta_app.android.liveUpdate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.mbta.tid.mbta_app.android.R
import com.mbta.tid.mbta_app.model.RoutePillSpec
import com.mbta.tid.mbta_app.model.RouteType
import com.mbta.tid.mbta_app.utils.EasternTimeInstant

/**
 * Builds the Live Update notification. A Live Update must be ongoing, request promotion, have a
 * content title, use a standard style such as ProgressStyle, and must not be colorized, use custom
 * views or be a group summary. Its channel must not be IMPORTANCE_MIN.
 */
object LiveUpdateNotification {
    const val CHANNEL_ID = "live_updates"
    const val ONGOING_ID = 4_001
    const val FINAL_ID = 4_002
    const val RESUME_ID = 4_003

    // PendingIntent request codes. Different codes keep the intents apart.
    private const val REQUEST_OPEN = 0
    private const val REQUEST_STOP = 1
    private const val REQUEST_DISMISSED = 2
    private const val REQUEST_RESUME = 3
    private const val FINAL_TIMEOUT_MS = 60_000L
    private const val RESUME_TIMEOUT_MS = 30 * 60_000L

    /** True if the app may post notifications (Android 13 and later need the permission). */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.live_update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description = context.getString(R.string.live_update_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Shown at once when the service starts, before any data arrives. */
    fun placeholder(context: Context): Notification =
        baseBuilder(context, openAppIntent(context))
            .setContentTitle(context.getString(R.string.live_update_placeholder_title))
            .setContentText(context.getString(R.string.live_update_placeholder_text))
            .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
            .build()

    /**
     * What the ongoing notification shows. Each post goes to the lock screen and to connected
     * watches, so post only when this changes. The exact clock time is not part of it: the text
     * shows the time to the minute, and the "when" time is close enough until the text changes.
     */
    data class OngoingContent(
        val text: LiveUpdateText,
        val progress: Int,
        val progressMax: Int,
        val boardingPosition: Int,
        val destinationPosition: Int?,
        val routeColor: String,
        val target: LiveUpdateDeepLink.Target?,
        val routePill: RoutePillSpec?,
        val routeType: RouteType?,
        /** The marker is shown only while the vehicle position is known. */
        val showTracker: Boolean,
    )

    fun ongoingContent(
        state: LiveUpdateState,
        text: LiveUpdateText,
        target: LiveUpdateDeepLink.Target?,
    ): OngoingContent =
        OngoingContent(
            text,
            state.progress,
            state.progressMax,
            state.boardingPosition,
            state.destinationPosition,
            state.routeColor,
            target,
            state.routePill,
            state.routeType,
            showTracker(state),
        )

    private fun showTracker(state: LiveUpdateState): Boolean =
        state.routeType != null && state.stopsAway != null

    /** @param target opens the tracked trip when the user taps the notification. */
    fun ongoing(
        context: Context,
        state: LiveUpdateState,
        text: LiveUpdateText,
        target: LiveUpdateDeepLink.Target?,
    ): Notification {
        val color = parseRouteColor(state.routeColor)
        val builder =
            baseBuilder(context, openAppIntent(context, target))
                .setContentTitle(text.title)
                .setContentText(text.text)
                .setSubText(text.subText)
                .setShortCriticalText(text.chip)
                .setStyle(progressStyle(context, state, color))
        state.routePill?.let { builder.setLargeIcon(badge(context, it)) }

        val clockTime = (state.label as? LiveUpdateLabel.ClockTime)?.time
        if (clockTime != null) {
            builder.setWhen(epochMillis(clockTime)).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    /** A plain notification for the end of the trip. It is not ongoing or promoted. */
    fun final(context: Context, text: LiveUpdateText): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon_monochrome)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(FINAL_TIMEOUT_MS)
            .build()

    /**
     * Shown when the system restarted the service after the process died, but didn't allow a
     * foreground service from the background. Tapping it resumes the saved session. A tap on a
     * notification may start a foreground service.
     */
    fun resume(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon_monochrome)
            .setContentTitle(context.getString(R.string.live_update_resume_title))
            .setContentText(context.getString(R.string.live_update_resume_text))
            .setContentIntent(
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_RESUME,
                    Intent(context, LiveUpdateService::class.java)
                        .setAction(LiveUpdateService.ACTION_RESUME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(RESUME_TIMEOUT_MS)
            .build()

    private fun baseBuilder(
        context: Context,
        contentIntent: PendingIntent?,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissedIntent(context))
            .addAction(0, context.getString(R.string.live_update_action_stop), stopIntent(context))

    // The Stop and Dismissed intents never change, so build them once instead of on every post.
    @Volatile private var stopIntent: PendingIntent? = null
    @Volatile private var dismissedIntent: PendingIntent? = null

    private fun stopIntent(context: Context): PendingIntent =
        stopIntent
            ?: serviceIntent(context, LiveUpdateService.ACTION_STOP, REQUEST_STOP).also {
                stopIntent = it
            }

    private fun dismissedIntent(context: Context): PendingIntent =
        dismissedIntent
            ?: serviceIntent(context, LiveUpdateService.ACTION_DISMISSED, REQUEST_DISMISSED).also {
                dismissedIntent = it
            }

    /**
     * One route per session, so keep the last badges instead of drawing them on every post. Only
     * the service's main thread builds notifications, so these need no locking.
     */
    private var badgeCache: Pair<RoutePillSpec, Bitmap>? = null
    private var trackerCache: Pair<Pair<RouteType, String>, Bitmap>? = null

    private fun badge(context: Context, spec: RoutePillSpec): Bitmap =
        badgeCache?.takeIf { it.first == spec }?.second
            ?: LiveUpdateRouteBadge.badge(context, spec).also { badgeCache = spec to it }

    private fun tracker(context: Context, type: RouteType, color: String): Bitmap {
        val key = type to color
        return trackerCache?.takeIf { it.first == key }?.second
            ?: LiveUpdateRouteBadge.tracker(context, type, color).also { trackerCache = key to it }
    }

    private fun progressStyle(
        context: Context,
        state: LiveUpdateState,
        color: Int?,
    ): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle().setStyledByProgress(true)
        val routeType = state.routeType
        if (routeType != null && showTracker(state)) {
            style.setProgressTrackerIcon(
                IconCompat.createWithBitmap(tracker(context, routeType, state.routeColor)),
            )
        }
        val boarding = state.boardingPosition
        val destination = state.destinationPosition
        val segments = buildList {
            if (boarding > 0) add(boarding)
            if (destination != null && destination > boarding) add(destination - boarding)
            if (isEmpty()) add(state.progressMax)
        }
        style.setProgressSegments(
            segments.map { length ->
                NotificationCompat.ProgressStyle.Segment(length).apply { color?.let(::setColor) }
            },
        )
        val points = listOfNotNull(boarding.takeIf { it > 0 }, destination?.takeIf { it > 0 })
        style.setProgressPoints(
            points.map { position ->
                NotificationCompat.ProgressStyle.Point(position).apply { color?.let(::setColor) }
            },
        )
        style.setProgress(state.progress)
        return style
    }

    /**
     * With [target], opens the tracked trip: MainActivity's stop details with the trip selected, or
     * [LiveTripActivity] once the rider is aboard.
     *
     * The system tells PendingIntents apart by their component and data URI too, so each stop link
     * and the trip page are their own entries, even with one request code. A session uses about two
     * (boarding, then aboard), so this is bounded. Extras are not part of it, so
     * FLAG_UPDATE_CURRENT keeps the trip page's extras current.
     */
    private fun openAppIntent(
        context: Context,
        target: LiveUpdateDeepLink.Target? = null,
    ): PendingIntent? {
        val intent =
            target?.intent(context)
                ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val app = context.applicationContext
        return PendingIntent.getService(
            app,
            requestCode,
            Intent(app, LiveUpdateService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** EasternTimeInstant does not expose epoch time, so offset it from the current time. */
    private fun epochMillis(time: EasternTimeInstant): Long =
        System.currentTimeMillis() + (time - EasternTimeInstant.now()).inWholeMilliseconds
}
