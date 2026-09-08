package io.github.jamesgoodwin.remotecompose.parser

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes an encoded raster image (PNG, JPEG, or WEBP, per the bitmap pool section of the `.rc`
 * format) into a Compose [ImageBitmap].
 *
 * Declared `expect` because commonMain has no image codec of its own: iOS and desktop decode via
 * Skia (Skiko), Android decodes via `android.graphics.BitmapFactory`. See the `actual`
 * implementations under `iosMain`, `desktopMain`, and `androidMain`.
 *
 * @throws Exception if [bytes] cannot be decoded as a supported image format. Callers such as
 *   [BitmapPool.get] are expected to catch this and degrade gracefully (e.g. skip the image)
 *   rather than let a single malformed asset abort the whole render.
 */
internal expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap
