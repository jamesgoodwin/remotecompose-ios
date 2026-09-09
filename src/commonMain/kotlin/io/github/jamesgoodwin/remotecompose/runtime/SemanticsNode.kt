package io.github.jamesgoodwin.remotecompose.runtime

/**
 * What a document says about one of its components, for a screen reader: an
 * `ACCESSIBILITY_SEMANTICS` modifier with its label ids resolved and its box laid out.
 *
 * A document says nothing about itself unless it was written to: text a component draws is drawn
 * rather than labelled, so anything a reader hears is here because the document put it here.
 *
 * Nested rather than flat, because [mode] is about a component and the ones inside it: a `MERGE`
 * is read as one thing together with its children, and a `CLEAR_AND_SET` replaces them. That
 * nesting is also the reading order — the format has no traversal index, so the order components
 * were written in is the only order there is.
 *
 * Coordinates are in document space, the same space [HitRegion] uses; a host drawing the document
 * scaled must map them the same way it maps a touch.
 *
 * @property contentDescription What to read out, or null when the document named no description.
 * @property text The component's text as a reader should hear it, when the document named one
 *   separately from [contentDescription].
 * @property stateDescription What state it is in ("On", "Not yet refreshed") — often a value the
 *   document computes, so it is resolved fresh on every frame.
 * @property role What kind of control it is, for a reader that announces that.
 * @property enabled False when the document says the control is there but not usable.
 * @property clickable Whether a reader should offer to activate it. A host that acts on this
 *   should send the click through [io.github.jamesgoodwin.remotecompose.parser.RemoteComposeDocument.click]
 *   at the middle of [left]..[right] and [top]..[bottom], as a finger would.
 */
public data class SemanticsNode(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val contentDescription: String?,
    val text: String?,
    val stateDescription: String?,
    val role: SemanticsRole,
    val mode: SemanticsMode,
    val enabled: Boolean,
    val clickable: Boolean,
    val children: List<SemanticsNode>,
) {
    /**
     * What a reader should say for this node, as one string: its own description and text, and —
     * when it is a [SemanticsMode.MERGE] — those of everything it merges, in the order they were
     * written. Null when neither it nor anything under it says anything.
     *
     * The merge is done here rather than left to the platform because the platforms do not agree:
     * Compose's own descendant merging reaches a screen reader on some and arrives empty on
     * others, and a control that a reader focuses and says nothing about is worse than one it
     * skips. A [SemanticsMode.CLEAR_AND_SET] replaces what is under it, so nothing but its own
     * labels is read.
     */
    public fun spokenLabel(): String? {
        val parts = mutableListOf<String>()
        fun collect(node: SemanticsNode, descend: Boolean) {
            node.contentDescription?.let { parts += it }
            node.text?.let { parts += it }
            if (descend) for (child in node.children) collect(child, child.mode != SemanticsMode.CLEAR_AND_SET)
        }
        collect(this, mode == SemanticsMode.MERGE)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/** `AccessibleComponent.Role`, in the order the wire byte indexes them. */
public enum class SemanticsRole {
    BUTTON,
    CHECKBOX,
    SWITCH,
    RADIO_BUTTON,
    TAB,
    IMAGE,
    DROPDOWN_LIST,
    PICKER,
    CAROUSEL,
    UNKNOWN,
    ;

    public companion object {
        /** `Role.fromInt`: anything at or past [UNKNOWN] is unknown. */
        public fun fromWire(value: Int): SemanticsRole = entries.getOrElse(value) { UNKNOWN }
    }
}

/**
 * `AccessibleComponent.Mode`: how this component's semantics sit with those inside it. The
 * official player maps these onto `Modifier.semantics`, `clearAndSetSemantics` and
 * `semantics(mergeDescendants = true)`.
 */
public enum class SemanticsMode {
    SET,
    CLEAR_AND_SET,
    MERGE,
    ;

    public companion object {
        /** `CoreSemantics.modeFromInt`: anything out of range is [SET]. */
        public fun fromWire(value: Int): SemanticsMode = entries.getOrElse(value) { SET }
    }
}
