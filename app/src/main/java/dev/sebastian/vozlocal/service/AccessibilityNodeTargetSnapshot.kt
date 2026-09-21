package dev.sebastian.vozlocal.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Converts only focused-node metadata; callers must not read node text before policy approval. */
internal object AccessibilityNodeTargetSnapshot {
    fun from(node: AccessibilityNodeInfo?): AccessibilityTarget? {
        if (node == null || !node.isVisibleToUser || !node.isFocused) return null
        val packageName = node.packageName?.toString() ?: return null
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            node.isAccessibilityDataSensitive
        return AccessibilityTarget(
            packageName = packageName,
            windowId = node.windowId,
            editable = node.isEditable,
            enabled = node.isEnabled,
            password = node.isPassword,
            accessibilityDataSensitive = sensitive,
            stableId = stableId(node),
        )
    }

    private fun stableId(node: AccessibilityNodeInfo): String? {
        val uniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) node.uniqueId else null
        if (!uniqueId.isNullOrBlank()) return "unique:$uniqueId"
        val viewId = node.viewIdResourceName
        if (!viewId.isNullOrBlank()) return "view:$viewId"
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return "bounds:${node.className}:${rect.flattenToString()}"
    }
}
