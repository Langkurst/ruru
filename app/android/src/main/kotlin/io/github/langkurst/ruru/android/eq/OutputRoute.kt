package io.github.langkurst.ruru.android.eq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether music is going to Bluetooth headphones. A fake of this runs the backend in tests. */
interface OutputRoute {
    val bluetooth: StateFlow<Boolean>

    /** The sample rate of the sound output, or null when unknown. */
    val sampleRate: Int?

    /** Look again (the output switcher does not always call back). */
    fun refresh()
}

/** The real route: Android's audio device list, and on Android 13 and newer where music is routed. */
class AndroidOutputRoute(context: Context) : OutputRoute {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val current = MutableStateFlow(false)
    override val bluetooth: StateFlow<Boolean> = current

    override val sampleRate: Int? = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        refresh()
        audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    override fun refresh() {
        val am = audio ?: return
        current.value = runCatching {
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Where music is routed now: picking "This phone" in the output switcher counts as no headphones.
                am.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            } else {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
            EqRunPolicy.isBluetoothHeadphones(devices.map { it.type })
        }.getOrDefault(false)
    }
}
