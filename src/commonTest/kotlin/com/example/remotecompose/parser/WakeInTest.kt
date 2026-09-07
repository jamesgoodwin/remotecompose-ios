package com.example.remotecompose.parser

import com.example.remotecompose.fixture
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
        val document = RemoteComposeParser.load(sample)
        document.frame(0L)
        assertEquals(2000, document.nextRepaintDelayMillis(), "two seconds, in milliseconds")
    }

    @Test
    fun theSoonestOfSeveralRequestsIsTheOneKept() {
        // The fixture writes five seconds and then two. Keeping the last would give two either
        // way, so the reader is what says which rule is in force: asking again puts five back
        // only if it were the later write that counted, and it is not.
        val operations = OperationReader.readAll(sample).filterIsInstance<Operation.WakeIn>()
        assertEquals(listOf(5f, 2f), operations.map { it.seconds })

        val document = RemoteComposeParser.load(sample)
        document.frame(0L)
        assertEquals(2000, document.nextRepaintDelayMillis())

        // And once a request has been served, only a sooner one replaces it: that is the whole of
        // what `mLastRepaint` is for, and it is why writing five after two changes nothing.
        document.context.wakeIn(5f)
        assertEquals(2f, document.context.repaintSeconds, 0.001f, "five does not push it back out")
        document.context.wakeIn(1f)
        assertEquals(1f, document.context.repaintSeconds, 0.001f, "one brings it forward")
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
