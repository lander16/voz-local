# Floating overlay visibility and accessibility warnings

Implementation tracking and validation: [issue #15](https://github.com/lander16/voz-local/issues/15).

## What the audit established

The September 21, 2026 source audit found three independent ways for the microphone
to remain visible unexpectedly: the saved `show_only_on_input=false` option bypassed
focus checks, focused editors remained eligible after the keyboard closed, and an
active recording bypassed ordinary visibility checks. The service subscribed only
to focus and window-state events, without interactive-window change notifications.
An app can retain input focus after keyboard dismissal, so focus alone is insufficient.

The reported appearance in YouTube Music is consistent with these paths, but was
not reproduced on a connected device during this audit.

## Research and design choice

Android exposes input-method windows through `AccessibilityWindowInfo.TYPE_INPUT_METHOD`.
This represents input UI, including a keyboard or suggestions; it is not proof that
a particular keyboard layout or keyboard vendor is active. The service requires an
input-method window alongside a valid editor. It does not infer visibility from
keyboard package names or an editor's retained focus.
[Android window documentation](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#TYPE_INPUT_METHOD).

Input focus and the active window are different: Android can mark the keyboard or
overlay active while it is being touched, even though the editor retains input
focus. Eligibility therefore needs to tolerate those touch transitions without
treating an unrelated system surface as a valid dictation context.
[Android active-window semantics](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#isActive).

Receiving `TYPE_WINDOWS_CHANGED` and retrieving the window list requires
`FLAG_RETRIEVE_INTERACTIVE_WINDOWS` and the existing `canRetrieveWindowContent`
capability. This expands the windows the platform permits the service to inspect.
The implementation uses window metadata for eligibility, without traversing all
window content. The additional capability is a deliberate tradeoff for reliable
keyboard-dismissal detection, not a reduction in the accessibility permission itself.
[Android service flags](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS).

## Behavior contract

- An idle button requires a visible input-method window and an eligible focused
  editor in the foreground application window. Unknown or inconsistent state hides it.
- The editor must be visible, enabled, editable, and neither a password nor an
  accessibility-sensitive control; protected applications remain excluded.
- Window and focus changes trigger a fresh eligibility check. IME event packages
  must not be mistaken for the foreground application.
- Recording starts and insertion recheck current eligibility. Losing the keyboard
  or original target cancels the overlay session and discards ongoing capture.
- Old always-visible preferences cannot bypass this policy. Setup and settings
  describe mandatory behavior in English and Spanish.
- A hardware-keyboard-only session without visible input-method UI has no floating
  button. The in-app dictation screen remains available.
- Multiple application surfaces, including split-screen, are treated as ambiguous
  and hide the button. Supporting these layouts requires a separately validated
  ownership policy rather than guessing which editor should receive text.

## What this does not establish about warnings

Android documents that enabling accessibility allows an app to read screen content
and act on the user's behalf. A permission or restricted-settings notice can describe
that capability even when no overlay is visible.
[Android restricted settings](https://support.google.com/android/answer/12623953).

Play Protect separately checks apps for potentially harmful behavior and can warn
about unverified apps using sensitive permissions. Without the exact warning and
its source, an accessibility disclosure cannot be identified as a Play Protect
malware finding. Visibility fixes cannot promise either a clean Play Protect result
or acceptance by a particular bank.
[Google Play Protect](https://support.google.com/android/answer/2812853).

The earlier September 8 Pixel/Nu comparison found different outcomes between debug
and upload signing certificates. That remains a historical observation for those
builds and app versions, not evidence that certificate identity explains every
future warning. Continue using the verified signed-release installer, keep Play
Protect enabled, and let the user perform bank compatibility checks manually.

## Validation protocol

Automated regressions must exercise keyboard removal with retained editor focus,
no focused editor, app/window changes, unknown window state, protected/password
fields, session cancellation, stale start/insertion, and startup/interruption hiding.
Record exact checks and remaining gaps in issue #15.

On the Pixel, use the production certificate and a non-sensitive text editor:

1. Open an editable field and the keyboard; confirm the button appears.
2. Dismiss the keyboard while leaving the editor focused; confirm it disappears.
3. Open YouTube Music or another non-input screen; confirm it stays hidden.
4. Open that app's search editor; confirm visibility follows the keyboard and field.
5. Start dictation, then dismiss the keyboard or change the target; confirm capture
   stops and no text reaches a different field.
6. Switch apps during decoding, test two fields in one app, lock/unlock, rotate,
   and reconnect the accessibility service. Check for a stale button or result.
7. Separately ask the user to check Nu and report any warning verbatim. Do not
   automate, inspect, screenshot, or log bank screens.

No Pixel was connected at the initial September 21 device check. Host tests and
successful builds do not substitute for these device and OEM behavior checks.

### Retained host evidence (September 21, 2026)

Implementation commits: `8a4cba5` (service, policy, framework adapter and lifecycle
regressions) and `af6c3d3` (English/Spanish notice and bypass removal).

With Android Studio's bundled JDK, `./gradlew testDebugUnitTest lintDebug assembleRelease`
completed successfully in 1 minute 55 seconds. XML test reports recorded 217 tests
across 40 suites, zero skipped, zero failures, and zero errors. Lint recorded zero
errors and 63 warnings. Release packaging succeeded; this validation invocation
did not supply signing credentials or install the APK.

New checks include keyboard/window policy, framework-node visibility and focus,
service interruption cancelling timer/processing and hiding the overlay, window
events without an eligible editor, stale taps and stale insertion, and the localized
notice without a bypass control. Physical microphone teardown, actual keyboard
window reporting, and Nu behavior still need the device checks above.
