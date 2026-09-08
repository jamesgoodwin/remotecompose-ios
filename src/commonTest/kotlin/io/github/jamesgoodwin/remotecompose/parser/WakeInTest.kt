package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `WAKE_IN` against `tools/rc-writer/sample.rc`.
 *
 * `WakeIn.paint` calls `PaintContext.wakeIn`, which reaches `RemoteComposeState.wakeIn`: the
 * soonest request is kept, and `getOpsToUpdate` hands the delay to the host. It is how a surface
 * that changes rarely says so instead of being drawn at every frame, so there is nothing to see —
 * what it produces is a number the host schedules by.
 */
class WakeInTest {

    private val sample = fixture("sample")

    @Test
    fun aDocumentThatAsksToBeWokenSaysWhen() {
        // The coverage sample carries the request but also animates, so it wants a frame now
        // regardless; the request itself is what is kept.
        val document = RemoteComposeParser.load(sample)
        document.frame(0L)
        assertEquals(2f, document.context.repaintSeconds, 0.001f)

        // On a document with nothing else going on, that request is the answer.
        val quiet = RemoteComposeParser.load(fixture("referenced"))
        quiet.frame(0L)
        quiet.context.wakeIn(2f)
        assertEquals(2000, quiet.nextRepaintDelayMillis(), "two seconds, in milliseconds")
    }

    @Test
    fun theSoonestOfSeveralRequestsIsTheOneKept() {
        // The fixture writes five seconds and then two, and two is what it ends up with. In one
        // pass that only says the last write wins, since the rule proper does not start until a
        // request has been served — which is what the rest of this checks.
        val operations = OperationReader.readAll(sample).filterIsInstance<Operation.WakeIn>()
        assertEquals(listOf(5f, 2f), operations.map { it.seconds })
        val document = RemoteComposeParser.load(sample)
        document.frame(0L)
        assertEquals(2f, document.context.repaintSeconds, 0.001f)

        // Once one has been served, only a sooner one replaces it: that is what `mLastRepaint` is
        // for. Served on a quiet document, since a document that has already changed is answered
        // with "now" before the wake is ever looked at.
        val quiet = RemoteComposeParser.load(fixture("referenced"))
        quiet.frame(0L)
        quiet.context.wakeIn(2f)
        assertEquals(2000, quiet.nextRepaintDelayMillis())
        quiet.context.wakeIn(5f)
        assertEquals(2f, quiet.context.repaintSeconds, 0.001f, "five does not push it back out")
        quiet.context.wakeIn(1f)
        assertEquals(1f, quiet.context.repaintSeconds, 0.001f, "one brings it forward")
    }

    @Test
    fun aDocumentWithNothingToSayAsksForNoFrameAtAll() {
        // `getOpsToUpdate` returns -1 when nothing is listening and nothing has asked, which is
        // the host's licence to stop drawing.
        val document = RemoteComposeParser.load(fixture("referenced"))
        document.frame(0L)
        assertEquals(-1, document.nextRepaintDelayMillis())
    }

    @Test
    fun aDocumentThatHasAlreadyChangedWantsAFrameNow() {
        // A wake is what to do when nothing else is happening; a document following the clock is
        // already asking for the next frame, and that answer comes first.
        val document = RemoteComposeParser.load(fixture("watch"))
        document.frame(0L)
        assertTrue(document.needsRepaint)
        assertEquals(0, document.nextRepaintDelayMillis())
    }
}
