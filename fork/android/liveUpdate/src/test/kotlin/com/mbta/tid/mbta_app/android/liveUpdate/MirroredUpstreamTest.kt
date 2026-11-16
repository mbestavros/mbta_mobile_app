package com.mbta.tid.mbta_app.android.liveUpdate

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tripwire for upstream code that the fork copies. [LiveTripActivity], [LiveTripScreen],
 * [LiveTripMap] and [LiveTripSocketSetup] repeat what upstream's pages do. The compiler catches a
 * renamed call, but not a new effect upstream adds. This test fingerprints each copied upstream
 * block, and fails when one changes, naming the fork code to review.
 *
 * After a rebase that changes a block: compare it with the fork's copy, update the copy if needed,
 * then put the new fingerprint from the failure message here.
 */
class MirroredUpstreamTest {
    private class Mirrored(
        val name: String,
        val upstreamFile: String,
        /** Matches the start of the block. The block is the first `{ … }` from the match. */
        val start: Regex,
        val forkCopy: String,
        val fingerprint: String,
    )

    private val app = "androidApp/src/main/java/com/mbta/tid/mbta_app/android"

    private val mirrored =
        listOf(
            Mirrored(
                "MainActivity.onCreate",
                "$app/MainActivity.kt",
                Regex("""override fun onCreate\("""),
                "LiveTripActivity.onCreate",
                "7f462eef89c1cb71",
            ),
            Mirrored(
                "ContentView socket handlers",
                "$app/ContentView.kt",
                Regex(
                    """LaunchedEffect\(null\) \{\s*val errorKey = ErrorKey\(setOf\(\), "socket"\)""",
                ),
                "installSocketHandlers (LiveTripSocketSetup.kt)",
                "866b3e0f969059cc",
            ),
            Mirrored(
                "ContentView socket attach",
                "$app/ContentView.kt",
                Regex("""LifecycleResumeEffect\(null\) \{\s*socket\.attach\(\)"""),
                "LiveTripScreen's LifecycleResumeEffect(socket)",
                "39cbcca7765b3b5b",
            ),
            Mirrored(
                "ContentView map viewport",
                "$app/ContentView.kt",
                Regex("""val mapViewportState = rememberMapViewportState \{"""),
                "LiveTripMap's viewport",
                "1ab9472e94b1f89e",
            ),
            Mirrored(
                "ContentView viewport manager",
                "$app/ContentView.kt",
                Regex("""LaunchedEffect\(Unit\) \{\s*mapViewModel\.setViewportManager"""),
                "LiveTripMap's setViewportManager",
                "fe5ea157cd693eb9",
            ),
            Mirrored(
                "MapAndSheetPage map updates",
                "$app/pages/MapAndSheetPage.kt",
                Regex("""LaunchedEffect\(mapUpdate\) \{"""),
                "LiveTripMap's LaunchedEffect(mapUpdate)",
                "ba5978941fbaff77",
            ),
            Mirrored(
                "TripDetailsPage",
                "$app/pages/TripDetailsPage.kt",
                Regex("""fun TripDetailsPage\([\s\S]*?\n\) \{"""),
                "LiveTripScreen",
                "317c490a9cb79662",
            ),
        )

    /** The repository root. Gradle runs unit tests in the module directory. */
    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

    /** The text from [start] through the closing brace of the first `{` from it. */
    private fun block(text: String, start: Regex): String? {
        val match = start.find(text) ?: return null
        val open = text.indexOf('{', match.range.first)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(match.range.first, i + 1)
            }
        }
        return null
    }

    /** Whitespace changes, such as a reformat, keep the fingerprint. */
    private fun fingerprint(block: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(block.replace(Regex("""\s+"""), " ").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    @Test
    fun `upstream code the fork copies is unchanged`() {
        val changed = mirrored.mapNotNull { m ->
            val text = File(root, m.upstreamFile).readText()
            val block =
                block(text, m.start)
                    ?: return@mapNotNull "${m.name}: not found in ${m.upstreamFile}. " +
                        "Find where upstream moved it, and review ${m.forkCopy}."
            val now = fingerprint(block)
            if (now == m.fingerprint) null
            else
                "${m.name} (${m.upstreamFile}) changed: review ${m.forkCopy}, " +
                    "then set its fingerprint to \"$now\"."
        }

        assertTrue(changed.joinToString("\n", prefix = "Upstream changed:\n"), changed.isEmpty())
    }
}
