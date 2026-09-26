package com.thomaswcode.dictationapp.platform.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.audio.AudioCapture
import com.thomaswcode.dictationapp.core.audio.AudioCaptureFactory
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.MicrophoneAccessDeniedException
import com.thomaswcode.dictationapp.core.audio.MicrophoneUnavailableException
import com.thomaswcode.dictationapp.core.audio.WavReplayAudioCapture
import com.thomaswcode.dictationapp.core.insertion.Notifier
import com.thomaswcode.dictationapp.core.insertion.ToastKind
import java.io.IOException
import kotlin.concurrent.thread

/**
 * AudioRecord at 16 kHz mono PCM16 (no resampling needed on Android), sliced into 100 ms frames on a
 * dedicated thread. VOICE_RECOGNITION is the source tuned for speech-to-text: no heavy AGC or call-style
 * noise suppression. An accessibility service may record while another app is in front, which is what
 * lets the bubble dictate into any app.
 *
 * The microphone chosen in Settings > Audio is used, or else the phone's own: a connected headset is used only
 * when chosen. [notice] hears about a chosen Bluetooth microphone that could not be connected.
 */
class MicrophoneCapture(
    private val context: Context,
    private val deviceKey: String?,
    private val logger: Logger,
    private val notice: ((String) -> Unit)? = null,
) : AudioCapture {
    override var onFrame: ((AudioFrame) -> Unit)? = null
    override var onFault: ((Throwable) -> Unit)? = null
    override var onCompleted: (() -> Unit)? = null

    /** The microphone recording now ("Phone microphone", "Bluetooth headset (…)"), once recording has started. */
    @Volatile var recordingFrom: String? = null
        private set

    @Volatile private var running = false
    private var worker: Thread? = null
    private val recordLock = Any()

    /** The recorder while the worker owns it; [stop] uses it to unblock a read stuck on a stalled device. */
    private var activeRecord: AudioRecord? = null

    override fun start() {
        if (running) return
        running = true
        worker = thread(name = "mic-capture", priority = Thread.MAX_PRIORITY) { captureLoop() }
    }

    /**
     * Stops and waits briefly for the last frame so the tail of the speech is delivered before we return. If the
     * read is blocked (the device stalled or disconnected), stopping the recorder releases it, so the worker never
     * keeps the microphone or a Bluetooth route after the session has ended.
     */
    override fun stop() {
        running = false
        val w = worker
        if (w == null || w == Thread.currentThread()) return
        runCatching { w.join(250) }
        if (w.isAlive) {
            synchronized(recordLock) { runCatching { activeRecord?.stop() } }
            runCatching { w.join(400) }
        }
    }

    override fun close() = stop()

    @SuppressLint("MissingPermission") // The orchestrator checks RECORD_AUDIO before creating a capture.
    private fun captureLoop() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        var route: BluetoothRoute? = null
        val record = try {
            val minBuffer = AudioRecord.getMinBufferSize(AudioFrame.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioFrame.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, AudioFrame.FRAME_BYTES * 4),
            )
        } catch (e: SecurityException) {
            running = false
            onFault?.invoke(MicrophoneAccessDeniedException("Microphone access was refused."))
            return
        } catch (e: IllegalArgumentException) {
            running = false
            onFault?.invoke(MicrophoneUnavailableException("The microphone could not be opened: ${e.message}"))
            return
        }

        synchronized(recordLock) { activeRecord = record }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // Access is granted (checked before a capture is created), so this is a busy or missing device.
                throw MicrophoneUnavailableException("The microphone could not be opened; another app may be using it.")
            }

            // The chosen microphone, or the phone's own: a connected headset is not used unless chosen.
            val chosen = InputDevices.find(context, deviceKey)
            if (!deviceKey.isNullOrEmpty() && chosen == null) logger.info("The chosen microphone is not connected; using the phone's")
            val phone = InputDevices.phoneMicrophoneBesideOthers(context)
            var switchTo: AudioDeviceInfo? = null
            if (chosen != null && InputDevices.isBluetooth(chosen)) {
                // A Bluetooth headset's microphone carries audio only over its call link, which takes a second or
                // so to come up: the phone's microphone records until then, so no words are lost.
                route = BluetoothRoute.request(context, audioManager, chosen, logger)
                if (route != null) switchTo = chosen else bluetoothFailed(chosen, "it could not be switched to its call microphone")
                phone?.let { record.preferredDevice = it }
            } else {
                (chosen ?: phone)?.let { record.preferredDevice = it }
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicrophoneUnavailableException("The microphone did not start; another app may be using it.")
            }

            recordingFrom = record.routedDevice?.let(InputDevices::nameOf) ?: "the default microphone"
            logger.info("Recording from $recordingFrom" + (chosen?.let { " (chosen: ${InputDevices.nameOf(it)})" } ?: ""))

            val buffer = ByteArray(AudioFrame.FRAME_BYTES)
            var position = 0L
            while (running) {
                var read = 0
                while (read < buffer.size && running) {
                    val n = record.read(buffer, read, buffer.size - read)
                    if (n < 0) {
                        if (!running) break // stop() stopped the recorder to unblock this read
                        throw IOException("AudioRecord.read returned $n")
                    }

                    read += n
                }

                if (read > 0) {
                    // A short last read (the stop came mid-frame) is zero-padded to a whole frame: AssemblyAI takes
                    // chunks of 50 ms or more, and the speech in it is kept.
                    val pcm = buffer.copyOf()
                    if (read < pcm.size) pcm.fill(0, read, pcm.size)
                    onFrame?.invoke(AudioFrame(pcm, AudioFrame.peakOf(pcm, read), position))
                    position += read * 1000L / (AudioFrame.SAMPLE_RATE * 2)
                }

                // Between frames: switch to the Bluetooth microphone once its call link is up, or give up on it.
                val target = switchTo ?: continue
                val link = route ?: continue
                if (link.isUp()) {
                    switchTo = null
                    if (record.setPreferredDevice(target)) {
                        recordingFrom = InputDevices.nameOf(target)
                        logger.info("Bluetooth call link up; recording from $recordingFrom")
                    } else {
                        link.close()
                        route = null
                        bluetoothFailed(target, "its microphone could not be selected")
                    }
                } else if (link.timedOut()) {
                    switchTo = null
                    link.close()
                    route = null
                    bluetoothFailed(target, "its call link did not come up")
                }
            }
        } catch (e: Exception) {
            running = false
            logger.warn("Microphone capture failed: ${e.message}", e)
            onFault?.invoke(e)
        } finally {
            synchronized(recordLock) {
                activeRecord = null
                runCatching { record.stop() }
                record.release()
            }
            route?.close()
        }
    }

    private fun bluetoothFailed(device: AudioDeviceInfo, why: String) {
        logger.warn("Bluetooth microphone ${InputDevices.nameOf(device)} not used: $why; recording from the phone's microphone")
        notice?.invoke("${InputDevices.nameOf(device)} could not be used ($why), so the phone's microphone is recording.")
    }
}

