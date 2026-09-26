package com.thomaswcode.dictationapp.platform.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

/** Which device a dictation records from, and which one is selected to bring up a Bluetooth headset's microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class InputDevicesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** A device as Android lists it: a microphone is a source, a headset's earpiece a sink. */
    private fun device(type: Int, sink: Boolean, address: String = ""): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(type).build().also {
            val port = ReflectionHelpers.getField<Any>(it, "mPort")
            ReflectionHelpers.setField(port, "mRole", if (sink) ROLE_SINK else ROLE_SOURCE)
            ReflectionHelpers.setField(port, "mAddress", address)
            // The builder knows only the output codes; a microphone carries its input code.
            if (!sink) ReflectionHelpers.setField(port, "mType", INPUT_CODES.getValue(type))
        }

    @Test
    fun `a Bluetooth microphone is brought up through its headset's output, never itself`() {
        // Selecting the microphone itself as the communication device is refused: it must be a sink.
        val microphone = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = false, address = "00:11:22:33:44:55")
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = true, address = "00:11:22:33:44:55")
        val otherHeadset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = true, address = "66:77:88:99:AA:BB")
        val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, sink = true)
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(earpiece, otherHeadset, headset))

        assertSame(headset, InputDevices.communicationOutputFor(audioManager, microphone))
    }

    @Test
    fun `without a matching address only a lone headset of that kind is taken`() {
        val microphone = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = false)
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = true, address = "00:11:22:33:44:55")
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true), headset))
        assertSame(headset, InputDevices.communicationOutputFor(audioManager, microphone))

        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(headset, device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = true, address = "66:77:88:99:AA:BB")))
        assertNull(InputDevices.communicationOutputFor(audioManager, microphone))
    }

    @Test
    fun `the phone's microphone is chosen when a headset is connected too, and left to Android otherwise`() {
        val phone = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, sink = false)
        shadowOf(audioManager).setInputDevices(listOf(phone))
        assertNull(InputDevices.phoneMicrophoneBesideOthers(context))

        shadowOf(audioManager).setInputDevices(listOf(device(AudioDeviceInfo.TYPE_WIRED_HEADSET, sink = false), phone))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, InputDevices.phoneMicrophoneBesideOthers(context)?.type)

        shadowOf(audioManager).setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sink = false), phone))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, InputDevices.phoneMicrophoneBesideOthers(context)?.type)
    }

    private companion object {
        // AudioPort's roles and AudioSystem's input device codes (hidden classes).
        const val ROLE_SOURCE = 1
        const val ROLE_SINK = 2
        val INPUT_CODES = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC to 0x80000004.toInt(),
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to 0x80000008.toInt(),
            AudioDeviceInfo.TYPE_WIRED_HEADSET to 0x80000010.toInt(),
        )
    }
}
