package dev.sebastian.vozlocal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingButtonDockPolicyTest {

    @Test
    fun testCloserToRight() {
        val screenWidth = 1080
        val btnSize = 150

        // Button on the far left
        assertFalse(FloatingButtonDockPolicy.isCloserToRight(0, screenWidth, btnSize))
        assertFalse(FloatingButtonDockPolicy.isCloserToRight(100, screenWidth, btnSize))

        // Center boundary
        // Center of screen is 540. Button center: X + 75.
        // If X = 464, center = 539 (< 540 -> closer to left)
        assertFalse(FloatingButtonDockPolicy.isCloserToRight(464, screenWidth, btnSize))
        // If X = 466, center = 541 (> 540 -> closer to right)
        assertTrue(FloatingButtonDockPolicy.isCloserToRight(466, screenWidth, btnSize))

        // Button on the far right
        assertTrue(FloatingButtonDockPolicy.isCloserToRight(900, screenWidth, btnSize))
    }

    @Test
    fun testComputeDockedX_zeroInsets() {
        val screenWidth = 1080
        val btnSize = 150
        val edgeMargin = 30

        // Left dock
        val leftX = FloatingButtonDockPolicy.computeDockedX(
            buttonScreenX = 100,
            screenWidth = screenWidth,
            btnSize = btnSize,
            edgeMargin = edgeMargin,
            safeLeft = 0,
            safeRight = 0
        )
        assertEquals(30, leftX)

        // Right dock
        val rightX = FloatingButtonDockPolicy.computeDockedX(
            buttonScreenX = 800,
            screenWidth = screenWidth,
            btnSize = btnSize,
            edgeMargin = edgeMargin,
            safeLeft = 0,
            safeRight = 0
        )
        // 1080 - 0 - 150 - 30 = 900
        assertEquals(900, rightX)
    }

    @Test
    fun testComputeDockedX_withCutoutInsets() {
        val screenWidth = 1080
        val btnSize = 150
        val edgeMargin = 30
        val safeLeft = 70
        val safeRight = 50

        // Left dock respecting left inset
        val leftX = FloatingButtonDockPolicy.computeDockedX(
            buttonScreenX = 200,
            screenWidth = screenWidth,
            btnSize = btnSize,
            edgeMargin = edgeMargin,
            safeLeft = safeLeft,
            safeRight = safeRight
        )
        assertEquals(100, leftX) // 70 + 30

        // Right dock respecting right inset
        val rightX = FloatingButtonDockPolicy.computeDockedX(
            buttonScreenX = 900,
            screenWidth = screenWidth,
            btnSize = btnSize,
            edgeMargin = edgeMargin,
            safeLeft = safeLeft,
            safeRight = safeRight
        )
        // 1080 - 50 - 150 - 30 = 850
        assertEquals(850, rightX)
    }

    @Test
    fun testConstants() {
        assertEquals("vozlocal_prefs", FloatingButtonDockPolicy.PREFS_NAME)
        assertEquals("button_screen_x", FloatingButtonDockPolicy.PREF_KEY_X)
        assertEquals("button_screen_y", FloatingButtonDockPolicy.PREF_KEY_Y)
        assertEquals(50, FloatingButtonDockPolicy.DEFAULT_X)
        assertEquals(500, FloatingButtonDockPolicy.DEFAULT_Y)
    }
}
