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
}
