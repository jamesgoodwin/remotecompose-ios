package io.github.jamesgoodwin.remotecompose.layout

import io.github.jamesgoodwin.remotecompose.runtime.CubicEasing
import io.github.jamesgoodwin.remotecompose.runtime.Easing

/**
 * A component moving from where it was to where it now is, a transcription of
 * `AnimateMeasure` (remote-core 1.0.0-alpha18).
 *
 * The layout is recomputed from scratch every frame, so a component whose position or size
 * changed would otherwise jump. This holds the measure it had before the change and eases
 * towards the new one over [duration] seconds; [at] is the whole of what the real class does
 * with them, `original * (1 - p) + target * p` with `p` off the easing curve.
 */
internal class MeasureAnimation(
    var fromX: Float,
    var fromY: Float,
    var fromWidth: Float,
    var fromHeight: Float,
    var toX: Float,
    var toY: Float,
    var toWidth: Float,
    var toHeight: Float,
    val duration: Float,
    easingType: Int,
    var startedAt: Float,
) {
    private val easing: Easing = CubicEasing.preset(easingType)

    /** True while [at] would still be moving; a finished animation is left where it landed. */
    fun isRunning(now: Float): Boolean = duration > 0f && now - startedAt < duration

    /**
     * Retargets without restarting from scratch: the component is where it is now, and heads for
     * somewhere else from there, so an animation interrupted mid-way does not snap back.
     */
    fun retarget(x: Float, y: Float, width: Float, height: Float, now: Float) {
        val p = progress(now)
        fromX = fromX + (toX - fromX) * p
        fromY = fromY + (toY - fromY) * p
        fromWidth = fromWidth + (toWidth - fromWidth) * p
        fromHeight = fromHeight + (toHeight - fromHeight) * p
        toX = x
        toY = y
        toWidth = width
        toHeight = height
        startedAt = now
    }

    private fun progress(now: Float): Float {
        if (duration <= 0f) return 1f
        val fraction = ((now - startedAt) / duration).coerceIn(0f, 1f)
        return easing.get(fraction)
    }

    /** Puts [node] where it is [now], between where it came from and where it is going. */
    fun applyTo(node: LayoutNode, now: Float) {
        val p = progress(now)
        node.x = fromX + (toX - fromX) * p
        node.y = fromY + (toY - fromY) * p
        node.width = fromWidth + (toWidth - fromWidth) * p
        node.height = fromHeight + (toHeight - fromHeight) * p
    }
}
