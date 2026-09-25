package com.thomaswcode.dictationapp

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import com.thomaswcode.dictationapp.core.audio.WavFileSink
import com.thomaswcode.dictationapp.core.cleanup.LlmPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.PassthroughPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.PostProcessorRouter
import com.thomaswcode.dictationapp.core.session.DictationOrchestrator
import com.thomaswcode.dictationapp.core.session.StatusHub
import com.thomaswcode.dictationapp.core.settings.AppPaths
import com.thomaswcode.dictationapp.core.settings.JsonSettingsStore
import com.thomaswcode.dictationapp.core.settings.SettingsApiKeyProvider
import com.thomaswcode.dictationapp.core.transcription.AssemblyAiStreamingTranscriber
import com.thomaswcode.dictationapp.platform.AndroidClipboard
import com.thomaswcode.dictationapp.platform.AndroidNotifier
import com.thomaswcode.dictationapp.platform.AppLog
import com.thomaswcode.dictationapp.platform.HistoryRetention
import com.thomaswcode.dictationapp.platform.KeystoreSecretStore
import com.thomaswcode.dictationapp.platform.SqliteHistoryRepository
import com.thomaswcode.dictationapp.platform.access.AccessibilityBridge
import com.thomaswcode.dictationapp.platform.audio.AndroidAudioCaptureFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class DictationApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}

/** Everything wired together once per process: the Android counterpart of the Windows Generic Host. */
class AppGraph(val app: Application) {
    val logger = AppLog(File(app.filesDir, "logs"))
    val settings = JsonSettingsStore(File(app.filesDir, "settings.json"), logger)
    val secrets = KeystoreSecretStore()
    val keys = SettingsApiKeyProvider(settings, secrets)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    val paths = AppPaths(app.filesDir)
    val history = SqliteHistoryRepository(app)
    val hub = StatusHub()
    val clipboard = AndroidClipboard(app)
    val notifier = AndroidNotifier(app)
    val bridge = AccessibilityBridge(clipboard, logger)
    val llm = LlmPostProcessor(http, keys, { settings.current }, logger)
    val retention = HistoryRetention(history, settings, paths, logger)

    /** Epoch millis until which the bubble is hidden (dragged onto the "Hide 10 min" target). */
    val snoozedUntil = MutableStateFlow(0L)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val orchestrator = DictationOrchestrator(
        captures = AndroidAudioCaptureFactory(app, logger),
        sinks = { path -> WavFileSink(path) },
        transcribers = { AssemblyAiStreamingTranscriber(http, keys, logger) },
        foreground = bridge,
        inserter = bridge,
        clipboard = clipboard,
        notifier = notifier,
        postProcessor = PostProcessorRouter(PassthroughPostProcessor(), llm),
        history = history,
        settings = settings,
        hub = hub,
        paths = paths,
        logger = logger,
        hasMicrophonePermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
    )

    fun start() {
        logger.info("DictationApp ${BuildConfig.VERSION_NAME} starting")
        orchestrator.start(scope)
        scope.launch {
            while (true) {
                retention.run()
                delay(60 * 60 * 1000L)
            }
        }
    }
}
