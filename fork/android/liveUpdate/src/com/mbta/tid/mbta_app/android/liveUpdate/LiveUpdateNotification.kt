package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.toColorInt
import com.mbta.tid.mbta_app.android.R
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
    private const val FINAL_TIMEOUT_MS = 60_000L

    /** The extra name that MainActivity.handleIntent reads (MainActivity.deepLinkPathKey). */
    private const val DEEP_LINK_PATH_EXTRA = "deep_link_path"

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
        baseBuilder(context)
            .setContentTitle(context.getString(R.string.live_update_placeholder_title))
            .setContentText(context.getString(R.string.live_update_placeholder_text))
            .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
            .build()

    /** @param deepLinkPath opens the tracked trip when the user taps the notification. */
    fun ongoing(
        context: Context,
        state: LiveUpdateState,
        text: LiveUpdateText,
        deepLinkPath: String?,
    ): Notification {
        val color = parseColor(state.routeColor)
        val builder =
            baseBuilder(context)
                .setContentTitle(text.title)
                .setContentText(text.text)
                .setSubText(text.subText)
                .setShortCriticalText(text.chip)
                .setStyle(progressStyle(state, color))
        if (deepLinkPath != null) builder.setContentIntent(openAppIntent(context, deepLinkPath))

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

    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .setDeleteIntent(serviceIntent(context, LiveUpdateService.ACTION_DISMISSED, 2))
            .addAction(
                0,
                context.getString(R.string.live_update_action_stop),
                serviceIntent(context, LiveUpdateService.ACTION_STOP, 1),
            )

    private fun progressStyle(
        state: LiveUpdateState,
        color: Int?,
    ): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle().setStyledByProgress(true)
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

    private fun openAppIntent(context: Context, deepLinkPath: String? = null): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // MainActivity reads this extra and opens stop details with the trip selected.
            deepLinkPath?.let { intent.putExtra(DEEP_LINK_PATH_EXTRA, it) }
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, LiveUpdateService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun parseColor(hex: String): Int? =
        try {
            "#$hex".toColorInt()
        } catch (e: IllegalArgumentException) {
            null
        }

    /** EasternTimeInstant does not expose epoch time, so offset it from the current time. */
    private fun epochMillis(time: EasternTimeInstant): Long =
        System.currentTimeMillis() + (time - EasternTimeInstant.now()).inWholeMilliseconds
}
