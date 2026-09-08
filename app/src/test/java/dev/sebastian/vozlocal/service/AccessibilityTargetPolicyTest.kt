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
        stableId = "view:com.example.notes:id/editor",
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
        assertFalse(AccessibilityTargetPolicy.matchesRecordingTarget(allowed, allowed.copy(stableId = "view:com.example.notes:id/other")))
        assertFalse(AccessibilityTargetPolicy.matchesRecordingTarget(allowed, allowed.copy(stableId = null)))
    }

    @Test
    fun insertionFailsClosedWithoutFrameworkIdentity() {
        assertFalse(AccessibilityTargetPolicy.hasStableIdentity(allowed.copy(stableId = null)))
        assertTrue(AccessibilityTargetPolicy.hasStableIdentity(allowed))
    }

    @Test
    fun placeholderDetectionUsesFrameworkHintStateAndEmptyControls() {
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText(""))
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("   "))
        assertTrue(AccessibilityTargetPolicy.isPlaceholderText("Custom Hint", isShowingHintText = true))
    }

    @Test
    fun placeholderDetectionPreservesAmbiguousNonemptyText() {
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("message"))
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("buscar"))
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("Ask Google"))
        assertFalse(
            AccessibilityTargetPolicy.isPlaceholderText(
                "Enter your query here",
                hintText = "Enter your query here",
                selectionStart = 21,
                selectionEnd = 21,
            )
        )
        assertFalse(
            AccessibilityTargetPolicy.isPlaceholderText(
                "Search web",
                contentDescription = "Search web",
                selectionStart = 10,
                selectionEnd = 10,
            )
        )
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("Hello world this is my dictation"))
        assertFalse(AccessibilityTargetPolicy.isPlaceholderText("My bank note"))
    }

    @Test
    fun messagingPromptWithoutCursorIsTreatedAsPlaceholder() {
        assertTrue(
            AccessibilityTargetPolicy.isPlaceholderText(
                text = "Message",
                hintText = "Message",
                selectionStart = -1,
                selectionEnd = -1,
            )
        )
        assertTrue(
            AccessibilityTargetPolicy.isPlaceholderText(
                text = "Message",
                contentDescription = "Message",
                selectionStart = -1,
                selectionEnd = -1,
            )
        )

        val result = AccessibilityTargetPolicy.computeInsertionText(
            rawText = "Message",
            textToInsert = "this is my dictation",
            isPlaceholder = true,
        )
        org.junit.Assert.assertEquals("this is my dictation", result)
    }

    @Test
    fun matchingPromptWithCursorIsPreservedAsUserText() {
        assertFalse(
            AccessibilityTargetPolicy.isPlaceholderText(
                text = "Message",
                contentDescription = "Message",
                selectionStart = 7,
                selectionEnd = 7,
            )
        )

        val result = AccessibilityTargetPolicy.computeInsertionText(
            rawText = "Message",
            textToInsert = " received",
            selectionStart = 7,
            selectionEnd = 7,
            isPlaceholder = false,
        )
        org.junit.Assert.assertEquals("Message received", result)
    }

    @Test
    fun placeholderDetectionPrefersFrameworkStateOverMatchingMetadata() {
        assertTrue(
            AccessibilityTargetPolicy.isPlaceholderText(
                text = "Enter your query here",
                hintText = "Enter your query here",
                isShowingHintText = true
            )
        )
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

    @Test
    fun computeInsertionRespectsSelectionInAmbiguousText() {
        val result = AccessibilityTargetPolicy.computeInsertionText(
            rawText = "buscar ahora",
            textToInsert = "después",
            selectionStart = 0,
            selectionEnd = 6,
            isPlaceholder = false
        )
        org.junit.Assert.assertEquals("después ahora", result)
    }
}
