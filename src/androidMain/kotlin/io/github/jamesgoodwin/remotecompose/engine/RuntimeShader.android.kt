package io.github.jamesgoodwin.remotecompose.engine

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

/**
 * Android's own runtime shader, which is what the official player uses. It arrived in API 33, so
 * anything older gets null and draws the shape in its paint colour.
 */
internal actual fun runtimeShaderBrush(
    source: String,
    floatUniforms: Map<String, FloatArray>,
    intUniforms: Map<String, IntArray>,
): Brush? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return try {
        val shader = RuntimeShader(source)
        for ((name, values) in floatUniforms) shader.setFloatUniform(name, values)
        for ((name, values) in intUniforms) shader.setIntUniform(name, values)
        ShaderBrush(shader)
    } catch (_: Exception) {
        null
    }
}
