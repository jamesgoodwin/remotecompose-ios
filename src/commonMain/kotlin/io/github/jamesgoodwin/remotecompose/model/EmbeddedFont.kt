package io.github.jamesgoodwin.remotecompose.model

import androidx.compose.ui.text.font.FontFamily
import io.github.jamesgoodwin.remotecompose.engine.embeddedFontFamily

/**
 * A font file the document carried in a `DATA_FONT` record, and the typeface built from it.
 *
 * A paint names one of these by id in place of a built-in family (`PaintStyle.font`), and both
 * the measure pass and the draw pass need the same typeface, so the document keeps one instance
 * per id and builds the typeface once, the first time text is actually laid out in it.
 *
 * @property id The `DATA_FONT` id, which is what the paint's `TYPEFACE` attribute names.
 * @property bytes The font file verbatim.
 */
public class EmbeddedFont internal constructor(
    public val id: Int,
    public val bytes: ByteArray,
) {
    /** Null when the platform could not read [bytes]; text then falls back to the default face. */
    internal val family: FontFamily? by lazy { embeddedFontFamily(bytes) }

    override fun toString(): String = "EmbeddedFont(id=$id, bytes=${bytes.size})"
}
