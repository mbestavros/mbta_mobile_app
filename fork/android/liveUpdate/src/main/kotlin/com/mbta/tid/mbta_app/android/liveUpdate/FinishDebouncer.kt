package com.mbta.tid.mbta_app.android.liveUpdate

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Ends tracking only when a finished phase holds across more than one update.
 *
 * A single vehicle update can briefly show another trip ID, a jump in the stop sequence, or a
 * skipped prediction. Each of these would end tracking. So a finished phase must show up in
 * [MIN_SNAPSHOTS] different snapshots. Except for an arrival, it must also hold for [confirmAfter].
 * The 15-second ticker re-emits the same snapshot, which doesn't count as a new one.
 */
class FinishDebouncer(private val confirmAfter: Duration = 20.seconds) {
    private class Pending(
        val reason: FinishReason,
        val sinceMs: Long,
        var lastSnapshot: TrackerSnapshot,
    ) {
        var count = 1
    }

    private var pending: Pending? = null

    /**
     * Returns true when tracking should finish now. Snapshots are compared by identity: the ticker
     * re-emits the same instance, and only a new instance counts as a new update.
     */
    fun shouldFinish(phase: LiveUpdatePhase, snapshot: TrackerSnapshot, nowMs: Long): Boolean {
        if (phase !is LiveUpdatePhase.Finished) {
            pending = null
            return false
        }
        val current = pending
        if (current == null || current.reason != phase.reason) {
            pending = Pending(phase.reason, nowMs, snapshot)
            return false
        }
        if (snapshot !== current.lastSnapshot) {
            current.count++
            current.lastSnapshot = snapshot
        }
        if (current.count < MIN_SNAPSHOTS) return false
        return phase.reason == FinishReason.ArrivedDestination ||
            nowMs - current.sinceMs >= confirmAfter.inWholeMilliseconds
    }

    fun reset() {
        pending = null
    }

    private companion object {
        const val MIN_SNAPSHOTS = 2
    }
}
