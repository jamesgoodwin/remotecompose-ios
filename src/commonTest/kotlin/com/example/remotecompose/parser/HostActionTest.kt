package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three ways a document asks its host to do something, against
 * `tools/rc-writer/hostactions.rc`: `HOST_ACTION` by number, `HOST_METADATA_ACTION` with a string
 * beside it, and `HOST_NAMED_ACTION` named by a string and carrying a typed value.
 *
 * One `HostAction` in the writer produces all three, depending on which constructor is used.
 */
class HostActionTest {

    private val bytes = fixture("hostactions")

    /** The three buttons, top to bottom: 40 tall, 10 apart, below a 16 padding. */
    private val plain = 36f
    private val withMetadata = 86f
    private val named = 136f

    private class Heard {
        val host = mutableListOf<Pair<Int, String>>()
        val byName = mutableListOf<Pair<String, Any?>>()
    }

    private fun listening(): Pair<RemoteComposeDocument, Heard> {
        val heard = Heard()
        val document = RemoteComposeParser.load(bytes)
        document.onHostAction = { id, metadata -> heard.host += id to metadata }
        document.onNamedAction = { name, value -> heard.byName += name to value }
        document.frame(0L)
        return document to heard
    }

    @Test
    fun eachButtonCarriesItsOwnKindOfAction() {
        val operations = OperationReader.readAll(bytes)
        assertEquals(1, operations.count { it is Operation.HostAction })
        assertEquals(1, operations.count { it is Operation.HostMetadataAction })
        val byName = operations.filterIsInstance<Operation.HostNamedAction>().single()
        assertEquals(0, byName.type, "FLOAT_TYPE")
    }

    @Test
    fun aPlainActionIsJustItsNumber() {
        val (document, heard) = listening()
        assertTrue(document.click(150f, plain))
        assertEquals(listOf(7 to ""), heard.host)
    }

    @Test
    fun aMetadataActionBringsItsString() {
        val (document, heard) = listening()
        assertTrue(document.click(150f, withMetadata))
        assertEquals(listOf(9 to "sku-4417"), heard.host)
    }

    @Test
    fun aNamedActionArrivesByNameWithItsValue() {
        val (document, heard) = listening()
        assertTrue(document.click(150f, named))
        assertEquals(1, heard.byName.size)
        assertEquals("addToCart", heard.byName[0].first)
        assertEquals(12.5f, heard.byName[0].second as Float, 0.001f)
        assertTrue(heard.host.isEmpty(), "a named action is not one of the numbered ones")
    }

    @Test
    fun aNamedActionReadsItsValueAsTheTypeItStates() {
        // The type says how to read the id: as a float here, so a float is what the host gets
        // rather than the raw id or the int the same number would be.
        val (document, heard) = listening()
        document.click(150f, named)
        assertTrue(heard.byName[0].second is Float)
    }

    @Test
    fun aDocumentWithNoListenerSimplyDoesNothing() {
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        // No callback set: the click is still handled by the component, and nothing throws.
        assertTrue(document.click(150f, named))
        assertNull(document.onNamedAction)
    }
}
