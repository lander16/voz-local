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

    @Test
    fun placeholderDetectionMatchesAskGoogleAndCommonHints() {
        // "Ask Google" placeholder without hintText attribute
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Ask Google"))
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("ask google"))
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Search"))
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Buscar"))
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Type a message"))

        // With isShowingHintText = true
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Custom Hint", isShowingHintText = true))

        // When text matches hintText
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Enter your query here", hintText = "Enter your query here"))

        // When text matches contentDescription
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Search web", contentDescription = "Search web"))

        // Real user text should NOT be flagged as placeholder
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("Hello world this is my dictation"))
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("My bank note"))
    }

    @Test
    fun computeInsertionReplacesPlaceholderCleanlyWithoutPrefixing() {
        // Bug reproduction: Field has "Ask Google", dictation is "what is the weather"
        val result = AccessibilityTargetPolicy.computeInsertionText(
            rawText = "Ask Google",
            textToInsert = "what is the weather",
            selectionStart = -1,
            selectionEnd = -1,
            isPlaceholder = true
        )
        org.junit.Assert.assertEquals("what is the weather", result)
    }

    @Test
    fun computeInsertionAppendsToExistingUserText() {
        val result = AccessibilityTargetPolicy.computeInsertionText(
            rawText = "Hello",
            textToInsert = "world",
            selectionStart = -1,
            selectionEnd = -1,
            isPlaceholder = false
        )
        org.junit.Assert.assertEquals("Hello world", result)
    }
}
