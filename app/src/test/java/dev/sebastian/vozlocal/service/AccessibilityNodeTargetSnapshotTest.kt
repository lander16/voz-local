package dev.sebastian.vozlocal.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityNodeTargetSnapshotTest {
    @Test
    fun onlyVisibleFocusedFrameworkNodesProduceTargetMetadata() {
        val node = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.example.notes"
            isEditable = true
            isEnabled = true
            isVisibleToUser = true
            isFocused = true
        }
        assertNotNull(AccessibilityNodeTargetSnapshot.from(node))

        node.isFocused = false
        assertNull(AccessibilityNodeTargetSnapshot.from(node))
        node.isFocused = true
        node.isVisibleToUser = false
        assertNull(AccessibilityNodeTargetSnapshot.from(node))
        node.recycle()
    }
}
