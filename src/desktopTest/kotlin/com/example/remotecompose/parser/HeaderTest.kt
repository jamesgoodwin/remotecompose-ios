package com.example.remotecompose.parser

import com.example.remotecompose.model.Header
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderTest {

    @Test
    fun headerCarriesRealVersionAndSizeFields() {
        val document = RemoteComposeParser.parse(File("tools/rc-writer/paint.rc").readBytes())
        assertEquals(
            Header(majorVersion = 1, minorVersion = 1, patchVersion = 0, width = 200, height = 200, capabilities = 0L),
            document.header,
        )
    }

    @Test
    fun textPoolIsKeyedByWriterIds() {
        val document = RemoteComposeParser.parse(File("tools/rc-writer/paint.rc").readBytes())
        assertEquals("Big", document.strings[43])
        assertEquals("bold mono", document.strings[44])
    }
}
