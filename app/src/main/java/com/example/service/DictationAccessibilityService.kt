package com.example.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
    private var isRecording = false
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var startTimestamp: Long = 0

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

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "show_only_on_input") {
            val showOnlyOnInput = repository.getShowOnlyOnInput()
            if (!showOnlyOnInput) {
                floatingView?.visibility = View.VISIBLE
            } else if (!isRecording) {
                val activeFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: lastFocusedNode
                floatingView?.visibility = if (isInputNode(activeFocus)) View.VISIBLE else View.GONE
            }
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

        floatingView = FrameLayout(this)

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

        micIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor("#38BDF8"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        val btnSize = dpToPx(56f)
        val iconSize = dpToPx(28f)

        buttonContainer.addView(micIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        floatingView?.addView(buttonContainer, FrameLayout.LayoutParams(btnSize, btnSize))

        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16f).toFloat()
            setColor(Color.parseColor("#111827"))
            setStroke(dpToPx(1.5f), Color.parseColor("#38BDF8"))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
        }
        expandedPanel = panel

        statusText = TextView(this).apply {
            text = "Dictating..."
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        panel.addView(statusText)

        waveLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            val waveParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(24f)
            ).apply {
                topMargin = dpToPx(4f)
            }
            layoutParams = waveParams
        }

        for (i in 0 until 5) {
            val bar = View(this).apply {
                val barParams = LinearLayout.LayoutParams(dpToPx(4f), dpToPx(6f)).apply {
                    leftMargin = dpToPx(2f)
                    rightMargin = dpToPx(2f)
                }
                layoutParams = barParams
                val barDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(2f).toFloat()
                    setColor(Color.parseColor("#38BDF8"))
                }
                background = barDrawable
            }
            waveBars.add(bar)
            waveLayout.addView(bar)
        }
        panel.addView(waveLayout)

        val panelParams = FrameLayout.LayoutParams(
            dpToPx(140f),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(64f)
        }
        floatingView?.addView(panel, panelParams)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 600
        }

        buttonContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
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
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
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

        val showOnlyOnInput = repository.getShowOnlyOnInput()
        floatingView?.visibility = if (showOnlyOnInput) View.GONE else View.VISIBLE

        windowManager.addView(floatingView, layoutParams)
    }

    private fun stopRecordingUI() {
        isRecording = false
        micIcon.setColorFilter(Color.parseColor("#38BDF8"))
        bgDrawable?.setColor(Color.parseColor("#1E222A"))
        expandedPanel?.visibility = View.GONE
        stopWaveformAnimation()
    }

    private fun toggleDictation() {
        if (!isRecording) {
            isRecording = true
            startTimestamp = System.currentTimeMillis()
            micIcon.setColorFilter(Color.parseColor("#EF4444"))
            bgDrawable?.setColor(Color.parseColor("#2D1D1F"))
            expandedPanel?.visibility = View.VISIBLE
            statusText.text = "Recording audio..."
            startWaveformAnimation()

            audioRecorder.startRecording(serviceScope) { amplitude ->
                Handler(Looper.getMainLooper()).post {
                    waveBars.forEachIndexed { index, bar ->
                        val heightPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            6f + (amplitude * 24f * (1f + (index % 3) * 0.3f)),
                            resources.displayMetrics
                        ).toInt()
                        val layoutParams = bar.layoutParams as LinearLayout.LayoutParams
                        layoutParams.height = heightPx
                        bar.layoutParams = layoutParams
                    }
                }
            }
        } else {
            statusText.text = "Whisper transcribing..."
            val samples = audioRecorder.stopRecording()
            stopWaveformAnimation()

            serviceScope.launch(Dispatchers.Default) {
                val models = repository.allModels.first()
                val selected = models.find { it.isSelected } ?: models.firstOrNull()
                val modelId = selected?.id ?: "whisper_tiny"

                val rawText = repository.transcribeAudio(samples, modelId)
                if (rawText.isNotEmpty()) {
                    processAndPaste(rawText, selected?.name ?: "Whisper Local")
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "No speech heard"
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
        val showOnlyOnInput = repository.getShowOnlyOnInput()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null) {
                    lastFocusedNode?.recycle()
                    lastFocusedNode = source

                    if (showOnlyOnInput) {
                        if (isInputNode(source)) {
                            floatingView?.visibility = View.VISIBLE
                        } else {
                            if (!isRecording) {
                                val inputFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                floatingView?.visibility = if (isInputNode(inputFocus)) View.VISIBLE else View.GONE
                            }
                        }
                    } else {
                        floatingView?.visibility = View.VISIBLE
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (showOnlyOnInput && !isRecording) {
                    val activeFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    floatingView?.visibility = if (isInputNode(activeFocus)) View.VISIBLE else View.GONE
                } else if (!showOnlyOnInput) {
                    floatingView?.visibility = View.VISIBLE
                }
            }
        }
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
