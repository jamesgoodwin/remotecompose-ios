package io.github.jamesgoodwin.remotecompose.engine

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** Skia's own runtime effect, which iOS and desktop both draw through. */
internal actual fun runtimeShaderBrush(
    source: String,
    floatUniforms: Map<String, FloatArray>,
    intUniforms: Map<String, IntArray>,
): Brush? = try {
    val effect = RuntimeEffect.makeForShader(source)
    val builder = RuntimeShaderBuilder(effect)
    for ((name, values) in floatUniforms) builder.setFloats(name, values)
    for ((name, values) in intUniforms) builder.setInts(name, values)
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader = builder.makeShader(null)
    }
} catch (_: Exception) {
    // A shader that will not compile leaves the shape in its paint colour.
    null
}

/** `RuntimeShaderBuilder` takes uniforms by arity rather than as an array of any length. */
private fun RuntimeShaderBuilder.setFloats(name: String, v: FloatArray) {
    when (v.size) {
        0 -> Unit
        1 -> uniform(name, v[0])
        2 -> uniform(name, v[0], v[1])
        3 -> uniform(name, v[0], v[1], v[2])
        4 -> uniform(name, v[0], v[1], v[2], v[3])
        else -> uniform(name, v)
    }
}

private fun RuntimeShaderBuilder.setInts(name: String, v: IntArray) {
    when (v.size) {
        0 -> Unit
        1 -> uniform(name, v[0])
        2 -> uniform(name, v[0], v[1])
        3 -> uniform(name, v[0], v[1], v[2])
        else -> uniform(name, v[0], v[1], v[2], v[3])
    }
}
