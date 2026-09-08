package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DATA_SHADER` against `tools/rc-writer/shader.rc` (see `buildShaderSample()` in the writer
 * tool). Painting a shader needs a runtime shader compiler this renderer does not have, so the
 * contract this locks down is the other one: the record decodes, its uniforms survive, and the
 * draws around the shaded one are unaffected.
 */
class ShaderFixtureTest {

    private val bytes by lazy { fixture("shader") }
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    @Test
    fun theShaderRecordDecodesWithItsUniforms() {
        val shader = operations.filterIsInstance<Operation.ShaderData>().single()
        assertEquals(listOf(120f, 120f), shader.floatUniforms.getValue("iResolution").toList())
        assertEquals(listOf(4), shader.intUniforms.getValue("iSteps").toList())
        assertTrue(shader.bitmapUniforms.containsKey("iTexture"))
        // The source is a text-pool entry, not part of the record.
        assertTrue(document.strings.getValue(shader.shaderTextId).contains("half4 main"))
    }

    @Test
    fun theRestOfTheDocumentStillDraws() {
        val rects = document.opcodes.filterIsInstance<Opcode.DrawRect>()
        val circles = document.opcodes.filterIsInstance<Opcode.DrawCircle>()
        // Both rects are emitted, the shaded one with the colour it was left with; the shader
        // itself is not applied to either.
        assertEquals(2, rects.size)
        assertEquals(1, circles.size)
        assertEquals(8f, rects[0].left)
        assertEquals(60f, rects[1].left)
        assertEquals(null, rects[1].paint.gradient)
    }
}
