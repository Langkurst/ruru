package io.github.langkurst.ruru.android.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/**
 * Whether a BLE scan by this app returns anything. Android 12 and newer ask for BLUETOOTH_SCAN
 * (declared `neverForLocation`: Ruru never works out where anyone is). Android 9 to 11 have no such
 * permission: there `startScan` succeeds but delivers nothing unless the app holds the location
 * permission (fine location on 10 and 11, coarse is enough on 9) and location is turned on, and
 * nothing says why. Ruru scans only to hear AirPods and Beats battery adverts and to find a JBL
 * `<name>-LE` control address, never for location.
 *
 * One rule for every scanner, so a scan that would come back empty is skipped with the reason
 * instead of looking like "no headset nearby". From Earshot (MIT, see CREDITS.md).
 */
internal object BleScanAccess {

    /** What is missing for a scan with results, or null when nothing is. Pure, for the tests. */
    fun missing(sdk: Int, scanGranted: Boolean, fineGranted: Boolean, coarseGranted: Boolean, locationOn: Boolean): String? = when {
        sdk >= Build.VERSION_CODES.S -> if (scanGranted) null else "the Nearby devices permission"
        sdk >= Build.VERSION_CODES.Q && !fineGranted -> "the precise location permission (Android 10 and 11)"
        !fineGranted && !coarseGranted -> "the location permission (Android 9)"
        !locationOn -> "location turned on (Android 11 and older)"
        else -> null
    }

    /** The permission to ask for so a scan can return results on [sdk], or null when none can help. */
    fun permissionToAsk(sdk: Int): String = if (sdk >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

    fun missing(context: Context): String? {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return missing(Build.VERSION.SDK_INT, scanGranted = granted(Manifest.permission.BLUETOOTH_SCAN), fineGranted = false, coarseGranted = false, locationOn = true)
        }
        // Unknown (no LocationManager) counts as on: never block a scan on a guess.
        val locationOn = runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() ?: true
        return missing(
            Build.VERSION.SDK_INT,
            scanGranted = false,
            fineGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            locationOn = locationOn,
        )
    }
}
