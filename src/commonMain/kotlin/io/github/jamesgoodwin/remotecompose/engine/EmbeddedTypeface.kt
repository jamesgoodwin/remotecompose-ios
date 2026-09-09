package io.github.jamesgoodwin.remotecompose.engine

import androidx.compose.ui.text.font.FontFamily

/**
 * A font family holding the one typeface in [bytes], or null if this platform cannot read them.
 *
 * `DATA_FONT` carries a font file, and a paint names it by id where it would otherwise name one
 * of the four built-in families. The official player builds a typeface out of those bytes and
 * hands it to the paint directly (`ComposePaintChanges.setTypeFace(int, ...)`), so the requested
 * weight and slant only describe the file rather than restyle it; a family of one loaded typeface
 * is the Compose equivalent, since its resolver returns that typeface whatever is asked of it.
 *
 * Null rather than an exception for bytes that are not a font this platform can read: a document
 * that carries a broken font should still draw its text in the default face.
 */
internal expect fun embeddedFontFamily(bytes: ByteArray): FontFamily?
