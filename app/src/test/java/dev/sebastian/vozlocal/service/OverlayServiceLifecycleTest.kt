package dev.sebastian.vozlocal.service

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise service entrypoints without initializing a native ASR engine or opening a microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayServiceLifecycleTest {
    private fun set(service: DictationAccessibilityService, name: String, value: Any?) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun get(service: DictationAccessibilityService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    @Test fun interruptHidesOverlayAndCancelsTimerAndProcessing() {
        val service = Robolectric.buildService(DictationAccessibilityService::class.java).get()
        val overlay = FrameLayout(service).apply { visibility = View.VISIBLE }
        val timer = Job()
        val processing = Job()
        set(service, "floatingView", overlay)
        set(service, "timerJob", timer)
        set(service, "processingJob", processing)
        set(service, "isRecording", true)

        service.onInterrupt()

        assertEquals(View.GONE, overlay.visibility)
        assertTrue(timer.isCancelled)
        assertTrue(processing.isCancelled)
        assertEquals(false, get(service, "isRecording"))
    }

    @Test fun windowEventWithoutEligibleInputHidesPreviouslyVisibleOverlay() {
        val service = Robolectric.buildService(DictationAccessibilityService::class.java).get()
        val overlay = FrameLayout(service).apply { visibility = View.VISIBLE }
        set(service, "floatingView", overlay)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        // A keyboard package event is not evidence of an eligible app editor.
        event.packageName = "com.example.keyboard"
        service.onAccessibilityEvent(event)
        assertEquals(View.GONE, overlay.visibility)
        assertNull(get(service, "currentTarget"))
    }

    @Test fun staleTapWithoutWindowsCannotStartRecording() {
        val service = Robolectric.buildService(DictationAccessibilityService::class.java).get()
        service.javaClass.getDeclaredMethod("toggleDictation").apply { isAccessible = true }.invoke(service)
        assertEquals(false, get(service, "isRecording"))
        assertEquals(false, get(service, "ownsRecorderSession"))
        assertNull(get(service, "activeSession"))
    }

    @Test fun staleResultWithoutWindowsCannotInsert() {
        val service = Robolectric.buildService(DictationAccessibilityService::class.java).get()
        val original = AccessibilityTarget("com.example.notes", 12, true, true, false, false, "view:editor")
        val inserted = service.javaClass.getDeclaredMethod(
            "pasteTextToActiveInput", AccessibilityTarget::class.java, String::class.java
        ).apply { isAccessible = true }.invoke(service, original, "Dictated text")
        assertEquals(false, inserted)
    }
}
