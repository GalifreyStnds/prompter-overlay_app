package us.jeremycombs.prompter

/**
 * Pure scrolling and geometry math. No Android classes, so it is unit testable on the JVM.
 */
object ScrollMath {
    /** Longest frame time we honor. A stalled frame must not make the text jump. */
    const val MAX_FRAME_SECONDS = 0.1f

    /**
     * Returns the new scroll position after [dtSeconds] at [speedPxPerSec].
     * The result never exceeds [max] and never goes below zero.
     */
    fun advance(position: Float, speedPxPerSec: Float, dtSeconds: Float, max: Float): Float {
        val dt = dtSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
        val upper = max.coerceAtLeast(0f)
        return (position + speedPxPerSec * dt).coerceIn(0f, upper)
    }

    /** True when there is nothing left to scroll. */
    fun atEnd(position: Float, max: Float): Boolean = max <= 0f || position >= max

    /** Like coerceIn, but tolerates inverted bounds by favoring [min]. */
    fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, maxOf(min, max))
}
