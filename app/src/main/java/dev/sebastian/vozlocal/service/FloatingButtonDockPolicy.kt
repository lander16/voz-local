package dev.sebastian.vozlocal.service

/**
 * Calculations and constants for floating mic button positioning, safe docking, and persistence.
 */
object FloatingButtonDockPolicy {
    const val PREFS_NAME = "vozlocal_prefs"
    const val PREF_KEY_X = "button_screen_x"
    const val PREF_KEY_Y = "button_screen_y"
    const val DEFAULT_X = 50
    const val DEFAULT_Y = 500

    /**
     * Determines whether the floating button is closer to the left or right edge of the screen.
     * Uses button center to evaluate which bezel is closest.
     */
    fun isCloserToRight(buttonScreenX: Int, screenWidth: Int, btnSize: Int = 0): Boolean {
        return (buttonScreenX + btnSize / 2) > (screenWidth / 2)
    }

    /**
     * Computes the target docked X position.
     * - If closer to the left: safeLeft + edgeMargin
     * - If closer to the right: screenWidth - safeRight - btnSize - edgeMargin
     */
    fun computeDockedX(
        buttonScreenX: Int,
        screenWidth: Int,
        btnSize: Int,
        edgeMargin: Int,
        safeLeft: Int = 0,
        safeRight: Int = 0,
    ): Int {
        val closerToRight = isCloserToRight(buttonScreenX, screenWidth, btnSize)
        return if (closerToRight) {
            screenWidth - safeRight - btnSize - edgeMargin
        } else {
            safeLeft + edgeMargin
        }
    }
}
