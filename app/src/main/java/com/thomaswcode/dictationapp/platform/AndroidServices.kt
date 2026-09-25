package com.thomaswcode.dictationapp.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.history.HistoryRepository
import com.thomaswcode.dictationapp.core.insertion.Clipboard
import com.thomaswcode.dictationapp.core.insertion.Notifier
import com.thomaswcode.dictationapp.core.insertion.ToastKind
import com.thomaswcode.dictationapp.core.settings.AppPaths
import com.thomaswcode.dictationapp.core.settings.SettingsStore
import com.thomaswcode.dictationapp.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidClipboard(private val context: Context) : Clipboard {
    override suspend fun setText(text: String) = withContext(Dispatchers.Main) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }
}

/** Toasts stand in for the Windows tray notifications. */
class AndroidNotifier(private val context: Context) : Notifier {
    private val main = Handler(Looper.getMainLooper())

    override fun toast(title: String, message: String, kind: ToastKind) {
        val text = if (message.isBlank()) title else "$title: $message"
        main.post { Toast.makeText(context, text, if (kind == ToastKind.Error || text.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    override fun requestMicrophonePermission() {
        main.post {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_REQUEST_MICROPHONE, true),
            )
        }
    }
}

/**
 * Applies the retention settings: text older than the history retention is deleted, audio older than the
 * audio retention is removed from its record and from disk, and WAVs no record points to are swept. Runs
 * at startup and hourly, like the Windows HistoryRetentionService.
 */
class HistoryRetention(
    private val history: HistoryRepository,
    private val settings: SettingsStore,
    private val paths: AppPaths,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        try {
            val s = settings.current
            val now = clock()
            var removedFiles = 0
            s.historyRetention.millis?.let { age ->
                history.deleteOlderThan(now - age).forEach { if (File(it).delete()) removedFiles++ }
            }

            s.audioRetention.millis?.let { age ->
                history.clearAudioOlderThan(now - age).forEach { if (File(it).delete()) removedFiles++ }
            }

            // Orphans: WAVs no record references (a crash mid-dictation). Recent files may be recording now.
            val referenced = history.referencedAudioPaths()
            paths.audioDir.listFiles()
                ?.filter { it.isFile && it.path !in referenced && now - it.lastModified() > ORPHAN_GRACE_MS }
                ?.forEach { if (it.delete()) removedFiles++ }
            if (removedFiles > 0) logger.info("Retention removed $removedFiles audio file(s)")
        } catch (e: Exception) {
            logger.warn("Retention pass failed", e)
        }
    }

    suspend fun deleteAll(): Int {
        val paths = history.deleteAll()
        paths.forEach { File(it).delete() }
        // Unreferenced files go too, except one a dictation in progress is still writing: deleting it would leave that
        // dictation's record pointing at nothing. Retention removes it later if it ends up orphaned.
        val now = clock()
        this.paths.audioDir.listFiles()?.filter { now - it.lastModified() > ACTIVE_RECORDING_GRACE_MS }?.forEach { it.delete() }
        return paths.size
    }

    companion object {
        private const val ORPHAN_GRACE_MS = 30 * 60 * 1000L

        /** A recording in progress rewrites its WAV every 100 ms; anything untouched for a minute is not being recorded. */
        private const val ACTIVE_RECORDING_GRACE_MS = 60 * 1000L
    }
}
