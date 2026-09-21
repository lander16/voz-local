package dev.sebastian.vozlocal.service

/** Framework-independent facts collected from [android.view.accessibility.AccessibilityWindowInfo]. */
data class AccessibilityWindowSnapshot(
    val id: Int,
    val type: Int,
    val active: Boolean,
    val focused: Boolean,
)

/**
 * The overlay is deliberately conservative: an uncertain or multi-window state has no target.
 * Window metadata is evaluated before reading the focused node, so this policy never needs a
 * broad accessibility-tree search.
 */
object OverlayEligibilityPolicy {
    const val TYPE_APPLICATION = 1
    const val TYPE_INPUT_METHOD = 2
    const val TYPE_ACCESSIBILITY_OVERLAY = 4

    fun activeApplicationWindowId(windows: List<AccessibilityWindowSnapshot>): Int? {
        val applicationWindows = windows.filter { it.type == TYPE_APPLICATION }
        // Multiple application surfaces have ambiguous input ownership (for example split screen).
        if (applicationWindows.size != 1) return null
        val application = applicationWindows.single()
        // Input-method interactions can make the IME the active window while the app remains the
        // only focused application. Focus identifies the application that owns input in that case.
        val activeWindows = windows.filter { it.active }
        val activeWindowIsKnownInputSurface = activeWindows.isNotEmpty() && activeWindows.all {
            it.type == TYPE_APPLICATION ||
                it.type == TYPE_INPUT_METHOD ||
                it.type == TYPE_ACCESSIBILITY_OVERLAY
        }
        return application.id.takeIf { application.focused && activeWindowIsKnownInputSurface }
    }

    fun hasVisibleInputMethod(windows: List<AccessibilityWindowSnapshot>): Boolean =
        windows.count { it.type == TYPE_INPUT_METHOD } == 1

    fun canReadFocusedTarget(windows: List<AccessibilityWindowSnapshot>): Boolean =
        activeApplicationWindowId(windows) != null && hasVisibleInputMethod(windows)

    fun isEligibleTarget(
        windows: List<AccessibilityWindowSnapshot>,
        target: AccessibilityTarget?,
        deniedPackages: Set<String>,
    ): Boolean {
        val applicationWindowId = activeApplicationWindowId(windows) ?: return false
        return hasVisibleInputMethod(windows) &&
            target?.windowId == applicationWindowId &&
            AccessibilityTargetPolicy.canTarget(target, deniedPackages) &&
            AccessibilityTargetPolicy.hasStableIdentity(target)
    }
}
