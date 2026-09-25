package com.thomaswcode.dictationapp.platform.access

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.thomaswcode.dictationapp.DictationApp
import com.thomaswcode.dictationapp.platform.overlay.BubbleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Android counterpart of the Windows keyboard hook, UI Automation probe and paste engine in one: it
 * watches which field has focus and whether the keyboard is open, shows the bubble over other apps, and
 * gives the orchestrator the focused field to insert into. It also keeps the process alive, so it is the
 * "tray app" too: Android restarts it after a reboot on its own.
 */
class DictationAccessibilityService : AccessibilityService() {
    private val graph get() = (application as DictationApp).graph
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { evaluate() }
    private var bubble: BubbleController? = null
    private var scope: CoroutineScope? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastDecision: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val g = graph
        g.bridge.service = this
        val controller = BubbleController(this, g.orchestrator, g.settings, g.snoozedUntil, g.logger)
        bubble = controller
        controller.attach()
        val sc = MainScope()
        scope = sc
        sc.launch {
            g.hub.status.collect {
                controller.render(it)
                if (!it.isActive) scheduleRefresh()
            }
        }
        sc.launch { g.hub.flashed.collect { controller.flash() } }
        sc.launch {
            g.settings.flow.collect {
                controller.applySettings(it)
                scheduleRefresh()
            }
        }
        sc.launch { g.snoozedUntil.collect { scheduleRefresh() } }
        registerNetworkCallback(controller)
        connectedState.value = true
        g.logger.info("Accessibility service connected")
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> scheduleRefresh()
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.onConfigurationChanged()
        scheduleRefresh()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, REFRESH_DEBOUNCE_MS)
    }

    /** Decides whether the bubble is eligible: an editable, non-secret, non-numeric field and (optionally) the keyboard. */
    private fun evaluate() {
        val controller = bubble ?: return
        val g = graph
        val windows = runCatching { windows }.getOrDefault(emptyList())
        val keyboardTop = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
            ?.top
        val focus = g.bridge.focusedField(this)
        val pkg = focus?.packageName?.toString()
        val s = g.settings.current
        val eligible = focus != null &&
            FieldInspector.isEditableField(focus) &&
            !FieldInspector.isSecret(focus) &&
            !FieldInspector.isNumeric(focus) &&
            pkg !in s.hiddenInPackages &&
            (keyboardTop != null || !s.bubbleOnlyWithKeyboard)
        val decision = "eligible=$eligible pkg=$pkg field=${focus?.className} editable=${focus?.isEditable} keyboardTop=$keyboardTop"
        if (decision != lastDecision) {
            lastDecision = decision
            g.logger.debug("Bubble: $decision")
        }

        controller.update(BubbleController.Anchor(eligible, keyboardTop, pkg))
    }

    private fun registerNetworkCallback(controller: BubbleController) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                handler.post { controller.setOnline(ok) }
            }

            override fun onLost(network: Network) {
                handler.post { controller.setOnline(false) }
            }
        }
        controller.setOnline(cm.activeNetwork != null)
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun teardown() {
        handler.removeCallbacks(refresh)
        // Without the bubble there is no way to stop a hands-free dictation, and nowhere to insert it: discard it
        // rather than let the microphone run invisibly until the time cap.
        if (bubble != null && graph.hub.current.isActive) {
            graph.logger.info("Accessibility service disconnected during a dictation; discarding it")
            graph.orchestrator.cancel()
        }

        bubble?.detach()
        bubble = null
        scope?.cancel()
        scope = null
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        val g = graph
        if (g.bridge.service === this) g.bridge.service = null
        if (connectedState.value) g.logger.info("Accessibility service disconnected")
        connectedState.value = false
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 80L
        private val connectedState = MutableStateFlow(false)

        /** True while the service is bound and the bubble can appear. */
        val connected: StateFlow<Boolean> = connectedState.asStateFlow()

        /** Whether the user has switched the service on in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, DictationAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
