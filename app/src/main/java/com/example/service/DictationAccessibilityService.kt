package com.example.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.VozLocalApp
import com.example.audio.AudioRecorder
import com.example.data.repository.DictationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.max

private const val TAG = "DictationService"

class DictationAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var floatingView: FrameLayout? = null
    private var buttonView: FrameLayout? = null
    private var isRecording = false
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var startTimestamp: Long = 0
    private var timerJob: Job? = null

    private val audioRecorder = AudioRecorder()
    private lateinit var repository: DictationRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI elements inside floating overlay
    private lateinit var micIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var waveLayout: LinearLayout
    private val waveBars = mutableListOf<View>()
    private var waveAnimator: ValueAnimator? = null

    private var expandedPanel: LinearLayout? = null
    private var bgDrawable: GradientDrawable? = null
    private var panelBgDrawable: GradientDrawable? = null

    private var buttonScreenX = 50
    private var buttonScreenY = 600

    private fun updatePanelPositioning(params: WindowManager.LayoutParams) {
        val panel = expandedPanel ?: return
        val btn = buttonView ?: return
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val btnSize = dpToPx(56f)
        val panelWidth = dpToPx(140f)
        val margin = dpToPx(8f)
        val offset = panelWidth + margin
        val totalWidth = btnSize + offset

        val isRightSide = buttonScreenX > (screenWidth / 2)

        val minX = if (isRightSide) offset else 0
        val maxX = if (!isRightSide) screenWidth - totalWidth else screenWidth - btnSize
        buttonScreenX = buttonScreenX.coerceIn(minX.coerceAtLeast(0), maxX.coerceAtLeast(0))

        val maxY = (screenHeight - btnSize).coerceAtLeast(0)
        buttonScreenY = buttonScreenY.coerceIn(0, maxY)

        if (panel.visibility == View.VISIBLE) {
            params.width = totalWidth
            params.height = btnSize

            if (isRightSide) {
                // Right half of screen -> Open panel to the LEFT of mic button
                params.x = buttonScreenX - offset
                panel.translationX = 0f
                btn.translationX = offset.toFloat()
            } else {
                // Left half of screen -> Open panel to the RIGHT of mic button
                params.x = buttonScreenX
                btn.translationX = 0f
                panel.translationX = (btnSize + margin).toFloat()
            }
        } else {
            // Panel hidden: overlay window is just the mic button, zero offset during drag
            params.width = btnSize
            params.height = btnSize
            params.x = buttonScreenX
            btn.translationX = 0f
            panel.translationX = 0f
        }
        params.y = buttonScreenY
    }

    private fun isInputNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
               className.contains("Input", ignoreCase = true) ||
               className.contains("TextField", ignoreCase = true) ||
               className.contains("AutoComplete", ignoreCase = true) ||
               className.contains("SearchView", ignoreCase = true)
    }

    private fun isKeyboardVisible(): Boolean {
        return try {
            val activeWindows = windows
            if (activeWindows != null) {
                for (window in activeWindows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun updateFloatingViewVisibility() {
        if (isRecording) {
            floatingView?.visibility = View.VISIBLE
            return
        }

        val showOnlyOnInput = repository.getShowOnlyOnInput()
        if (!showOnlyOnInput) {
            floatingView?.visibility = View.VISIBLE
            return
        }

        val keyboardOpen = isKeyboardVisible()
        val activeFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedNode
        val isEditing = isInputNode(activeFocus)

        // Only show floating button when the soft keyboard is open AND a text field is active
        floatingView?.visibility = if (keyboardOpen && isEditing) View.VISIBLE else View.GONE
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "show_only_on_input") {
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
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        val rootLayout = FrameLayout(this)
        floatingView = rootLayout

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1E222A"))
            setStroke(dpToPx(2f), Color.parseColor("#4B5563"))
        }
        bgDrawable = bg

        val buttonContainer = FrameLayout(this).apply {
            background = bg
            elevation = dpToPx(8f).toFloat()
        }
        buttonView = buttonContainer

        micIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor("#38BDF8"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        val btnSize = dpToPx(56f)
        val iconSize = dpToPx(28f)
        val panelWidth = dpToPx(140f)

        buttonContainer.addView(micIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16f).toFloat()
            setColor(Color.parseColor("#0F172A")) // Slate dark
            setStroke(dpToPx(1.5f), Color.parseColor("#EF4444")) // Red border
        }
        panelBgDrawable = panelBg

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
        }
        expandedPanel = panel

        statusText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        panel.addView(statusText)

        waveLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            val waveParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(18f)
            ).apply {
                topMargin = dpToPx(4f)
                bottomMargin = dpToPx(6f)
            }
            layoutParams = waveParams
        }

        for (i in 0 until 5) {
            val bar = View(this).apply {
                val barParams = LinearLayout.LayoutParams(dpToPx(4f), dpToPx(6f)).apply {
                    leftMargin = dpToPx(2.5f)
                    rightMargin = dpToPx(2.5f)
                }
                layoutParams = barParams
                val barDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(2f).toFloat()
                    setColor(Color.parseColor("#EF4444"))
                }
                background = barDrawable
            }
            waveBars.add(bar)
            waveLayout.addView(bar)
        }
        panel.addView(waveLayout)

        // Clear, high-visibility Stop Button inside panel
        val stopButtonBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(6f).toFloat()
            setColor(Color.parseColor("#EF4444"))
        }

        val stopButton = TextView(this).apply {
            text = "⏹ Stop"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = stopButtonBg
            setPadding(dpToPx(12f), dpToPx(5f), dpToPx(12f), dpToPx(5f))
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
            btnSize,
            btnSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = buttonScreenX
            y = buttonScreenY
        }

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
                        if (!isDragging) {
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
        micIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
        micIcon.setColorFilter(Color.parseColor("#38BDF8"))
        bgDrawable?.setColor(Color.parseColor("#1E222A"))
        expandedPanel?.visibility = View.GONE
        (floatingView?.layoutParams as? WindowManager.LayoutParams)?.let { params ->
            updatePanelPositioning(params)
            windowManager.updateViewLayout(floatingView, params)
        }
        updateFloatingViewVisibility()
    }

    private fun toggleDictation() {
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        if (!isRecording) {
            isRecording = true
            startTimestamp = System.currentTimeMillis()
            micIcon.setColorFilter(Color.WHITE)
            micIcon.setImageResource(android.R.drawable.ic_media_pause)
            bgDrawable?.setColor(Color.parseColor("#EF4444"))
            panelBgDrawable?.setStroke(dpToPx(1.5f), Color.parseColor("#EF4444"))

            expandedPanel?.visibility = View.VISIBLE
            (floatingView?.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                updatePanelPositioning(params)
                windowManager.updateViewLayout(floatingView, params)
            }

            var seconds = 0
            timerJob?.cancel()
            timerJob = serviceScope.launch {
                while (isRecording) {
                    val m = seconds / 60
                    val s = seconds % 60
                    statusText.text = "%02d:%02d".format(m, s)
                    delay(1000)
                    seconds++
                }
            }

            audioRecorder.startRecording(serviceScope) { amplitude ->
                Handler(Looper.getMainLooper()).post {
                    waveBars.forEachIndexed { index, bar ->
                        val heightPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            6f + (amplitude * 20f * (1f + (index % 3) * 0.2f)),
                            resources.displayMetrics
                        ).toInt()
                        val layoutParams = bar.layoutParams as LinearLayout.LayoutParams
                        layoutParams.height = heightPx
                        bar.layoutParams = layoutParams
                    }
                }
            }
        } else {
            timerJob?.cancel()
            timerJob = null
            statusText.text = "..."
            panelBgDrawable?.setStroke(dpToPx(1.5f), Color.parseColor("#38BDF8"))
            micIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
            micIcon.setColorFilter(Color.parseColor("#38BDF8"))
            bgDrawable?.setColor(Color.parseColor("#1E222A"))

            val samples = audioRecorder.stopRecording()

            serviceScope.launch(Dispatchers.Default) {
                val models = repository.allModels.first()
                val selected = models.find { it.isSelected } ?: models.firstOrNull()
                val modelId = selected?.id ?: "whisper_tiny"

                val rawText = repository.transcribeAudio(samples, modelId)
                if (rawText.isNotEmpty()) {
                    processAndPaste(rawText, selected?.name ?: "Whisper Local")
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "..."
                        delay(1200)
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
            applyDict = true
        )

        repository.insertHistory(
            com.example.data.model.TranscriptionHistory(
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

    private fun pasteTextToActiveInput(text: String) {
        val targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedNode
        if (targetNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            if (!success) {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("VozLocal Dictation", text)
                    clipboard.setPrimaryClip(clip)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                } catch (e: Exception) {
                    Log.e(TAG, "Paste failed", e)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val source = event.source
        if (source != null && isInputNode(source)) {
            lastFocusedNode?.recycle()
            lastFocusedNode = source
        }

        updateFloatingViewVisibility()
    }

    override fun onInterrupt() {
        isRecording = false
        stopWaveformAnimation()
        audioRecorder.stopRecording()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        @Suppress("DEPRECATION")
        lastFocusedNode?.recycle()
        lastFocusedNode = null
        val prefs = getSharedPreferences("vozlocal_prefs", Context.MODE_PRIVATE)
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
