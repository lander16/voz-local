package dev.sebastian.vozlocal.service

/** Immutable target facts, kept separate from framework node objects. */
data class AccessibilityTarget(
    val packageName: String,
    val windowId: Int,
    val editable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val accessibilityDataSensitive: Boolean,
)

/** Security boundary for the global dictation overlay. */
object AccessibilityTargetPolicy {
    fun canObservePackage(packageName: String?, deniedPackages: Set<String>): Boolean =
        !packageName.isNullOrBlank() && packageName !in deniedPackages

    fun canTarget(node: AccessibilityTarget?, deniedPackages: Set<String>): Boolean =
        node != null &&
            canObservePackage(node.packageName, deniedPackages) &&
            node.editable &&
            node.enabled &&
            !node.password &&
            !node.accessibilityDataSensitive

    fun matchesRecordingTarget(recordingTarget: AccessibilityTarget?, currentTarget: AccessibilityTarget?): Boolean =
        recordingTarget != null && currentTarget != null &&
            recordingTarget.packageName == currentTarget.packageName &&
            recordingTarget.windowId == currentTarget.windowId

    fun isPlaceholderText(
        text: String,
        hintText: String? = null,
        contentDescription: String? = null,
        isShowingHintText: Boolean = false
    ): Boolean {
        if (text.isBlank()) return true
        // Framework state is the only trustworthy evidence that a nonempty value is a hint.
        // Matching hint or content-description strings can also be deliberate user text.
        return isShowingHintText
    }

    fun computeInsertionText(
        rawText: String,
        textToInsert: String,
        selectionStart: Int = -1,
        selectionEnd: Int = -1,
        isPlaceholder: Boolean = false
    ): String {
        val existingText = if (isPlaceholder) "" else rawText
        if (existingText.isEmpty()) return textToInsert

        val insertionStart = minOf(selectionStart, selectionEnd)
        val insertionEnd = maxOf(selectionStart, selectionEnd)

        return if (insertionStart >= 0 && insertionEnd >= insertionStart && insertionEnd <= existingText.length) {
            existingText.replaceRange(insertionStart, insertionEnd, textToInsert)
        } else {
            if (existingText.endsWith(" ") || textToInsert.startsWith(" ")) {
                existingText + textToInsert
            } else {
                "$existingText $textToInsert"
            }
        }
    }
}
