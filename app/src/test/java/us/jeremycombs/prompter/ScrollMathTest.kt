package us.jeremycombs.prompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollMathTest {

    @Test
    fun advancesBySpeedTimesDelta() {
        assertEquals(10f, ScrollMath.advance(0f, 100f, 0.1f, 1000f), 0.001f)
    }

    @Test
    fun longFrameIsCappedSoTextDoesNotJump() {
        assertEquals(10f, ScrollMath.advance(0f, 100f, 5f, 1000f), 0.001f)
    }

    @Test
    fun neverPassesMaximum() {
        assertEquals(50f, ScrollMath.advance(49f, 100f, 0.1f, 50f), 0.001f)
    }

    @Test
    fun negativeDeltaDoesNotMoveBackwards() {
        assertEquals(5f, ScrollMath.advance(5f, 100f, -1f, 100f), 0.001f)
    }

    @Test
    fun negativeMaxIsTreatedAsZero() {
        assertEquals(0f, ScrollMath.advance(0f, 100f, 0.1f, -20f), 0.001f)
    }

    @Test
    fun atEndWhenNothingToScroll() {
        assertTrue(ScrollMath.atEnd(0f, 0f))
    }

    @Test
    fun atEndOnlyAtMaximum() {
        assertFalse(ScrollMath.atEnd(99f, 100f))
        assertTrue(ScrollMath.atEnd(100f, 100f))
    }

    @Test
    fun clampKeepsValueInsideBounds() {
        assertEquals(5, ScrollMath.clamp(5, 0, 10))
        assertEquals(0, ScrollMath.clamp(-3, 0, 10))
        assertEquals(10, ScrollMath.clamp(99, 0, 10))
    }

    @Test
    fun clampToleratesInvertedBounds() {
        assertEquals(10, ScrollMath.clamp(5, 10, 3))
    }

    @Test
    fun rewindUsesBlankLinesAndStepsBackFromAnExactStop() {
        val script = "First\nStill first\n \t\nSecond\nStill second\n\nThird"
        val second = script.indexOf("Second")
        val third = script.indexOf("Third")
        assertEquals(second, ScrollMath.rewindTarget(script, third) { it })
        assertEquals(second, ScrollMath.rewindTarget(script, second + 3) { it })
        assertEquals(0, ScrollMath.rewindTarget(script, second) { it })
    }

    @Test
    fun rewindIgnoresLeadingAndTrailingBlankLinesAndHandlesCrLf() {
        val script = "\r\nFirst\r\n \r\nSecond\r\n\r\n"
        val second = script.indexOf("Second")
        assertEquals(second, ScrollMath.rewindTarget(script, script.length) { it })
        assertEquals(0, ScrollMath.rewindTarget(script, 0) { it })
        assertEquals(0, ScrollMath.rewindTarget("Only one\nwrapped paragraph", 20) { it })
    }
}
