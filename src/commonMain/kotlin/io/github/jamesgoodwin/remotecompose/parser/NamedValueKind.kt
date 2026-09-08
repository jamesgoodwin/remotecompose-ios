package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext

/**
 * What kind of value a document named, and so which `setNamed...` call fills it in.
 *
 * `NamedVariable` writes this as an integer, and the integers are the wire format's rather than
 * this renderer's. They are not a public API — a host that reads [RemoteComposeDocument.namedValues]
 * wants to know it is being asked for a colour, not that the number is 2.
 */
public enum class NamedValueKind {
    STRING,
    FLOAT,
    COLOR,
    IMAGE,
    INT,
    LONG,
    /** 6 is both `FLOAT_ARRAY_TYPE` and `PATH_TYPE` upstream. */
    FLOAT_ARRAY,

    /** A type this renderer does not know, so that a new one does not become an exception. */
    UNKNOWN,
    ;

    internal companion object {
        fun of(type: Int): NamedValueKind = when (type) {
            RemoteContext.NAMED_STRING -> STRING
            RemoteContext.NAMED_FLOAT -> FLOAT
            RemoteContext.NAMED_COLOR -> COLOR
            RemoteContext.NAMED_IMAGE -> IMAGE
            RemoteContext.NAMED_INT -> INT
            RemoteContext.NAMED_LONG -> LONG
            RemoteContext.NAMED_FLOAT_ARRAY -> FLOAT_ARRAY
            else -> UNKNOWN
        }
    }
}
