package com.thomaswcode.dictationapp.core.settings

import com.thomaswcode.dictationapp.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface SettingsStore {
    val flow: StateFlow<AppSettings>

    val current: AppSettings get() = flow.value

    /** Applies [transform] to the latest settings and persists the result atomically. */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

/**
 * JSON file store with atomic writes (write a temp file, then rename over the old one). A corrupt file is
 * moved aside and defaults are used, so a bad edit never bricks the app. Unknown keys and unknown enum
 * values are tolerated so older builds can read newer files.
 */
class JsonSettingsStore(private val file: File, private val logger: Logger) : SettingsStore {
    private val lock = Any()
    private val state = MutableStateFlow(load())

    override val flow: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val next = transform(state.value)
        if (next != state.value) {
            write(next)
            state.value = next
        }

        next
    }

    private fun write(settings: AppSettings) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(AppSettings.serializer(), settings))
        if (!tmp.renameTo(file)) {
            // renameTo does not replace on every filesystem; fall back to delete + rename.
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not replace ${file.path}")
        }
    }

    private fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), file.readText())
        } catch (e: Exception) {
            if (e !is SerializationException && e !is IOException && e !is IllegalArgumentException) throw e
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
            val backup = File(file.path + ".corrupt-" + stamp)
            logger.error("settings.json is unreadable; moving it to ${backup.name} and using defaults", e)
            runCatching { file.renameTo(backup) }
            AppSettings()
        }
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
