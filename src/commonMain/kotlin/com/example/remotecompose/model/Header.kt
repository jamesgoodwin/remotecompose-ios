package com.example.remotecompose.model

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.parser.BufferReader
import com.example.remotecompose.parser.RemoteComposeParseException

/**
 * Document-level metadata parsed from the front of a `.rc` payload: the magic/version marker
 * followed by the canvas's intrinsic size, background color, and a capability flag bitmask.
 *
 * @property versionMajor Major protocol version, read from byte 3 of the magic header.
 * @property versionMinor Minor protocol version, read from byte 4 of the magic header.
 * @property width Intrinsic document width in density-independent pixels.
 * @property height Intrinsic document height in density-independent pixels.
 * @property backgroundColor Canvas background, decoded from a packed ARGB int.
 * @property capabilities Bitmask of optional feature flags the document may rely on (e.g.
 *   whether it contains animated variables or interactive actions). Individual bits are not yet
 *   assigned meaning by this renderer; unrecognized bits should simply be ignored by consumers.
 */
data class Header(
    val versionMajor: Int,
    val versionMinor: Int,
    val width: Int,
    val height: Int,
    val backgroundColor: Color,
    val capabilities: Long,
) {
    companion object {
        /** First two magic bytes: ASCII 'R', 'C'. */
        private const val MAGIC_BYTE_0 = 0x52
        private const val MAGIC_BYTE_1 = 0x43

        /**
         * Parses the header section from [reader], which must be positioned at the very start of
         * the payload. Validates the two-byte 'R','C' marker before consuming the version bytes.
         *
         * @throws RemoteComposeParseException if the magic bytes do not match.
         */
        fun read(reader: BufferReader): Header {
            val magic0 = reader.readU8()
            val magic1 = reader.readU8()
            if (magic0 != MAGIC_BYTE_0 || magic1 != MAGIC_BYTE_1) {
                throw RemoteComposeParseException(
                    "Not a Remote Compose stream: expected magic bytes " +
                        "0x${MAGIC_BYTE_0.toString(16)} 0x${MAGIC_BYTE_1.toString(16)}, " +
                        "got 0x${magic0.toString(16)} 0x${magic1.toString(16)}"
                )
            }
            val versionMajor = reader.readU8()
            val versionMinor = reader.readU8()

            val width = reader.readS32()
            val height = reader.readS32()
            val backgroundColor = reader.readColor()
            val capabilities = reader.readVarUInt()

            return Header(
                versionMajor = versionMajor,
                versionMinor = versionMinor,
                width = width,
                height = height,
                backgroundColor = backgroundColor,
                capabilities = capabilities,
            )
        }
    }
}
