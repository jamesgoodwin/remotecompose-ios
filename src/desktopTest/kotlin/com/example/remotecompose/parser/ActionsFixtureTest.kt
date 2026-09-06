package com.example.remotecompose.parser

import com.example.remotecompose.model.Opcode
import com.example.remotecompose.runtime.ActionTrigger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of click actions against `tools/rc-writer/actions.rc` (see
 * `buildActionsSample()` in the writer tool): four buttons whose action lists change document
 * values, and a panel plus label that read them.
 */
class ActionsFixtureTest {

    private fun load(): RemoteComposeDocument =
        RemoteComposeParser.load(File("tools/rc-writer/actions.rc").readBytes()).also { it.frame(0L) }

    /** The dark panel whose width the buttons change. */
    private fun panelWidth(doc: RemoteComposeDocument): Float {
        val panel = doc.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>()
            .last { it.paint.color.red < 0.3f && it.paint.color.green < 0.35f }
        return panel.right - panel.left
    }

    private fun label(doc: RemoteComposeDocument): String? {
        val frame = doc.frame(0L)
        val text = frame.opcodes.filterIsInstance<Opcode.DrawText>().last()
        return frame.strings[text.stringIndex]
    }

    @Test
    fun everyButtonHasAClickRegion() {
        val doc = load()
        val clickable = doc.hitRegions.filter { it.actions.containsKey(ActionTrigger.CLICK) }
        assertEquals(4, clickable.size)
        assertTrue(clickable.all { it.right > it.left && it.bottom > it.top })
    }

    @Test
    fun clickWidensAndNarrowsThePanel() {
        val doc = load()
        assertEquals(40f, panelWidth(doc), 0.01f)
        val wide = doc.hitRegions.first { it.actions.containsKey(ActionTrigger.CLICK) }
        assertTrue(doc.click(wide.left + 2f, wide.top + 2f))
        assertEquals(150f, panelWidth(doc), 0.01f)
        val narrow = doc.hitRegions.filter { it.actions.containsKey(ActionTrigger.CLICK) }[1]
        assertTrue(doc.click(narrow.left + 2f, narrow.top + 2f))
        assertEquals(40f, panelWidth(doc), 0.01f)
    }

    @Test
    fun clickReplacesTheLabelText() {
        val doc = load()
        assertEquals("tap a button", label(doc))
        val wide = doc.hitRegions.first { it.actions.containsKey(ActionTrigger.CLICK) }
        doc.click(wide.left + 2f, wide.top + 2f)
        assertEquals("wide", label(doc))
    }

    @Test
    fun hostActionReachesTheHost() {
        val doc = load()
        var received: Pair<Int, String>? = null
        doc.onHostAction = { id, metadata -> received = id to metadata }
        val host = doc.hitRegions.filter { it.actions.containsKey(ActionTrigger.CLICK) }[3]
        assertTrue(doc.click(host.left + 2f, host.top + 2f))
        assertEquals(42, assertNotNull(received).first)
    }

    @Test
    fun integerChangeWritesThePool() {
        val doc = load()
        val counter = doc.hitRegions.filter { it.actions.containsKey(ActionTrigger.CLICK) }[2]
        doc.click(counter.left + 2f, counter.top + 2f)
        assertTrue(doc.context.ints.containsValue(7))
    }

    @Test
    fun aTapOutsideEveryRegionIsUnhandled() {
        val doc = load()
        assertFalse(doc.click(199f, 199f))
    }

    @Test
    fun clickRequestsARepaint() {
        val doc = load()
        doc.frame(0L)
        val wide = doc.hitRegions.first { it.actions.containsKey(ActionTrigger.CLICK) }
        doc.click(wide.left + 2f, wide.top + 2f)
        assertTrue(doc.needsRepaint)
    }
}
