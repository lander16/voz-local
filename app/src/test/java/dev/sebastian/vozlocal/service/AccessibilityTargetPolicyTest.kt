package dev.sebastian.vozlocal.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTargetPolicyTest {
    private val allowed = AccessibilityTarget(
        packageName = "com.example.notes",
        windowId = 7,
        editable = true,
        enabled = true,
        password = false,
        accessibilityDataSensitive = false,
    )

    @Test
    fun deniedPackagesAreRejectedBeforeNodeInspection() {
        assertFalse(AccessibilityTargetPolicy.canObservePackage("com.bank.app", setOf("com.bank.app")))
        assertFalse(AccessibilityTargetPolicy.canTarget(allowed.copy(packageName = "com.bank.app"), setOf("com.bank.app")))
    }

    @Test
    fun passwordAndSensitiveInputsAreNeverTargets() {
        assertFalse(AccessibilityTargetPolicy.canTarget(allowed.copy(password = true), emptySet()))
        assertFalse(AccessibilityTargetPolicy.canTarget(allowed.copy(accessibilityDataSensitive = true), emptySet()))
    }

    @Test
    fun targetMustBeEditableAndEnabled() {
        assertFalse(AccessibilityTargetPolicy.canTarget(allowed.copy(editable = false), emptySet()))
        assertFalse(AccessibilityTargetPolicy.canTarget(allowed.copy(enabled = false), emptySet()))
        assertTrue(AccessibilityTargetPolicy.canTarget(allowed, emptySet()))
    }

    @Test
    fun insertionRequiresTheOriginalPackageAndWindow() {
        assertTrue(AccessibilityTargetPolicy.matchesRecordingTarget(allowed, allowed.copy(editable = false)))
        assertFalse(AccessibilityTargetPolicy.matchesRecordingTarget(allowed, allowed.copy(windowId = 8)))
        assertFalse(AccessibilityTargetPolicy.matchesRecordingTarget(allowed, allowed.copy(packageName = "com.example.other")))
    }
}
