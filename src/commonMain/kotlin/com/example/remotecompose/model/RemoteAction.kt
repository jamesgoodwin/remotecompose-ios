package com.example.remotecompose.model

/**
 * An interaction event dispatched out of [com.example.remotecompose.ui.RemoteComposeCanvas] to
 * the host application, produced when a pointer gesture hits an `OP_ACTION_CLICK` opcode's
 * target rect.
 */
sealed interface RemoteAction {
    /** A tap on a region bound to a document-authored action ID (e.g. a link or navigation target). */
    data class Click(
        val actionId: Int,
        val targetUrl: String?,
        val payload: Map<String, Any>,
    ) : RemoteAction

    /** A tap on a region bound to an application-defined identifier not covered by [Click]. */
    data class Custom(
        val identifier: String,
        val params: Map<String, Any>,
    ) : RemoteAction
}