/**
 * A Bluetooth headset's call link, the only way its microphone carries audio. Android 12 and later select the headset as
 * the communication device, which must be one of its outputs (the one with the chosen microphone's address); Android 11
 * uses the older SCO switch, which needs MODIFY_AUDIO_SETTINGS (declared for Android 11 only).
 */
class BluetoothRoute private constructor(
    private val context: Context,
    private val audioManager: AudioManager,
    /** Android 12+: the communication device asked for; null for the older SCO switch. */
    private val sink: AudioDeviceInfo?,
    private val deadline: Long,
) {
    /** True once the headset's call link is up. */
    fun isUp(): Boolean =
        if (sink != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.id == sink.id else scoConnected()

    fun timedOut(): Boolean = SystemClock.elapsedRealtime() >= deadline

    /** Hands the link back: only this app's request is withdrawn, never a call's. */
    fun close() {
        runCatching {
            if (sink != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }
    }

    /** The SCO state from its sticky broadcast (reading it registers nothing). */
    private fun scoConnected(): Boolean {
        val state = runCatching { context.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) }.getOrNull()
        return state?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR) == AudioManager.SCO_AUDIO_STATE_CONNECTED
    }

    companion object {
        /** How long the call link may take to come up before the phone's microphone is kept. */
        const val CONNECT_TIMEOUT_MS = 2_500L

        /** Asks for [input]'s call link; null when it cannot be asked for (no matching headset output, or refused). */
        fun request(context: Context, audioManager: AudioManager, input: AudioDeviceInfo, logger: Logger): BluetoothRoute? {
            val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sink = InputDevices.communicationOutputFor(audioManager, input) ?: return null
                val accepted = runCatching { audioManager.setCommunicationDevice(sink) }
                    .onFailure { logger.warn("setCommunicationDevice(${InputDevices.nameOf(sink)}) failed", it) }
                    .getOrDefault(false)
                return if (accepted) BluetoothRoute(context, audioManager, sink, deadline) else null
            }

            if (input.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return null
            return runCatching {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                BluetoothRoute(context, audioManager, null, deadline)
            }.onFailure { logger.warn("startBluetoothSco failed", it) }.getOrNull()
        }
    }
}

