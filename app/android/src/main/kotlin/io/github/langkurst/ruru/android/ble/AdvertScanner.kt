package io.github.langkurst.ruru.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.langkurst.ruru.link.HeadsetAdvert
import io.github.langkurst.ruru.link.LogRing
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Listens for Apple's proximity-pairing adverts (AirPods and Beats battery) in one short window.
 * Read-only: no connection, nothing sent. Adverts come from random addresses; each advertiser gets
 * an opaque [AdvertRules.key] (a salted hash, never the address) so the shared reader can follow
 * one of them. The caller decides when to listen (only while Ruru is in front) and checks
 * [BleScanAccess] first. Pattern from Earshot's AppleAdvertScanner (MIT, see CREDITS.md).
 */
internal class AdvertScanner(private val context: Context) {

    @SuppressLint("MissingPermission") // the caller checked BleScanAccess
    suspend fun listen(windowMs: Long): List<HeadsetAdvert> {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return emptyList()
        val heard = LinkedHashMap<String, HeadsetAdvert>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(AdvertRules.APPLE_COMPANY_ID) ?: return
                if (!AdvertRules.isProximityPairing(data)) return
                val key = AdvertRules.key(result.device.address, SALT)
                val advert = HeadsetAdvert(AdvertRules.APPLE_COMPANY_ID, data, result.scanRecord?.deviceName, key, result.rssi)
                // The last one heard from an advertiser is the newest.
                synchronized(this) {
                    heard.remove(key)
                    heard[key] = advert
                }
            }

            override fun onScanFailed(errorCode: Int) {
                LogRing.add('W', TAG, "advert scan failed: $errorCode")
            }
        }
        val filter = ScanFilter.Builder()
            // Proximity pairing only: type 0x07 in the first byte of Apple's manufacturer data.
            .setManufacturerData(AdvertRules.APPLE_COMPANY_ID, byteArrayOf(AdvertRules.PROXIMITY_PAIRING), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val started = runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { LogRing.add('W', TAG, "advert scan refused: ${it.message}") }
            .isSuccess
        if (!started) return emptyList()
        try {
            delay(windowMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return synchronized(callback) { heard.values.toList() }
    }

    private companion object {
        const val TAG = "Adverts"

        /** Per run of the app: a key means the same advertiser for as long as Ruru runs, and nothing after. */
        val SALT = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }
}

/** The pure rules of [AdvertScanner], for the tests. */
internal object AdvertRules {
    const val APPLE_COMPANY_ID = 0x004C

    /** The first byte of Apple's manufacturer data for AirPods and Beats proximity pairing. */
    const val PROXIMITY_PAIRING: Byte = 0x07

    fun isProximityPairing(data: ByteArray?): Boolean = data != null && data.isNotEmpty() && data[0] == PROXIMITY_PAIRING

    /**
     * A stable, opaque name for an advertiser: a salted SHA-256 of its address, shortened. The
     * address itself never leaves the platform layer.
     */
    fun key(address: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(address.uppercase().toByteArray())
        }.digest()
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
