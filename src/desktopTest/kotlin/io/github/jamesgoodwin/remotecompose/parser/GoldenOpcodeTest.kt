package io.github.jamesgoodwin.remotecompose.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Pins the exact flattened opcode list each fixture parses to, so refactors of the parser
 * (splitting it into per-opcode operations, moving evaluation) can be checked for zero
 * behaviour change. A missing golden file is written on first run and the test fails so the
 * new file is reviewed and committed deliberately.
 */
class GoldenOpcodeTest {

    private fun check(name: String) {
        val document = RemoteComposeParser.parse(File("tools/rc-writer/$name.rc").readBytes())
        val actual = buildString {
            appendLine("header=${document.header}")
            appendLine("strings=${document.strings.toSortedMap()}")
            document.opcodes.forEach { appendLine(it.toString()) }
        }
        val golden = File("src/desktopTest/resources/golden/$name.opcodes.txt")
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("Golden file created at ${golden.path}; review and commit it, then rerun.")
        }
        assertEquals(golden.readText(), actual, "opcode list for $name.rc changed")
    }

    @Test fun sample() = check("sample")
    @Test fun showcase() = check("showcase")
    @Test fun paint() = check("paint")
    @Test fun anim() = check("anim")
}
