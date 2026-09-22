package dev.sebastian.vozlocal.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.sebastian.vozlocal.VozLocalApp
import dev.sebastian.vozlocal.data.repository.DictationRepository
import dev.sebastian.vozlocal.data.model.DictationModel
import dev.sebastian.vozlocal.moonshine.MoonshineModels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private const val TAG = "DictationService"

private data class AccessibilityDictationSession(
    val id: Long,
    val target: AccessibilityTarget?,
    val startedAtMs: Long,
    val useAiPolisher: Boolean,
)

class DictationAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatingView: FrameLayout? = null
    private var buttonView: FrameLayout? = null
    private var isRecording = false
    private var ownsRecorderSession = false
    private var currentTarget: AccessibilityTarget? = null
    private var nextSessionId = 0L
    private var activeSession: AccessibilityDictationSession? = null
    private var processingJob: Job? = null
    private var timerJob: Job? = null
    internal var lastWarmedModelId: String? = null
    private var warmupJob: Job? = null
    private var recorderDiscardJob: Job? = null
    private var recorderStopJob: Job? = null
    // Captured when recording begins so a settings/model change cannot reroute this audio.
    private var recordingModel: DictationModel? = null
    private var availableModels: List<DictationModel> = emptyList()

    // Process-wide singleton recorder (has an internal Mutex); shared with the main app.
    private val audioRecorder get() = (applicationContext as VozLocalApp).audioRecorder
    private lateinit var repository: DictationRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Reused for marshalling the live amplitude callback to the main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI elements inside floating overlay
    private lateinit var micIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var waveLayout: LinearLayout
    private val waveBars = mutableListOf<View>()
    private var waveAnimator: ValueAnimator? = null

    private var expandedPanel: LinearLayout? = null
    private var bgDrawable: GradientDrawable? = null
    private var panelBgDrawable: GradientDrawable? = null

    private var buttonScreenX = FloatingButtonDockPolicy.DEFAULT_X
    private var buttonScreenY = FloatingButtonDockPolicy.DEFAULT_Y
    private var snapAnimator: ValueAnimator? = null

    private fun updatePanelPositioning(params: WindowManager.LayoutParams) {
        val panel = expandedPanel ?: return
        val btn = buttonView ?: return
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val btnSize = dpToPx(56f)
        val panelWidth = dpToPx(80f)
        val margin = dpToPx(8f)
        val offset = panelWidth + margin
        val totalWidth = btnSize + offset

        val isRightSide = buttonScreenX > (screenWidth / 2)

        val minX = if (isRightSide) offset else 0
        val maxX = if (!isRightSide) screenWidth - totalWidth else screenWidth - btnSize
        buttonScreenX = buttonScreenX.coerceIn(minX.coerceAtLeast(0), maxX.coerceAtLeast(0))

        val maxY = (screenHeight - btnSize).coerceAtLeast(0)
        buttonScreenY = buttonScreenY.coerceIn(0, maxY)

        params.width = totalWidth
        params.height = btnSize

        if (isRightSide) {
            // Button on right side: Window left edge is at (buttonScreenX - offset).
            // Mic button is at offset inside window (Screen X = buttonScreenX).
            // Panel is at 0 inside window (Screen X = buttonScreenX - offset).
            params.x = buttonScreenX - offset
            panel.translationX = 0f
            btn.translationX = offset.toFloat()
        } else {
            // Button on left side: Window left edge is at buttonScreenX.
            // Mic button is at 0 inside window (Screen X = buttonScreenX).
            // Panel is at (btnSize + margin) inside window (Screen X = buttonScreenX + btnSize + margin).
            params.x = buttonScreenX
            btn.translationX = 0f
            panel.translationX = (btnSize + margin).toFloat()
        }
        params.y = buttonScreenY
    }

    /**
     * Clamps the floating button to the safe area (system bars + display cutout),
     * using WindowMetrics/WindowInsets on API 30+ and full display bounds otherwise.
     * Keeps the whole window (panel + button) inside the safe region.
     */
    private fun clampButtonToSafeArea() {
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }
        val btnSize = dpToPx(56f)
        val panelWidth = dpToPx(80f)
        val margin = dpToPx(8f)
        val offset = panelWidth + margin
        val totalWidth = btnSize + offset

        val isRightSide = buttonScreenX > (resources.displayMetrics.widthPixels / 2)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val screenWidth = metrics.bounds.width()
            val screenHeight = metrics.bounds.height()
            val minX = if (isRightSide) insets.left + offset else insets.left
            val maxX = if (isRightSide) {
                screenWidth - insets.right - btnSize
            } else {
                screenWidth - insets.right - totalWidth
            }
            buttonScreenX = buttonScreenX.coerceIn(minX, maxX)
            val minY = insets.top
            val maxY = (screenHeight - insets.bottom - btnSize).coerceAtLeast(minY)
            buttonScreenY = buttonScreenY.coerceIn(minY, maxY)
        } else {
            val maxX = (
                resources.displayMetrics.widthPixels - (if (isRightSide) btnSize else totalWidth)
                ).coerceAtLeast(0)
            val maxY = (resources.displayMetrics.heightPixels - btnSize).coerceAtLeast(0)
            buttonScreenX = buttonScreenX.coerceIn(0, maxX)
            buttonScreenY = buttonScreenY.coerceIn(0, maxY)
        }

        getSharedPreferences(FloatingButtonDockPolicy.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(FloatingButtonDockPolicy.PREF_KEY_X, buttonScreenX)
            .putInt(FloatingButtonDockPolicy.PREF_KEY_Y, buttonScreenY)
            .apply()
    }

    private fun deniedPackages(): Set<String> = repository.getDeniedPackages()

    private fun nodeTarget(node: AccessibilityNodeInfo?): AccessibilityTarget? {
        return AccessibilityNodeTargetSnapshot.from(node)
    }

    private fun interactiveWindowsSnapshot(): List<AccessibilityWindowSnapshot> =
        windows?.map {
            AccessibilityWindowSnapshot(
                id = it.id,
                type = it.type,
                active = it.isActive,
                focused = it.isFocused,
            )
        }.orEmpty()

    private fun deviceAllowsOverlay(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return powerManager?.isInteractive == true && keyguardManager?.isKeyguardLocked != true
    }

    /**
     * Read only framework focus after the window snapshot says there is one focused application
     * and one IME window. The focused-node package is checked before any text, hint, or content
     * description is accessed.
     */
    private fun focusedEligibleTarget(
        windowSnapshot: List<AccessibilityWindowSnapshot> = interactiveWindowsSnapshot(),
    ): AccessibilityTarget? {
        if (!deviceAllowsOverlay() || !OverlayEligibilityPolicy.canReadFocusedTarget(windowSnapshot)) return null
        val target = nodeTarget(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        return target?.takeIf {
            OverlayEligibilityPolicy.isEligibleTarget(windowSnapshot, it, deniedPackages())
        }
    }

    private fun clearTarget() {
        currentTarget = null
    }

    private fun isCurrentSession(session: AccessibilityDictationSession): Boolean =
        activeSession?.id == session.id

    private fun cancelActiveSession() {
        activeSession = null
        processingJob?.cancel()
        processingJob = null
    }

    /** Never cancel an in-flight recorder teardown: AudioRecorder must reach its idle state. */
    private fun ensureRecorderDiscarded() {
        if (recorderDiscardJob?.isActive == true) return
        recorderDiscardJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            audioRecorder.discardRecording()
        }
    }

    private fun updateFloatingViewVisibility() {
        // The legacy always-visible preference is intentionally ignored: visibility requires a
        // current IME window and a current eligible focused target.
        currentTarget = focusedEligibleTarget()
        floatingView?.visibility = if (currentTarget != null) View.VISIBLE else View.GONE
    }

    private fun reevaluateEligibility() {
        val target = focusedEligibleTarget()
        currentTarget = target
        val sessionTarget = activeSession?.target
        if (sessionTarget != null && !AccessibilityTargetPolicy.matchesRecordingTarget(sessionTarget, target)) {
            stopAndDiscardForIneligibleTarget()
            return
        }
        floatingView?.visibility = if (target != null) View.VISIBLE else View.GONE
        if (target != null && !isRecording) warmupModelIfNeeded()
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "denied_accessibility_packages") {
            reevaluateEligibility()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = (applicationContext as VozLocalApp).repository
        val prefs = getSharedPreferences("vozlocal_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        serviceScope.launch {
            repository.allModels.collect { models ->
                availableModels = models
                val active = models.find { it.isSelected && it.isDownloaded }
                    ?: models.firstOrNull { it.isDownloaded && !MoonshineModels.isMoonshine(it.id) }
                if (active?.id != lastWarmedModelId) {
                    lastWarmedModelId = null
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        val prefs = getSharedPreferences(FloatingButtonDockPolicy.PREFS_NAME, Context.MODE_PRIVATE)
        buttonScreenX = prefs.getInt(FloatingButtonDockPolicy.PREF_KEY_X, FloatingButtonDockPolicy.DEFAULT_X)
        buttonScreenY = prefs.getInt(FloatingButtonDockPolicy.PREF_KEY_Y, FloatingButtonDockPolicy.DEFAULT_Y)

        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        val rootLayout = FrameLayout(this).apply {
            alpha = 0.88f
        }
        floatingView = rootLayout

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#D91E222A".toColorInt()) // ~85% translucent dark background
            setStroke(dpToPx(2f), "#804B5563".toColorInt())
        }
        bgDrawable = bg

        val buttonContainer = FrameLayout(this).apply {
            background = bg
            elevation = dpToPx(8f).toFloat()
            isHapticFeedbackEnabled = true
        }
        buttonView = buttonContainer

        micIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter("#38BDF8".toColorInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        val btnSize = dpToPx(56f)
        val iconSize = dpToPx(28f)
        val panelWidth = dpToPx(80f)
        val margin = dpToPx(8f)
        val offset = panelWidth + margin
        val totalWidth = btnSize + offset

        buttonContainer.addView(micIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16f).toFloat()
            setColor("#E60F172A".toColorInt()) // ~90% translucent dark slate
            setStroke(dpToPx(1.5f), "#EF4444".toColorInt()) // Red border
        }
        panelBgDrawable = panelBg

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
        }
        expandedPanel = panel

        statusText = TextView(this).apply {
            text = "00:00"
            setTextColor("#F8FAFC".toColorInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        panel.addView(statusText)

        waveLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            val waveParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(16f)
            ).apply {
                topMargin = dpToPx(2f)
                bottomMargin = dpToPx(4f)
            }
            layoutParams = waveParams
        }

        for (i in 0 until 5) {
            val bar = View(this).apply {
                val barParams = LinearLayout.LayoutParams(dpToPx(3.5f), dpToPx(6f)).apply {
                    leftMargin = dpToPx(2f)
                    rightMargin = dpToPx(2f)
                }
                layoutParams = barParams
                val barDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(2f).toFloat()
                    setColor("#EF4444".toColorInt())
                }
                background = barDrawable
            }
            waveBars.add(bar)
            waveLayout.addView(bar)
        }
        panel.addView(waveLayout)

        val stopButtonBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(4f).toFloat()
            setColor("#EF4444".toColorInt())
        }

        val stopButton = TextView(this).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = stopButtonBg
            // Accessible 48dp minimum touch target
            minWidth = dpToPx(48f)
            minHeight = dpToPx(48f)
            setPadding(dpToPx(12f), dpToPx(4f), dpToPx(12f), dpToPx(4f))
            contentDescription = "Stop dictation"
            setOnClickListener {
                if (isRecording) {
                    toggleDictation()
                }
            }
        }
        panel.addView(stopButton)

        // Add panel and buttonContainer to rootLayout ONCE
        rootLayout.addView(panel, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT))
        rootLayout.addView(buttonContainer, FrameLayout.LayoutParams(btnSize, btnSize))

        val layoutParams = WindowManager.LayoutParams(
            totalWidth,
            btnSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = buttonScreenX
            y = buttonScreenY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Allow the floating button into display cutout areas on API 30+.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        updatePanelPositioning(layoutParams)
        clampButtonToSafeArea()
        updatePanelPositioning(layoutParams)

        buttonContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startScreenX = 0
            private var startScreenY = 0
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        snapAnimator?.cancel()
                        snapAnimator = null
                        startScreenX = buttonScreenX
                        startScreenY = buttonScreenY
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (max(Math.abs(dx), Math.abs(dy)) > 10) {
                            isDragging = true
                        }
                        if (isDragging) {
                            buttonScreenX = startScreenX + dx
                            buttonScreenY = startScreenY + dy
                            updatePanelPositioning(layoutParams)
                            windowManager.updateViewLayout(floatingView, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            clampButtonToSafeArea()

                            val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                windowManager.currentWindowMetrics.bounds.width()
                            } else {
                                resources.displayMetrics.widthPixels
                            }
                            val safeLeft: Int
                            val safeRight: Int
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                                )
                                safeLeft = insets.left
                                safeRight = insets.right
                            } else {
                                safeLeft = 0
                                safeRight = 0
                            }

                            val edgeMargin = dpToPx(12f)
                            val targetDockedX = FloatingButtonDockPolicy.computeDockedX(
                                buttonScreenX = buttonScreenX,
                                screenWidth = screenWidth,
                                btnSize = btnSize,
                                edgeMargin = edgeMargin,
                                safeLeft = safeLeft,
                                safeRight = safeRight
                            )

                            snapAnimator?.cancel()
                            val startX = buttonScreenX
                            val animator = ValueAnimator.ofInt(startX, targetDockedX).apply {
                                duration = 180L
                                interpolator = DecelerateInterpolator()
                                addUpdateListener { va ->
                                    buttonScreenX = va.animatedValue as Int
                                    updatePanelPositioning(layoutParams)
                                    windowManager.updateViewLayout(floatingView, layoutParams)
                                }
                                addListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        buttonScreenX = targetDockedX
                                        clampButtonToSafeArea()
                                        updatePanelPositioning(layoutParams)
                                        windowManager.updateViewLayout(floatingView, layoutParams)
                                        getSharedPreferences(FloatingButtonDockPolicy.PREFS_NAME, Context.MODE_PRIVATE).edit()
                                            .putInt(FloatingButtonDockPolicy.PREF_KEY_X, buttonScreenX)
                                            .putInt(FloatingButtonDockPolicy.PREF_KEY_Y, buttonScreenY)
                                            .apply()
                                        snapAnimator = null
                                    }
                                })
                            }
                            snapAnimator = animator
                            animator.start()
                        } else {
                            triggerHapticFeedback(v)
                            toggleDictation()
                        }
                        return true
                    }
                }
                return false
            }
        })

        updateFloatingViewVisibility()
        windowManager.addView(floatingView, layoutParams)
    }

    private fun stopRecordingUI() {
        isRecording = false
        timerJob?.cancel()
        timerJob = null
        stopWaveformAnimation()
        micIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        micIcon.setColorFilter("#38BDF8".toColorInt())
        bgDrawable?.setColor("#D91E222A".toColorInt())
        expandedPanel?.visibility = View.GONE
        updateFloatingViewVisibility()
    }

    private fun triggerHapticFeedback(v: View) {
        val performed = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (!performed) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    val vibrator = vibratorManager?.defaultVibrator
                    if (vibrator?.hasVibrator() == true) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (vibrator?.hasVibrator() == true) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (vibrator?.hasVibrator() == true) {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(20L)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to perform fallback vibration", e)
            }
        }
    }

    private fun toggleDictation() {
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        if (!isRecording) {
            if (recorderDiscardJob?.isActive == true || recorderStopJob?.isActive == true) return
            if (processingJob?.isActive == true || activeSession != null) {
                // Do not let a second tap retarget a result that is still decoding.
                statusText.visibility = View.VISIBLE
                statusText.text = "Processing…"
                return
            }
            val target = focusedEligibleTarget() ?: return
            val models = availableModels
            val model = models.find { it.isSelected && it.isDownloaded }
                ?: models.firstOrNull { it.isDownloaded && !MoonshineModels.isMoonshine(it.id) }
            if (model == null) {
                Toast.makeText(this, getString(dev.sebastian.vozlocal.R.string.speech_model_missing), Toast.LENGTH_SHORT).show()
                return
            }
            repository.liveModelError(model.id)?.let { error ->
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                return
            }
            recordingModel = model
            val session = AccessibilityDictationSession(
                id = ++nextSessionId,
                target = target,
                startedAtMs = System.currentTimeMillis(),
                useAiPolisher = repository.getUseAiPolisher()
            )
            activeSession = session
            isRecording = true
            micIcon.setColorFilter(Color.WHITE)
            micIcon.setImageResource(android.R.drawable.ic_media_pause)
            bgDrawable?.setColor("#D9EF4444".toColorInt())
            panelBgDrawable?.setStroke(dpToPx(1.5f), "#EF4444".toColorInt())

            statusText.visibility = View.VISIBLE
            statusText.text = "00:00"
            stopWaveformAnimation()

            waveBars.forEach { bar ->
                (bar.background as? GradientDrawable)?.setColor("#EF4444".toColorInt())
            }

            expandedPanel?.visibility = View.VISIBLE

            var seconds = 0
            timerJob?.cancel()
            timerJob = serviceScope.launch {
                while (isRecording && isCurrentSession(session)) {
                    val m = seconds / 60
                    val s = seconds % 60
                    statusText.text = "%02d:%02d".format(m, s)
                    delay(1.seconds)
                    seconds++
                }
            }

            // Mark ownership before starting the reader: an immediate hardware/read failure may
            // otherwise notify us before startRecording returns.
            ownsRecorderSession = true
            val started = try {
                audioRecorder.startRecording(
                    scope = serviceScope,
                    onRmsChanged = { amplitude ->
                        mainHandler.post {
                            waveBars.forEachIndexed { index, bar ->
                                val scaleFactor = 1.0f + (amplitude * 3.5f * (1f + (index % 3) * 0.25f))
                                bar.scaleY = scaleFactor
                            }
                        }
                    },
                    onRecordingError = { error ->
                        mainHandler.post {
                            if (ownsRecorderSession && isCurrentSession(session)) {
                                Log.w(TAG, "Microphone capture failed", error)
                                ownsRecorderSession = false
                                isRecording = false
                                timerJob?.cancel()
                                timerJob = null
                                stopRecordingUI()
                                activeSession = null
                                recordingModel = null
                            }
                        }
                    }
                )
            } catch (error: SecurityException) {
                Log.w(TAG, "Microphone permission was revoked", error)
                false
            }
            if (!started) {
                ownsRecorderSession = false
                timerJob?.cancel()
                timerJob = null
                isRecording = false
                stopRecordingUI()
                if (isCurrentSession(session)) activeSession = null
                recordingModel = null
                return
            }
        } else {
            val session = activeSession ?: return
            timerJob?.cancel()
            timerJob = null
            isRecording = false
            
            // Purely visual processing state (NO text words!)
            statusText.visibility = View.GONE
            panelBgDrawable?.setStroke(dpToPx(1.5f), "#38BDF8".toColorInt())
            micIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
            micIcon.setColorFilter("#38BDF8".toColorInt())
            bgDrawable?.setColor("#D91E222A".toColorInt())

            waveBars.forEach { bar ->
                (bar.background as? GradientDrawable)?.setColor("#38BDF8".toColorInt())
            }

            startWaveformAnimation()
            // Keep the recorder stop independent from serviceScope. A focus-loss event or service
            // destruction must not cancel AudioRecorder while it is transitioning to idle.
            recorderStopJob = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch {
                val samples = if (ownsRecorderSession) audioRecorder.stopRecording() else FloatArray(0)
                ownsRecorderSession = false
                if (!isCurrentSession(session)) return@launch
                // Snapshot the model before dispatching: model selection can change while this
                // clip is decoding, but an in-flight clip must stay on its start-time model.
                val sessionModel = recordingModel
                processingJob = serviceScope.launch(Dispatchers.Default) {
                    try {
                        val selected = sessionModel
                        if (selected == null || !selected.isDownloaded) {
                            withContext(Dispatchers.Main) {
                                if (isCurrentSession(session)) {
                                    Toast.makeText(this@DictationAccessibilityService, getString(dev.sebastian.vozlocal.R.string.speech_model_missing), Toast.LENGTH_SHORT).show()
                                    stopRecordingUI()
                                    activeSession = null
                                    recordingModel = null
                                }
                            }
                            return@launch
                        }
                        val modelId = selected.id
                        repository.liveModelError(modelId, samples.size)?.let { error ->
                            withContext(Dispatchers.Main) {
                                if (isCurrentSession(session)) {
                                    Toast.makeText(this@DictationAccessibilityService, error, Toast.LENGTH_SHORT).show()
                                    stopRecordingUI()
                                    activeSession = null
                                    recordingModel = null
                                }
                            }
                            return@launch
                        }
                        val rawText = repository.transcribeAudio(samples, modelId)
                        if (!isCurrentSession(session)) return@launch
                        withContext(Dispatchers.Main) {
                            if (!isCurrentSession(session)) return@withContext
                            if (rawText.isNotEmpty()) {
                                processAndPaste(session, rawText, selected.name, selected.id)
                            } else {
                                stopRecordingUI()
                                activeSession = null
                                recordingModel = null
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Accessibility dictation failed", e)
                        withContext(Dispatchers.Main) {
                            if (isCurrentSession(session)) {
                                Toast.makeText(this@DictationAccessibilityService, e.message ?: "Transcription failed. Please try again.", Toast.LENGTH_SHORT).show()
                                stopRecordingUI()
                                activeSession = null
                                recordingModel = null
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startWaveformAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0.2f, 1.0f).apply {
            duration = 450
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                waveBars.forEachIndexed { index, bar ->
                    val factor = if (index % 2 == 0) progress else 1.2f - progress
                    val heightPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        6f + factor * 18f,
                        resources.displayMetrics
                    ).toInt()
                    val params = bar.layoutParams as LinearLayout.LayoutParams
                    params.height = heightPx
                    bar.layoutParams = params
                }
            }
            start()
        }
    }

    private fun stopWaveformAnimation() {
        waveAnimator?.cancel()
        waveAnimator = null
        waveBars.forEach { bar ->
            val params = bar.layoutParams as LinearLayout.LayoutParams
            params.height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
            bar.layoutParams = params
        }
    }

    private suspend fun processAndPaste(
        session: AccessibilityDictationSession,
        rawText: String,
        modelName: String,
        modelId: String
    ) {
        if (!isCurrentSession(session)) return
        val durationSec = ((System.currentTimeMillis() - session.startedAtMs) / 1000).toInt().coerceAtLeast(1)

        val processed = repository.postProcessText(
            text = rawText,
            smartPunctuation = true,
            autoCapitalize = true,
            applyDict = true,
            useAiPolisher = session.useAiPolisher,
            modelId = modelId
        )

        if (!isCurrentSession(session)) return

        withContext(Dispatchers.Main) {
            if (!isCurrentSession(session)) return@withContext
            // Results are discarded if the original focused field is no longer the sole eligible
            // input. Do not copy stale dictation into the clipboard.
            val stillEligible = session.target?.let { recordingTarget ->
                AccessibilityTargetPolicy.matchesRecordingTarget(recordingTarget, focusedEligibleTarget())
            } == true
            if (!stillEligible) {
                stopRecordingUI()
                activeSession = null
                recordingModel = null
                return@withContext
            }
            val pasted = pasteTextToActiveInput(requireNotNull(session.target), processed)
            if (!pasted) {
                // Failure can mean the target vanished between the two framework snapshots.
                // Clipboard fallback is only appropriate for a still-eligible editor rejecting text.
                if (!AccessibilityTargetPolicy.matchesRecordingTarget(session.target, focusedEligibleTarget())) {
                    stopRecordingUI()
                    activeSession = null
                    recordingModel = null
                    return@withContext
                }
                copyToClipboard(processed)
                Toast.makeText(
                    this@DictationAccessibilityService,
                    "Dictation copied to clipboard",
                    Toast.LENGTH_SHORT
                ).show()
            }
            repository.insertHistory(
                dev.sebastian.vozlocal.data.model.TranscriptionHistory(
                    text = processed,
                    durationSec = durationSec,
                    modelUsed = modelName,
                    type = "dictation"
                )
            )
            stopRecordingUI()
            activeSession = null
            recordingModel = null
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("VozLocal Dictation", text)
                clipboard.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy text to clipboard", e)
        }
    }

    private fun pasteTextToActiveInput(recordingTarget: AccessibilityTarget, text: String): Boolean {
        if (!deviceAllowsOverlay()) return false
        val windowSnapshot = interactiveWindowsSnapshot()
        if (!OverlayEligibilityPolicy.canReadFocusedTarget(windowSnapshot)) return false
        val targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = nodeTarget(targetNode)
        if (!OverlayEligibilityPolicy.isEligibleTarget(windowSnapshot, target, deniedPackages()) ||
            !AccessibilityTargetPolicy.matchesRecordingTarget(recordingTarget, target)) {
            return false
        }
        if (targetNode != null) {
            val rawText = targetNode.text?.toString().orEmpty()
            val isHintShowing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && targetNode.isShowingHintText
            val isPlaceholder = AccessibilityTargetPolicy.isPlaceholderText(
                text = rawText,
                hintText = targetNode.hintText?.toString(),
                contentDescription = targetNode.contentDescription?.toString(),
                isShowingHintText = isHintShowing,
                selectionStart = targetNode.textSelectionStart,
                selectionEnd = targetNode.textSelectionEnd,
            )
            val newText = AccessibilityTargetPolicy.computeInsertionText(
                rawText = rawText,
                textToInsert = text,
                selectionStart = targetNode.textSelectionStart,
                selectionEnd = targetNode.textSelectionEnd,
                isPlaceholder = isPlaceholder
            )
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) Log.w(TAG, "Direct text insertion was rejected by the target app")
            return success
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Event package/source is often the IME or this overlay. Always derive the foreground
        // target from interactive-window metadata and framework input focus instead.
        reevaluateEligibility()
    }

    internal fun warmupModelIfNeeded() {
        if (isRecording) return
        if (warmupJob?.isActive == true) return
        if (lastWarmedModelId != null && repository.modelLoaded.value) return

        warmupJob = serviceScope.launch(Dispatchers.Default) {
            try {
                val models = repository.allModels.first()
                val selected = models.find { it.isSelected && it.isDownloaded }
                    ?: models.firstOrNull { it.isDownloaded && !MoonshineModels.isMoonshine(it.id) }
                val modelId = selected?.id ?: return@launch

                if (lastWarmedModelId == modelId && repository.modelLoaded.value) {
                    return@launch
                }

                val loaded = repository.preloadModel(modelId)
                if (loaded) {
                    lastWarmedModelId = modelId
                    Log.d(TAG, "Input-focus model warmup succeeded for model: $modelId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to warm up Whisper model on input focus", e)
            }
        }
    }

    private fun stopAndDiscardForIneligibleTarget() {
        isRecording = false
        timerJob?.cancel()
        timerJob = null
        if (ownsRecorderSession) {
            ownsRecorderSession = false
            ensureRecorderDiscarded()
        }
        cancelActiveSession()
        recordingModel = null
        clearTarget()
        // Do not call stopRecordingUI here: it re-reads eligibility while a loss is being handled.
        stopWaveformAnimation()
        expandedPanel?.visibility = View.GONE
        floatingView?.visibility = View.GONE
    }

    override fun onInterrupt() {
        isRecording = false
        timerJob?.cancel()
        timerJob = null
        stopWaveformAnimation()
        if (ownsRecorderSession) {
            ownsRecorderSession = false
            ensureRecorderDiscarded()
        }
        cancelActiveSession()
        recordingModel = null
        clearTarget()
        expandedPanel?.visibility = View.GONE
        floatingView?.visibility = View.GONE
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        snapAnimator = null
        warmupJob?.cancel()
        warmupJob = null
        lastWarmedModelId = null
        if (ownsRecorderSession) {
            ownsRecorderSession = false
            ensureRecorderDiscarded()
        }
        cancelActiveSession()
        serviceScope.cancel()
        clearTarget()
        val prefs = getSharedPreferences(FloatingButtonDockPolicy.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating view", e)
            }
        }
        super.onDestroy()
    }
}
