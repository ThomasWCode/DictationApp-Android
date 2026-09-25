package com.thomaswcode.dictationapp.platform.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.audio.AudioCapture
import com.thomaswcode.dictationapp.core.audio.AudioCaptureFactory
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.MicrophoneAccessDeniedException
import com.thomaswcode.dictationapp.core.audio.WavReplayAudioCapture
import java.io.IOException
import kotlin.concurrent.thread

/**
 * AudioRecord at 16 kHz mono PCM16 (no resampling needed on Android), sliced into 100 ms frames on a
 * dedicated thread. VOICE_RECOGNITION is the source tuned for speech-to-text: no heavy AGC or call-style
 * noise suppression. An accessibility service may record while another app is in front, which is what
 * lets the bubble dictate into any app.
 */
class MicrophoneCapture(
    private val context: Context,
    private val deviceKey: String?,
    private val logger: Logger,
) : AudioCapture {
    override var onFrame: ((AudioFrame) -> Unit)? = null
    override var onFault: ((Throwable) -> Unit)? = null
    override var onCompleted: (() -> Unit)? = null

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun start() {
        if (running) return
        running = true
        worker = thread(name = "mic-capture", priority = Thread.MAX_PRIORITY) { captureLoop() }
    }

    /** Stops and waits briefly for the last frame so the tail of the speech is delivered before we return. */
    override fun stop() {
        running = false
        val w = worker
        if (w != null && w != Thread.currentThread()) runCatching { w.join(400) }
    }

    override fun close() = stop()

    @SuppressLint("MissingPermission") // The orchestrator checks RECORD_AUDIO before creating a capture.
    private fun captureLoop() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        var usedCommunicationDevice = false
        var usedLegacySco = false
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
            onFault?.invoke(IOException("The microphone could not be opened: ${e.message}"))
            return
        }

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw MicrophoneAccessDeniedException("The microphone could not be opened (is another app using it, or is access blocked?).")
            }

            InputDevices.find(context, deviceKey)?.let { device ->
                record.preferredDevice = device
                // Bluetooth headset microphones only carry audio once they are the communication device.
                // setCommunicationDevice is API 31; Android 11 (minSdk 30) uses the older SCO switch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                ) {
                    usedCommunicationDevice = audioManager.setCommunicationDevice(device)
                } else if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                    usedLegacySco = true
                }

                logger.info("Recording from ${InputDevices.nameOf(device)}")
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicrophoneAccessDeniedException("The microphone did not start (another app may be using it).")
            }

            val buffer = ByteArray(AudioFrame.FRAME_BYTES)
            var position = 0L
            while (running) {
                var read = 0
                while (read < buffer.size && running) {
                    val n = record.read(buffer, read, buffer.size - read)
                    if (n < 0) throw IOException("AudioRecord.read returned $n")
                    read += n
                }

                if (read > 0) {
                    val pcm = buffer.copyOf(read)
                    onFrame?.invoke(AudioFrame(pcm, AudioFrame.peakOf(pcm), position))
                    position += read * 1000L / (AudioFrame.SAMPLE_RATE * 2)
                }
            }
        } catch (e: Exception) {
            running = false
            logger.warn("Microphone capture failed: ${e.message}", e)
            onFault?.invoke(e)
        } finally {
            runCatching { record.stop() }
            record.release()
            if (usedCommunicationDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audioManager.clearCommunicationDevice() }
            if (usedLegacySco) {
                runCatching {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                }
            }
        }
    }
}

class AndroidAudioCaptureFactory(private val context: Context, private val logger: Logger) : AudioCaptureFactory {
    override fun createMicrophone(deviceKey: String?): AudioCapture = MicrophoneCapture(context, deviceKey, logger)

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
