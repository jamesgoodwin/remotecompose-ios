package io.github.jamesgoodwin.remotecompose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixture tests read the payloads the demo pages carry rather than the writer's output, which
 * is what lets them run on every target. This is the one place that still opens
 * `tools/rc-writer`, so it is what keeps the two the same: regenerate a fixture without
 * carrying the bytes across and this fails rather than the suite quietly testing the old document.
 */
class PayloadDriftTest {

    @Test
    fun everyPayloadIsTheFixtureOnDisk() {
        val stale = FIXTURES.mapNotNull { (name, bytes) ->
            val file = File("tools/rc-writer/$name.rc")
            when {
                !file.exists() -> "$name: no tools/rc-writer/$name.rc"
                !file.readBytes().contentEquals(bytes) ->
                    "$name: ${file.length()} bytes on disk, ${bytes.size} carried"
                else -> null
            }
        }
        assertTrue(
            stale.isEmpty(),
            "regenerated fixtures have not been carried into the payloads:\n" +
                stale.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun everyFixtureTheWriterProducesIsCarried() {
        // A new fixture with no payload is one the common tests cannot reach, so it would end up
        // tested on the desktop target alone — which is the whole thing this suite moved away
        // from. `texttest.rc` is a scratch file the writer leaves behind, not a fixture.
        val onDisk = File("tools/rc-writer").listFiles { f -> f.name.endsWith(".rc") }
            .orEmpty().map { it.name.removeSuffix(".rc") }.toSet() - "texttest"
        assertEquals(emptySet(), onDisk - FIXTURES.keys, "fixtures with no payload to read them by")
    }
}
