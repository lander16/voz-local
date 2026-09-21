package dev.sebastian.vozlocal.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayEligibilityPolicyTest {
    private val app = AccessibilityWindowSnapshot(
        id = 12,
        type = OverlayEligibilityPolicy.TYPE_APPLICATION,
        active = true,
        focused = true,
    )
    private val ime = AccessibilityWindowSnapshot(
        id = 30,
        type = OverlayEligibilityPolicy.TYPE_INPUT_METHOD,
        active = false,
        focused = false,
    )
    private val target = AccessibilityTarget(
        packageName = "com.example.notes",
        windowId = 12,
        editable = true,
        enabled = true,
        password = false,
        accessibilityDataSensitive = false,
        stableId = "view:editor",
    )

    @Test
    fun requiresOneActiveFocusedApplicationAndOneImeWindow() {
        assertEquals(12, OverlayEligibilityPolicy.activeApplicationWindowId(listOf(app, ime)))
        assertTrue(OverlayEligibilityPolicy.canReadFocusedTarget(listOf(app, ime)))
        assertFalse(OverlayEligibilityPolicy.canReadFocusedTarget(listOf(app)))
        assertFalse(OverlayEligibilityPolicy.canReadFocusedTarget(listOf(app, ime, ime.copy(id = 31))))
    }

    @Test
    fun focusedApplicationRemainsEligibleWhenImeBecomesActive() {
        assertEquals(12, OverlayEligibilityPolicy.activeApplicationWindowId(listOf(app.copy(active = false), ime.copy(active = true))))
    }

    @Test
    fun focusedApplicationAllowsAccessibilityOverlayButRejectsUnknownActiveSurface() {
        val overlay = AccessibilityWindowSnapshot(
            id = 44,
            type = OverlayEligibilityPolicy.TYPE_ACCESSIBILITY_OVERLAY,
            active = true,
            focused = false,
        )
        assertEquals(12, OverlayEligibilityPolicy.activeApplicationWindowId(listOf(app.copy(active = false), ime, overlay)))
        assertNull(
            OverlayEligibilityPolicy.activeApplicationWindowId(
                listOf(app.copy(active = false), ime, overlay.copy(type = 3))
            )
        )
    }

    @Test
    fun multipleApplicationWindowsFailClosed() {
        val secondary = app.copy(id = 13, active = false, focused = false)
        assertNull(OverlayEligibilityPolicy.activeApplicationWindowId(listOf(app, secondary, ime)))
        assertFalse(OverlayEligibilityPolicy.isEligibleTarget(listOf(app, secondary, ime), target, emptySet()))
    }

    @Test
    fun targetMustBelongToTheFocusedApplicationWindow() {
        assertTrue(OverlayEligibilityPolicy.isEligibleTarget(listOf(app, ime), target, emptySet()))
        assertFalse(OverlayEligibilityPolicy.isEligibleTarget(listOf(app, ime), target.copy(windowId = 99), emptySet()))
        assertFalse(OverlayEligibilityPolicy.isEligibleTarget(listOf(app, ime), target.copy(password = true), emptySet()))
        assertFalse(OverlayEligibilityPolicy.isEligibleTarget(listOf(app, ime), target.copy(stableId = null), emptySet()))
    }

    @Test
    fun deniedPackageIsRejectedAfterMetadataGate() {
        assertFalse(
            OverlayEligibilityPolicy.isEligibleTarget(
                listOf(app, ime), target, setOf("com.example.notes")
            )
        )
    }
}
