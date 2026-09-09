package io.github.jamesgoodwin.remotecompose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeDocument
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlay as Compose sees it: the document's labels really do become semantics a screen
 * reader can walk, at the size and place the components ended up.
 *
 * The composable is driven directly rather than through
 * [io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas], whose frame loop never lets the
 * test's clock go idle; what it draws is not what is being checked here.
 */
class SemanticsOverlayTest {

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.show(): RemoteComposeDocument {
        val loaded = RemoteComposeParser.load(fixture("semantics")).also { it.frame(0L) }
        setContent {
            Box(Modifier.size(300.dp, 300.dp)) { RemoteComposeSemantics(loaded) }
        }
        return loaded
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyLabelIsANodeAReaderCanFind() = runComposeUiTest {
        show()
        for (label in listOf("Chart of the last seven days", "Refresh", "Decorative pattern")) {
            onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aMergedRowIsOneNodeReadingBothOfItsParts() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Battery, 82%").assertIsDisplayed()
        // And not two: the parts are inside it, not beside it.
        val tree = onRoot().printToString(maxDepth = 10)
        assertTrue("Battery, 82%" in tree, tree)
        assertEquals(1, Regex("Battery").findAll(tree).count(), tree)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aClickableLabelCanBeActivatedAndDoesWhatATapWouldDo() = runComposeUiTest {
        val loaded = show()
        val button = onNodeWithContentDescription("Refresh")
        button.assertHasClickAction()
        button.performSemanticsAction(SemanticsActions.OnClick)
        loaded.frame(16L)
        assertEquals("Refreshed", loaded.semantics[2].stateDescription)
    }
}
