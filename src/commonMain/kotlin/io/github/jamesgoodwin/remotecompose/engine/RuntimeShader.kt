package io.github.jamesgoodwin.remotecompose.engine

import androidx.compose.ui.graphics.Brush

/**
 * Compiles a `DATA_SHADER`'s source into a brush, or returns null if this platform cannot.
 *
 * The format carries the shader as text plus named uniforms, which is the shape both platforms
 * take one in: `RuntimeShader` on Android, `RuntimeEffect` and `RuntimeShaderBuilder` on Skia. The
 * source itself is AGSL, which is Skia's own shading language under another name, so the same text
 * compiles on both.
 *
 * Null rather than an exception for a platform or a version that has no runtime shader, and for
 * source that does not compile: a shader that will not build should leave the shape drawn in its
 * paint colour rather than take the rest of the document down with it.
 */
internal expect fun runtimeShaderBrush(
    source: String,
    floatUniforms: Map<String, FloatArray>,
    intUniforms: Map<String, IntArray>,
): Brush?
