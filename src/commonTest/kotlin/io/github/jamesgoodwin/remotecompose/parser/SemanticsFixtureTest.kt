package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsMode
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsNode
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ACCESSIBILITY_SEMANTICS` against `tools/rc-writer/semantics.rc` (see `buildSemanticsSample()`
 * in the writer tool): what a document tells a screen reader, which is only ever what it was
 * written to say.
 */
class SemanticsFixtureTest {

    private val bytes by lazy { fixture("semantics") }

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    @Test
    fun theDocumentDescribesItself() {
        assertEquals("Four things a screen reader can find", loaded().contentDescription)
    }

    @Test
    fun everyLabelledComponentIsThereAndNothingElseIs() {
        val nodes = loaded().semantics
        assertEquals(4, nodes.size, "one per labelled component, top level")
        assertEquals(
            listOf("Chart of the last seven days", null, "Refresh", "Decorative pattern"),
            nodes.map { it.contentDescription },
        )
        assertEquals(SemanticsRole.IMAGE, nodes[0].role)
        assertEquals(SemanticsRole.BUTTON, nodes[2].role)
    }

    @Test
    fun aLabelledThingSitsWhereItWasDrawn() {
        // The rectangle a reader is given has to be the one a finger would hit: the picture is
        // the document's full width inside its 16pt padding, 60 tall, under the title.
        val picture = loaded().semantics.first()
        assertEquals(16f, picture.left, 0.5f)
        assertEquals(284f, picture.right, 0.5f)
        assertEquals(60f, picture.bottom - picture.top, 0.5f)
    }

    @Test
    fun aMergedRowKeepsItsChildrenUnderIt() {
        // The row says nothing itself; what a reader should hear is the two texts inside it, read
        // as one thing. Nesting is what carries that, since MERGE is about descendants.
        val row = loaded().semantics[1]
        assertEquals(SemanticsMode.MERGE, row.mode)
        assertNull(row.contentDescription)
        assertEquals(listOf("Battery", "82%"), row.children.map { it.text })
        assertTrue(row.children.all { it.mode == SemanticsMode.SET })
        assertTrue(
            row.children.all { it.left >= row.left && it.right <= row.right },
            "and inside it, which is what lets a host nest them",
        )
    }

    @Test
    fun aClickableThingSaysSoAndCarriesItsState() {
        val button = loaded().semantics[2]
        assertTrue(button.clickable)
        assertTrue(button.enabled)
        assertEquals("Not yet refreshed", button.stateDescription)
    }

    @Test
    fun theStateDescriptionIsWhateverTheValueSaysNow() {
        // The state is a document value, so clicking the button changes what a reader is told —
        // the label is resolved on the frame it is read, not when the document was written.
        val document = loaded()
        val button = document.semantics[2]
        document.click((button.left + button.right) / 2f, (button.top + button.bottom) / 2f)
        document.frame(16L)
        assertEquals("Refreshed", document.semantics[2].stateDescription)
    }

    @Test
    fun decorationReplacesWhatIsInsideIt() {
        val panel = loaded().semantics[3]
        assertEquals(SemanticsMode.CLEAR_AND_SET, panel.mode)
        assertEquals("Decorative pattern", panel.contentDescription)
    }

    @Test
    fun aDocumentThatSaysNothingHasNoSemantics() {
        // Which is most of them: the format labels nothing by itself.
        val plain = RemoteComposeParser.load(fixture("sample")).also { it.frame(0L) }
        assertEquals(emptyList<SemanticsNode>(), plain.semantics)
    }
}
