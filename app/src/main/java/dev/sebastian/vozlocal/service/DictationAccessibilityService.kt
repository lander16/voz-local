package dev.sebastian.vozlocal.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import dev.sebastian.vozlocal.VozLocalApp
import dev.sebastian.vozlocal.data.repository.DictationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private const val TAG = "DictationService"

class DictationAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatingView: FrameLayout? = null
    private var buttonView: FrameLayout? = null
    private var isRecording = false
    private var ownsRecorderSession = false
    private var currentPackageName: String? = null
    private var currentTarget: AccessibilityTarget? = null
    private var recordingTarget: AccessibilityTarget? = null
    private var startTimestamp: Long = 0
    private var timerJob: Job? = null

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
        if (node == null) return null
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
        )
    }

    private fun focusedAllowedTarget(): AccessibilityTarget? {
        val target = nodeTarget(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        return target?.takeIf { AccessibilityTargetPolicy.canTarget(it, deniedPackages()) }
    }

    private fun clearTarget() {
        currentTarget = null
        recordingTarget = null
    }

    private fun updateFloatingViewVisibility() {
        if (!AccessibilityTargetPolicy.canObservePackage(currentPackageName, deniedPackages())) {
            floatingView?.visibility = View.GONE
            return
        }

        if (isRecording) {
            floatingView?.visibility = View.VISIBLE
            return
        }

        val showOnlyOnInput = repository.getShowOnlyOnInput()
        if (!showOnlyOnInput) {
            floatingView?.visibility = View.VISIBLE
            return
        }

        currentTarget = focusedAllowedTarget()
        floatingView?.visibility = if (currentTarget != null) View.VISIBLE else View.GONE
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "show_only_on_input" || key == "denied_accessibility_packages") {
            updateFloatingViewVisibility()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = (applicationContext as VozLocalApp).repository
        val prefs = getSharedPreferences("vozlocal_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
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
        recordingTarget = null
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
            val target = focusedAllowedTarget()
            if (target == null) {
                // Never start a global recording without a current, safe insertion target.
                updateFloatingViewVisibility()
                return
            }
            isRecording = true
            recordingTarget = target
            startTimestamp = System.currentTimeMillis()
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
                while (isRecording) {
                    val m = seconds / 60
                    val s = seconds % 60
                    statusText.text = "%02d:%02d".format(m, s)
                    delay(1.seconds)
                    seconds++
                }
            }

            val started = audioRecorder.startRecording(serviceScope) { amplitude ->
                mainHandler.post {
                    waveBars.forEachIndexed { index, bar ->
                        val scaleFactor = 1.0f + (amplitude * 3.5f * (1f + (index % 3) * 0.25f))
                        bar.scaleY = scaleFactor
                    }
                }
            }
            if (!started) {
                timerJob?.cancel()
                timerJob = null
                isRecording = false
                stopRecordingUI()
                return
            }
            ownsRecorderSession = true
        } else {
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
            val samples = if (ownsRecorderSession) audioRecorder.stopRecording() else FloatArray(0)
            ownsRecorderSession = false

            serviceScope.launch(Dispatchers.Default) {
                val models = repository.allModels.first()
                val selected = models.find { it.isSelected && it.isDownloaded }
                    ?: models.firstOrNull { it.isDownloaded }
                if (selected == null) {
                    withContext(Dispatchers.Main) { stopRecordingUI() }
                    return@launch
                }
                val modelId = selected.id

                val rawText = repository.transcribeAudio(samples, modelId)
                withContext(Dispatchers.Main) {
                    if (rawText.isNotEmpty()) {
                        processAndPaste(rawText, selected?.name ?: "Whisper Local")
                    } else {
                        stopRecordingUI()
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

    private suspend fun processAndPaste(rawText: String, modelName: String) {
        val durationSec = ((System.currentTimeMillis() - startTimestamp) / 1000).toInt().coerceAtLeast(1)

        val processed = repository.postProcessText(
            text = rawText,
            smartPunctuation = true,
            autoCapitalize = true,
            applyDict = true,
            useAiPolisher = repository.getUseAiPolisher()
        )

        repository.insertHistory(
            dev.sebastian.vozlocal.data.model.TranscriptionHistory(
                text = processed,
                durationSec = durationSec,
                modelUsed = modelName,
                type = "dictation"
            )
        )

        withContext(Dispatchers.Main) {
            pasteTextToActiveInput(processed)
            stopRecordingUI()
        }
    }

    private fun pasteTextToActiveInput(text: String): Boolean {
        val targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = nodeTarget(targetNode)
        if (!AccessibilityTargetPolicy.canTarget(target, deniedPackages()) ||
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
                isShowingHintText = isHintShowing
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
        val packageName = event.packageName?.toString()
        if (!AccessibilityTargetPolicy.canObservePackage(packageName, deniedPackages())) {
            currentPackageName = packageName
            clearTarget()
            if (isRecording) {
                stopAndDiscardForSensitiveApp()
            } else {
                floatingView?.visibility = View.GONE
            }
            return
        }

        if (currentPackageName != packageName) {
            currentPackageName = packageName
            currentTarget = null
        }

        // Only retrieve the event source after the package denylist check.
        val source = event.source
        val target = nodeTarget(source)
        currentTarget = target?.takeIf { AccessibilityTargetPolicy.canTarget(it, deniedPackages()) }
        updateFloatingViewVisibility()
    }

    private fun stopAndDiscardForSensitiveApp() {
        isRecording = false
        timerJob?.cancel()
        timerJob = null
        if (ownsRecorderSession) {
            audioRecorder.stopRecording() // Intentionally discard the returned PCM.
            ownsRecorderSession = false
        }
        clearTarget()
        stopRecordingUI()
        floatingView?.visibility = View.GONE
    }

    override fun onInterrupt() {
        isRecording = false
        stopWaveformAnimation()
        if (ownsRecorderSession) {
            audioRecorder.stopRecording()
            ownsRecorderSession = false
        }
        clearTarget()
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        snapAnimator = null
        if (ownsRecorderSession) {
            audioRecorder.stopRecording()
            ownsRecorderSession = false
        }
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
