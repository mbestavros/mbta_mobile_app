package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why tracking ended or reconnected, and why the process died, so that a vanished Live
 * Update can be explained after the fact.
 *
 * Read it with `adb logcat -d -s LiveUpdate:*`, or on a debug build with `adb shell run-as
 * <package> cat shared_prefs/live_update_diagnostics.xml`.
 */
class LiveUpdateDiagnostics(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(event: String) {
        Log.i(TAG, event)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entries = entries() + "$stamp $event"
        prefs.edit().putString(KEY_LOG, entries.takeLast(MAX_ENTRIES).joinToString("\n")).apply()
    }

    fun entries(): List<String> =
        prefs.getString(KEY_LOG, null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    /** Records process exits since the last check. [sessionSaved] means tracking was active. */
    fun recordProcessExits(sessionSaved: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits =
            try {
                am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not read process exit reasons", e)
                return
            }
        val newExits = exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
        if (newExits.isEmpty()) return
        prefs.edit().putLong(KEY_LAST_EXIT, newExits.maxOf { it.timestamp }).apply()
        for (exit in newExits) {
            val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(exit.timestamp))
            record(
                "process_exit at=$at reason=${reasonName(exit.reason)} " +
                    "importance=${exit.importance} sessionSaved=$sessionSaved " +
                    "desc=${exit.description}",
            )
        }
    }

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
            else -> "code_$reason"
        }

    private companion object {
        const val TAG = "LiveUpdate"
        const val PREFS_NAME = "live_update_diagnostics"
        const val KEY_LOG = "log"
        const val KEY_LAST_EXIT = "lastExitTimestamp"
        // Quiet trips reconnect every few minutes, so keep enough entries for a few trips.
        const val MAX_ENTRIES = 100
        const val MAX_EXITS = 5
    }
}
