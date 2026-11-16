package dev.apkcli.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.widget.EdgeEffect
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * sora's CodeEditor as the editor pane hosts it: every gesture that starts on
 * it stays its own, and its edges do not stretch. Both are about scrolling.
 *
 * * sora, asked to let a parent scroll sideways
 *   (`setInterceptParentHorizontalScrollIfNeeded`), hands the gesture back
 *   whenever the finger drifts sideways at a horizontal edge, which on a file
 *   of short lines is every vertical drag. Nothing around the editor scrolls,
 *   and the split's divider is outside it, so here the gesture is claimed at
 *   its start and kept.
 * * While an edge effect is unfinished, sora turns a drag into stretching it
 *   instead of scrolling, and it releases the effect on ACTION_UP alone. A
 *   gesture that ended any other way left it pulled: drags then moved the
 *   text a little and stopped, until a pinch ended with an ACTION_UP. The
 *   edges here are [InertEdgeEffect]s, always at rest.
 */
internal class ProjectCodeEditor(context: Context) : CodeEditor(context) {

    // Nullable: CodeEditor's constructor runs before these are set, and
    // should it ask for an edge then, it gets its own.
    private val verticalEdge: EdgeEffect? = InertEdgeEffect(context)
    private val horizontalEdge: EdgeEffect? = InertEdgeEffect(context)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }

    // The touch handler and the renderer reach the edges through these alone.
    override fun getVerticalEdgeEffect(): EdgeEffect = verticalEdge ?: super.getVerticalEdgeEffect()

    override fun getHorizontalEdgeEffect(): EdgeEffect = horizontalEdge ?: super.getHorizontalEdgeEffect()
}

/** An edge effect that is never pulled and draws nothing. */
private class InertEdgeEffect(context: Context) : EdgeEffect(context) {
    override fun isFinished(): Boolean = true

    override fun onPull(deltaDistance: Float) = Unit

    override fun onPull(deltaDistance: Float, displacement: Float) = Unit

    override fun onPullDistance(deltaDistance: Float, displacement: Float): Float = 0f

    override fun getDistance(): Float = 0f

    override fun onRelease() = Unit

    override fun onAbsorb(velocity: Int) = Unit

    override fun draw(canvas: Canvas): Boolean = false
}
