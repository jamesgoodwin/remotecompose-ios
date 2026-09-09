package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DATA_SHADER` against `tools/rc-writer/shader.rc` (see `buildShaderSample()` in the writer
 * tool). The record carries source and named uniforms, and the
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
        assertEquals(2, rects.size)
        assertEquals(1, circles.size)
        assertEquals(8f, rects[0].left)
        assertEquals(60f, rects[1].left)
    }

    @Test
    fun onlyTheShadedRectNamesTheShader() {
        // The paint carries the id; compiling it is the executor's business, and whether a
        // platform can do it is not something the opcode list knows or should.
        val rects = document.opcodes.filterIsInstance<Opcode.DrawRect>()
        assertEquals(null, rects[0].paint.shaderId, "the plain rect names none")
        val shaderId = rects[1].paint.shaderId
        assertTrue(shaderId != null && shaderId != 0, "the shaded rect names one: $shaderId")
        assertEquals(null, rects[1].paint.gradient, "a shader is not a gradient")
    }

    @Test
    fun theShaderSourceReachesTheFrameUnderThatId() {
        // What the executor looks up when it comes to compile: the source out of the string pool
        // and the uniforms the document set, under the id the paint named.
        val id = document.opcodes.filterIsInstance<Opcode.DrawRect>()[1].paint.shaderId
        val spec = document.shaders.getValue(id!!)
        assertTrue(spec.source.contains("half4 main"), "the source came with it")
        assertEquals(listOf(120f, 120f), spec.floatUniforms.getValue("iResolution").toList())
        assertEquals(listOf(4), spec.intUniforms.getValue("iSteps").toList())
    }
}
