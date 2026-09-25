package com.thomaswcode.dictationapp.platform.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.session.DictationOrchestrator
import com.thomaswcode.dictationapp.core.session.DictationState
import com.thomaswcode.dictationapp.core.session.DictationStatus
import com.thomaswcode.dictationapp.core.settings.AppSettings
import com.thomaswcode.dictationapp.core.settings.BubbleSide
import com.thomaswcode.dictationapp.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the bubble's overlay window: where it sits (just above the keyboard, on the left or right edge),
 * when it shows (an editable, non-password field with the keyboard open, or any active dictation), and
 * what a touch means:
 *
 * - tap: start a hands-free dictation; the bubble becomes a pill with a cancel (x) and a check mark;
 * - press and hold: push-to-talk, release to insert; slide the finger away before releasing to discard;
 * - drag: move the bubble; it snaps to the nearest edge. Drop it on the target at the bottom to hide it
 *   for 10 minutes.
 *
 * The overlay is a TYPE_ACCESSIBILITY_OVERLAY window, so no "display over other apps" permission is needed,
 * and it is never focusable, so the text field keeps focus and the keyboard stays open.
 */
class BubbleController(
    private val context: Context,
    private val orchestrator: DictationOrchestrator,
    private val settingsStore: SettingsStore,
    private val snoozedUntil: MutableStateFlow<Long>,
    private val logger: Logger,
) {
    /** What the accessibility service found on screen. */
    data class Anchor(val eligible: Boolean, val keyboardTop: Int?, val packageName: String?)

    private enum class Gesture { None, Pending, Busy, Pill }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val view = BubbleView(context)
    private val target = SnoozeTargetView(context)
    private val params = overlayParams(touchable = true)
    private val targetParams = overlayParams(touchable = false)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private var attached = false
    private var visible = false
    private var anchor = Anchor(false, null, null)
    private var status = DictationStatus()
    private var online = true
    private var settings: AppSettings = settingsStore.current
    private var lastBadgeSeq = 0
    private var shrunk = false

    private var gesture = Gesture.None
    private var pillRegion: BubbleView.Region? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var pressed = false
    private var overTarget = false
    private var snapAnimator: ValueAnimator? = null

    private val confirmPress = Runnable {
        if (gesture == Gesture.Pending && !dragging) {
            pressed = true
            beginDictation()
        }
    }
    private val shrinkRunnable = Runnable { setShrunk(true) }
    private val snoozeEnded = Runnable { refreshVisibility() }

    fun attach() {
        if (attached) return
        applySettings(settingsStore.current)
        view.visibility = View.GONE
        view.alpha = 0f
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        view.setOnTouchListener { _, ev -> onTouch(ev) }
        target.visibility = View.GONE
        wm.addView(view, params)
        wm.addView(target, targetParams)
        attached = true
    }

    fun detach() {
        if (!attached) return
        handler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        runCatching { wm.removeViewImmediate(view) }
        runCatching { wm.removeViewImmediate(target) }
        attached = false
        visible = false
    }

    fun applySettings(s: AppSettings) {
        settings = s
        val percent = s.bubbleSizePercent.coerceIn(AppSettings.BUBBLE_MIN_PERCENT, AppSettings.BUBBLE_MAX_PERCENT)
        view.diameter = AppSettings.BUBBLE_BASE_DP * percent / 100f * density
        target.diameter = TARGET_DP * density
        if (!s.bubbleShrinkWhenIdle) setShrunk(false) else scheduleShrink()
        if (visible) {
            view.animate().alpha(targetAlpha()).setDuration(150).start()
            reposition()
        }

        refreshVisibility()
    }

    fun update(a: Anchor) {
        anchor = a
        refreshVisibility()
    }

    fun setOnline(value: Boolean) {
        if (online == value) return
        online = value
        render(status)
    }

    fun flash() = view.shake()

    fun onConfigurationChanged() {
        if (visible) reposition()
    }

    fun render(s: DictationStatus) {
        val wasActive = status.isActive
        status = s
        view.level = s.level
        val mode = when (s.state) {
            DictationState.Arming, DictationState.Recording -> if (s.handsFree) BubbleView.Mode.HandsFree else BubbleView.Mode.Recording
            DictationState.Finalising, DictationState.PostProcessing, DictationState.Inserting -> BubbleView.Mode.Processing
            DictationState.Idle -> if (online) BubbleView.Mode.Idle else BubbleView.Mode.Offline
        }
        val widthChanged = (view.mode == BubbleView.Mode.HandsFree) != (mode == BubbleView.Mode.HandsFree)
        view.mode = mode
        if (mode != BubbleView.Mode.Recording) view.cancelArmed = false
        if (s.isActive) {
            handler.removeCallbacks(shrinkRunnable)
            setShrunk(false)
        } else if (wasActive) {
            scheduleShrink()
        }

        if (!s.isActive && s.badge != null && s.badgeSeq != lastBadgeSeq) {
            lastBadgeSeq = s.badgeSeq
            when (s.badge) {
                "cleanup skipped" -> view.ring(BubbleView.AMBER)
                "Failed" -> view.ring(BubbleView.RED)
                "Nothing heard" -> view.shake()
            }
        }

        if (visible) {
            if (widthChanged) reposition()
            if (gesture == Gesture.None) view.animate().alpha(targetAlpha()).setDuration(150).start()
        }

        refreshVisibility()
    }

    private fun shouldShow(): Boolean {
        if (status.isActive) return true
        if (snoozedUntil.value > System.currentTimeMillis()) return false
        return anchor.eligible
    }

    private fun refreshVisibility() {
        if (!attached) return
        val show = shouldShow()
        if (show == visible) {
            if (show && gesture == Gesture.None && snapAnimator?.isRunning != true) reposition()
            return
        }

        visible = show
        if (show) {
            view.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            reposition()
            view.animate().alpha(targetAlpha()).setDuration(160).start()
            scheduleShrink()
        } else {
            handler.removeCallbacks(confirmPress)
            handler.removeCallbacks(shrinkRunnable)
            gesture = Gesture.None
            dragging = false
            pressed = false
            hideTarget()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            safeUpdate(view, params)
            view.animate().alpha(0f).setDuration(120).withEndAction { if (!visible) view.visibility = View.GONE }.start()
        }

        val snooze = snoozedUntil.value - System.currentTimeMillis()
        handler.removeCallbacks(snoozeEnded)
        if (snooze > 0) handler.postDelayed(snoozeEnded, snooze + 50)
    }

    /** Places the bubble on its edge, [AppSettings.bubbleOffsetDp] above the keyboard (or at its saved height). */
    private fun reposition() {
        if (!attached || dragging) return
        snapAnimator?.cancel()
        val (x, y) = restingPosition()
        params.x = x
        params.y = y
        safeUpdate(view, params)
        updatePivot()
    }

    private fun restingPosition(): Pair<Int, Int> {
        val screen = screen()
        val d = view.diameter
        val pad = view.pad
        val margin = EDGE_MARGIN_DP * density
        val x = if (settings.bubbleSide == BubbleSide.Right) screen.width() - margin - view.contentWidth - pad else margin - pad
        val kb = anchor.keyboardTop
        val y = if (kb != null) {
            kb - settings.bubbleOffsetDp * density - d - pad
        } else {
            screen.height() * settings.bubbleYFraction - d / 2 - pad
        }
        val minY = TOP_INSET_DP * density - pad
        val maxY = screen.height() - d - pad
        return x.roundToInt() to y.coerceIn(minY, maxOf(minY, maxY)).roundToInt()
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(shrinkRunnable)
                setShrunk(false)
                if (status.isActive) {
                    val running = status.state == DictationState.Arming || status.state == DictationState.Recording
                    gesture = if (running && status.handsFree) Gesture.Pill else Gesture.Busy
                    pillRegion = if (gesture == Gesture.Pill) view.regionAt(ev.x) else null
                    return true
                }

                snapAnimator?.cancel()
                gesture = Gesture.Pending
                pressed = false
                dragging = false
                downRawX = ev.rawX
                downRawY = ev.rawY
                downTime = ev.eventTime
                startX = params.x
                startY = params.y
                view.animate().alpha(1f).setDuration(80).start()
                handler.postDelayed(confirmPress, PRESS_CONFIRM_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture != Gesture.Pending) return true
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                val distance = hypot(dx, dy)
                if (!pressed && !dragging && distance > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(confirmPress)
                    showTarget()
                }

                if (dragging) {
                    params.x = (startX + dx).roundToInt()
                    params.y = (startY + dy).roundToInt()
                    safeUpdate(view, params)
                    updateTargetProximity()
                } else if (pressed) {
                    val armed = distance > view.diameter * CANCEL_DISTANCE_FACTOR
                    if (armed != view.cancelArmed) {
                        view.cancelArmed = armed
                        haptic(VibrationEffect.EFFECT_TICK)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val g = gesture
                gesture = Gesture.None
                when (g) {
                    Gesture.Pill -> if (view.regionAt(ev.x) == pillRegion) {
                        if (pillRegion == BubbleView.Region.Cancel) orchestrator.cancel() else orchestrator.release()
                        haptic(VibrationEffect.EFFECT_CLICK)
                    }
                    Gesture.Busy -> orchestrator.press() // ignored while finishing: the bubble shakes
                    Gesture.Pending -> {
                        handler.removeCallbacks(confirmPress)
                        when {
                            dragging -> finishDrag()
                            !pressed -> {
                                beginDictation()
                                orchestrator.enterHandsFree()
                            }
                            view.cancelArmed -> {
                                view.cancelArmed = false
                                orchestrator.cancel()
                                haptic(VibrationEffect.EFFECT_CLICK)
                            }
                            ev.eventTime - downTime < TAP_MAX_MS -> orchestrator.enterHandsFree()
                            else -> {
                                orchestrator.release()
                                haptic(VibrationEffect.EFFECT_CLICK)
                            }
                        }

                        dragging = false
                        pressed = false
                    }
                    Gesture.None -> Unit
                }

                if (!status.isActive) view.animate().alpha(targetAlpha()).setDuration(150).start()
                scheduleShrink()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(confirmPress)
                if (gesture == Gesture.Pending) {
                    if (dragging) finishDrag() else if (pressed) orchestrator.release()
                }

                gesture = Gesture.None
                dragging = false
                pressed = false
            }
        }

        return true
    }

    private fun beginDictation() {
        orchestrator.press()
        haptic(VibrationEffect.EFFECT_TICK)
    }

    private fun finishDrag() {
        dragging = false
        hideTarget()
        if (overTarget) {
            overTarget = false
            snooze()
            return
        }

        val screen = screen()
        val d = view.diameter
        val pad = view.pad
        val centerX = params.x + pad + view.contentWidth / 2
        val side = if (centerX < screen.width() / 2f) BubbleSide.Left else BubbleSide.Right
        val kb = anchor.keyboardTop
        val visualBottom = params.y + pad + d
        val next = settingsStore.update { s ->
            if (kb != null) {
                s.copy(bubbleSide = side, bubbleOffsetDp = ((kb - visualBottom) / density).roundToInt().coerceIn(0, MAX_OFFSET_DP))
            } else {
                s.copy(bubbleSide = side, bubbleYFraction = ((params.y + pad + d / 2) / screen.height()).coerceIn(0.05f, 0.95f))
            }
        }
        settings = next
        animateTo(restingPosition())
    }

    private fun animateTo(target: Pair<Int, Int>) {
        val fromX = params.x
        val fromY = params.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(220).apply {
            addUpdateListener {
                val f = it.animatedValue as Float
                params.x = (fromX + (target.first - fromX) * f).roundToInt()
                params.y = (fromY + (target.second - fromY) * f).roundToInt()
                safeUpdate(view, params)
            }
            start()
        }
        updatePivot()
    }

    private fun snooze() {
        snoozedUntil.value = System.currentTimeMillis() + SNOOZE_MS
        haptic(VibrationEffect.EFFECT_HEAVY_CLICK)
        Toast.makeText(context, "Bubble hidden for 10 minutes. Open DictationApp to bring it back sooner.", Toast.LENGTH_SHORT).show()
        logger.info("Bubble snoozed for 10 minutes")
        refreshVisibility()
    }

    private fun showTarget() {
        val screen = screen()
        target.measure(0, 0)
        targetParams.x = (screen.width() - target.measuredWidth) / 2
        targetParams.y = (screen.height() - target.measuredHeight - TARGET_BOTTOM_DP * density).roundToInt()
        target.active = false
        target.visibility = View.VISIBLE
        target.alpha = 0f
        safeUpdate(target, targetParams)
        target.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideTarget() {
        if (target.visibility != View.VISIBLE) return
        target.animate().alpha(0f).setDuration(120).withEndAction { target.visibility = View.GONE }.start()
    }

    private fun updateTargetProximity() {
        val bx = params.x + view.pad + view.diameter / 2
        val by = params.y + view.pad + view.diameter / 2
        val tx = targetParams.x + target.measuredWidth / 2f
        val ty = targetParams.y + target.diameter * 0.62f
        val near = hypot(bx - tx, by - ty) < TARGET_RADIUS_DP * density
        if (near != overTarget) {
            overTarget = near
            target.active = near
            if (near) haptic(VibrationEffect.EFFECT_TICK)
        }
    }

    private fun targetAlpha(): Float {
        if (status.isActive) return 1f
        val base = settings.bubbleOpacityPercent.coerceIn(AppSettings.OPACITY_MIN_PERCENT, 100) / 100f
        return if (shrunk) base * 0.8f else base
    }

    private fun scheduleShrink() {
        handler.removeCallbacks(shrinkRunnable)
        if (settings.bubbleShrinkWhenIdle && visible && !status.isActive) handler.postDelayed(shrinkRunnable, SHRINK_AFTER_MS)
    }

    private fun setShrunk(value: Boolean) {
        if (shrunk == value) return
        shrunk = value
        updatePivot()
        val scale = if (value) SHRUNK_SCALE else 1f
        view.animate().scaleX(scale).scaleY(scale).alpha(if (visible) targetAlpha() else view.alpha).setDuration(200).start()
    }

    /** Shrinks toward the screen edge so a shrunk bubble stays tucked in. */
    private fun updatePivot() {
        view.pivotX = if (settings.bubbleSide == BubbleSide.Right) view.pad + view.contentWidth else view.pad
        view.pivotY = view.pad + view.diameter / 2
    }

    private fun haptic(effect: Int) {
        if (!settings.hapticFeedback) return
        runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        if (attached) runCatching { wm.updateViewLayout(v, p) }
    }

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = if (touchable) "DictationApp bubble" else "DictationApp hide target"
    }

    companion object {
        /** A press becomes a dictation once the finger has stayed put this long (otherwise it may be a drag). */
        const val PRESS_CONFIRM_MS = 150L

        /** Released sooner than this, a press is a tap and the dictation continues hands-free. */
        const val TAP_MAX_MS = 300L
        const val SNOOZE_MS = 10 * 60 * 1000L
        private const val SHRINK_AFTER_MS = 5_000L
        private const val SHRUNK_SCALE = 0.62f
        private const val EDGE_MARGIN_DP = 6
        private const val TOP_INSET_DP = 28
        private const val MAX_OFFSET_DP = 600
        private const val TARGET_DP = 56
        private const val TARGET_BOTTOM_DP = 72
        private const val TARGET_RADIUS_DP = 72
        private const val CANCEL_DISTANCE_FACTOR = 1.8f
    }
}
