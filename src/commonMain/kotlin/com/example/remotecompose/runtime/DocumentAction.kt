package com.example.remotecompose.runtime

/**
 * One entry of a component's action list, as written inside `MODIFIER_CLICK` and the
 * `MODIFIER_TOUCH_*` modifiers. Each mirrors the `runAction` of the operation of the same name
 * in `androidx.compose.remote.core.operations.layout.modifiers`.
 */
sealed interface DocumentAction {

    /** `HostActionOperation`: hands [actionId] and optional [metadata] to the host application. */
    data class Host(val actionId: Int, val metadata: String? = null) : DocumentAction

    /** `ValueFloatChangeActionOperation`: `context.overrideFloat(targetId, value)`. */
    data class SetFloat(val targetId: Int, val value: Float) : DocumentAction

    /** `ValueIntegerChangeActionOperation`: `context.overrideInteger(targetId, value)`. */
    data class SetInteger(val targetId: Int, val value: Int) : DocumentAction

    /** `ValueStringChangeActionOperation`: copies the text at [sourceId] into [targetId]. */
    data class SetText(val targetId: Int, val sourceId: Int) : DocumentAction

    /** `ValueFloatExpressionChangeActionOperation`: evaluates the expression with id [expressionId] into [targetId]. */
    data class SetFloatFromExpression(val targetId: Int, val expressionId: Int) : DocumentAction

    /** `ValueIntegerExpressionChangeActionOperation`: the integer counterpart of [SetFloatFromExpression]. */
    data class SetIntegerFromExpression(val targetId: Int, val expressionId: Int) : DocumentAction
}

/** Which gesture an action list is attached to. */
enum class ActionTrigger { CLICK, TOUCH_DOWN, TOUCH_UP, TOUCH_CANCEL }

/**
 * A laid-out component's hit rectangle in window coordinates, with the actions each gesture runs.
 * Produced per frame by [com.example.remotecompose.layout.LayoutEngine.collectHitRegions].
 */
data class HitRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val actions: Map<ActionTrigger, List<DocumentAction>>,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}
