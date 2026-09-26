package com.thomaswcode.dictationapp.core.settings

import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.rules.LegacyAppRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface SettingsStore {
    val flow: StateFlow<AppSettings>

    val current: AppSettings get() = flow.value

    /**
     * Applies [transform] to the latest settings and persists the result atomically; returns the settings now in force.
     * A store that cannot save keeps the settings as they were and returns those.
     */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

/**
 * JSON file store with atomic writes: a temp file is written and synced to disk, then moved over the old one in a
 * single rename, so a crash or power cut leaves the old settings or the new ones, never neither. A corrupt file is
 * moved aside and defaults are used, so a bad edit never bricks the app. Unknown keys and unknown enum values are
 * tolerated so older builds can read newer files. When the file cannot be written (storage full), the settings stay
 * as they were and [onWriteFailed] hears why, rather than the button or bubble drag that asked crashing the app.
 */
class JsonSettingsStore(
    private val file: File,
    private val logger: Logger,
    private val onWriteFailed: ((Exception) -> Unit)? = null,
) : SettingsStore {
    private val lock = Any()
    private val state = MutableStateFlow(load())

    override val flow: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val current = state.value
        val next = transform(current)
        if (next == current) return current
        try {
            write(next)
        } catch (e: Exception) {
            logger.error("Could not save the settings; they stay as they were", e)
            onWriteFailed?.invoke(e)
            return current
        }

        state.value = next
        next
    }

    private fun write(settings: AppSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(AppSettings.serializer(), settings).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }

        // rename(2): the old file is replaced in one step; there is never a moment with no settings file.
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            val loaded = json.decodeFromString(AppSettings.serializer(), file.readText())
            val migrated = migrate(loaded)
            if (migrated != loaded) runCatching { write(migrated) }.onFailure { logger.warn("Could not save migrated settings", it) }
            migrated
        } catch (e: Exception) {
            if (e !is SerializationException && e !is IOException && e !is IllegalArgumentException) throw e
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
            val backup = File(file.path + ".corrupt-" + stamp)
            logger.error("settings.json is unreadable; moving it to ${backup.name} and using defaults", e)
            runCatching { file.renameTo(backup) }
            AppSettings()
        }
    }

    /**
     * Schema 2: app rules start empty and every app follows the Style defaults. The rules 0.1.0 seeded (Gmail formal,
     * WhatsApp casual, ...) are removed while they are still seeds; one whose insertion method, hint or on/off switch the
     * user changed is kept, as is every rule for another app.
     */
    private fun migrate(settings: AppSettings): AppSettings {
        var s = settings
        if (s.schemaVersion < 2) {
            val kept = s.appRules.filterNot(LegacyAppRules::isUnmodifiedSeed)
            logger.info("Migrated settings to schema 2: removed ${s.appRules.size - kept.size} seeded app rules, kept ${kept.size}")
            s = s.copy(appRules = kept, schemaVersion = 2)
        }

        return s
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}

/** Reversible protection for secrets at rest (the Android Keystore; DPAPI on Windows). */
interface SecretStore {
    fun protect(plaintext: String): String

    /** Returns null when the value cannot be unprotected (key lost after a reinstall, corrupt). */
    fun unprotect(protectedValue: String): String?
}

interface ApiKeyProvider {
    /** AssemblyAI key for streaming transcription. */
    fun assemblyAiKey(): String?

    /** Groq (OpenAI-compatible) key for cleanup and tone. Null when cleanup is unavailable. */
    fun llmKey(): String?
}

class SettingsApiKeyProvider(private val settings: SettingsStore, private val secrets: SecretStore) : ApiKeyProvider {
    override fun assemblyAiKey(): String? = resolve(settings.current.apiKeyProtected)

    override fun llmKey(): String? = resolve(settings.current.groqApiKeyProtected)

    private fun resolve(protectedValue: String?): String? {
        if (protectedValue.isNullOrEmpty()) return null
        return secrets.unprotect(protectedValue)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Where history audio lives. */
class AppPaths(val dataDir: File) {
    val audioDir: File get() = File(dataDir, "audio")

    fun newAudioPath(startedAt: Long): String {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(startedAt)) + ".wav"
        return File(audioDir, name).path
    }
}
