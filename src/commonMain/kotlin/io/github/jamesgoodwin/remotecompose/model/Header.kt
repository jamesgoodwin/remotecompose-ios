package io.github.jamesgoodwin.remotecompose.model

/**
 * Document metadata from the leading `Operations.HEADER` record: `[major:i32][minor:i32]
 * [patch:i32][width:i32][height:i32][capabilities:i64]` (`Header.read` in `remote-core`; the
 * alternative map-encoded form, signalled by `major >= 65536`, is not produced by the alpha18
 * writer).
 *
 * @property width Intrinsic document width in document pixels.
 * @property height Intrinsic document height in document pixels.
 * @property capabilities Bitmask of features the document relies on; not yet interpreted.
 */
data class Header(
    val majorVersion: Int,
    val minorVersion: Int,
    val patchVersion: Int,
    val width: Int,
    val height: Int,
    val capabilities: Long,
)