/** [notifier] shows a chosen Bluetooth microphone that could not be used, once per run (every time in the log). */
class AndroidAudioCaptureFactory(private val context: Context, private val logger: Logger, private val notifier: Notifier? = null) : AudioCaptureFactory {
    @Volatile private var warnedAboutBluetooth = false

    override fun createMicrophone(deviceKey: String?): AudioCapture = MicrophoneCapture(context, deviceKey, logger) { message ->
        if (!warnedAboutBluetooth) {
            warnedAboutBluetooth = true
            notifier?.toast("Bluetooth microphone not used", message, ToastKind.Warning)
        }
    }

    override fun createWavReplay(path: String, speed: Double): AudioCapture = WavReplayAudioCapture(path, speed)
}

/** Input devices the user can pick in Settings > Audio. Keys survive reboots (AudioDeviceInfo ids do not). */
object InputDevices {
    data class Device(val key: String, val name: String)

    private val supported = buildSet {
        add(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    fun list(context: Context): List<Device> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in supported }
            .map { Device(keyOf(it), nameOf(it)) }
            .distinctBy { it.key }

    fun find(context: Context, key: String?): AudioDeviceInfo? {
        if (key.isNullOrEmpty()) return null
        return context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { keyOf(it) == key }
    }

    fun isBluetooth(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /**
     * The phone's own microphone when a headset or USB microphone is connected too, which Android might otherwise
     * record from; null when the phone's is the only one (Android's own choice is then left alone).
     */
    fun phoneMicrophoneBesideOthers(context: Context): AudioDeviceInfo? {
        val inputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in supported }
        val phone = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: return null
        return phone.takeIf { inputs.any { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC } }
    }

    /**
     * The communication device for a Bluetooth [input]: the same headset's output (a microphone cannot be one). Matched
     * by address, or by type when only one headset of that type is available.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun communicationOutputFor(audioManager: AudioManager, input: AudioDeviceInfo): AudioDeviceInfo? {
        val outputs = audioManager.availableCommunicationDevices.filter { it.isSink && it.type == input.type }
        return outputs.firstOrNull { input.address.isNotEmpty() && it.address == input.address } ?: outputs.singleOrNull()
    }

    fun keyOf(d: AudioDeviceInfo): String = "${d.type}:${d.productName}"

    fun nameOf(d: AudioDeviceInfo): String {
        val product = d.productName?.toString()?.takeIf { it.isNotBlank() }
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> return "Phone microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
            else -> "Microphone"
        }
        return if (product != null) "$kind ($product)" else kind
    }
}

/** Plays a stored dictation WAV in History. */
class WavPlayer {
    private var player: MediaPlayer? = null

    var onStopped: (() -> Unit)? = null

    val isPlaying: Boolean get() = player != null

    fun play(path: String) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
        onStopped?.invoke()
    }
}
