package io.github.jamesgoodwin.remotecompose.model

import androidx.compose.ui.graphics.Color

/**
 * A snapshot of the cumulative paint state in effect when a draw opcode was issued.
 *
 * Mirrors the subset of `android.graphics.Paint` that the real player's `PaintContext` drives
 * from `PaintBundle` attributes (see `PaintBundle.applyPaintChange` in `remote-core`). Defaults
 * are Android's own `Paint()` defaults: opaque black, fill, hairline stroke, butt cap, miter
 * join with limit 4, 12px text.
 *
 * Color carries its alpha channel the same way `Paint.setColor`/`Paint.setAlpha` do: `ALPHA`
 * replaces the alpha of the current color rather than being a separate multiplier.
 *
 * @property textSize Font size in document pixels, not sp. The renderer converts with the
 *   `DrawScope`'s own density so text scales with the geometry it sits next to.
 * @property gradient When non-null, replaces [color] as the fill/stroke source, as a shader does
 *   on an Android `Paint`. Cleared by a `SHADER` attribute with id 0.
 * @property shaderId The `DATA_SHADER` a `PaintBundle.SHADER` attribute named, or null. Takes
 *   precedence over [gradient] and [color], as a shader does on a real paint.
 * @property font The `DATA_FONT` a `PaintBundle.TYPEFACE` attribute named, or null when it named
 *   one of the built-in families in [fontFamily] instead. Takes precedence over [fontFamily].
 * @property blendMode `PaintBundle.BLEND_MODE_*` ordinal, or null for the default source-over.
 */
public data class PaintStyle(
    val color: Color,
    val style: PaintStyleKind,
    val strokeWidth: Float = 0f,
    val strokeCap: StrokeCapKind = StrokeCapKind.BUTT,
    val strokeJoin: StrokeJoinKind = StrokeJoinKind.MITER,
    val strokeMiter: Float = 4f,
    val textSize: Float = DEFAULT_TEXT_SIZE_PX,
    val fontWeight: Int = 400,
    val fontItalic: Boolean = false,
    val fontFamily: FontFamilyKind = FontFamilyKind.DEFAULT,
    val font: EmbeddedFont? = null,
    val gradient: GradientSpec? = null,
    val shaderId: Int? = null,
    val blendMode: Int? = null,
) {
    public companion object {
        /** `android.graphics.Paint`'s default text size. */
        public const val DEFAULT_TEXT_SIZE_PX: Float = 12f
    }
}

/** `PaintBundle.STYLE_FILL` / `STYLE_STROKE` / `STYLE_FILL_AND_STROKE`, in ordinal order. */
public enum class PaintStyleKind {
    FILL,
    STROKE,
    FILL_AND_STROKE,
}

/** `android.graphics.Paint.Cap` ordinals as written by `PaintBundle.setStrokeCap`. */
public enum class StrokeCapKind {
    BUTT,
    ROUND,
    SQUARE,
}

/** `android.graphics.Paint.Join` ordinals as written by `PaintBundle.setStrokeJoin`. */
public enum class StrokeJoinKind {
    MITER,
    ROUND,
    BEVEL,
}

/** `PaintBundle.FONT_TYPE_*` ordinals as written by `PaintBundle.setTextStyle`. */
public enum class FontFamilyKind {
    DEFAULT,
    SANS_SERIF,
    SERIF,
    MONOSPACE,
}

/**
 * A gradient shader decoded from a `PaintBundle.GRADIENT` attribute. Coordinates are in document
 * space, the same space every draw opcode's own geometry uses.
 *
 * @property stops Optional per-color positions in `0..1`; null means evenly spaced.
 * @property tileMode `android.graphics.Shader.TileMode` ordinal: 0 clamp, 1 repeat, 2 mirror.
 */
public sealed interface GradientSpec {
    public val colors: List<Color>
    public val stops: List<Float>?

    public data class Linear(
        override val colors: List<Color>,
        override val stops: List<Float>?,
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val tileMode: Int,
    ) : GradientSpec

    public data class Radial(
        override val colors: List<Color>,
        override val stops: List<Float>?,
        val centerX: Float, val centerY: Float,
        val radius: Float,
        val tileMode: Int,
    ) : GradientSpec

    public data class Sweep(
        override val colors: List<Color>,
        override val stops: List<Float>?,
        val centerX: Float, val centerY: Float,
    ) : GradientSpec
}

/**
 * One segment of a reconstructed path, as emitted by `OP_DRAW_PATH` / `OP_CLIP_PATH`. Mirrors the
 * subset of `androidx.compose.ui.graphics.Path` construction calls the renderer needs to support:
 * move, line, quadratic and cubic Bézier, and close.
 */
public sealed interface PathCommand {
    public data class MoveTo(val x: Float, val y: Float) : PathCommand
    public data class LineTo(val x: Float, val y: Float) : PathCommand
    public data class QuadraticTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
    ) : PathCommand
    public data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
    ) : PathCommand
    public data object Close : PathCommand
}

/**
 * A `DATA_SHADER`: the source of a runtime shader and the uniforms the document set on it.
 *
 * Not a data class, because two of its three fields are maps of arrays and the generated equality
 * would compare those by identity.
 */
public class ShaderSpec(
    public val source: String,
    public val floatUniforms: Map<String, FloatArray>,
    public val intUniforms: Map<String, IntArray>,
)
