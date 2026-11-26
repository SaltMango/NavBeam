package io.github.saltmango.navbeam.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Utility to fetch the user-defined device name (e.g. "Sre's S25 Ultra").
 * Used to identify the phone on the dash's "Connected to X" screen.
 */
object DeviceName {
    fun get(context: Context): String {
        val resolver = context.contentResolver

        val btAdapterName = runCatching {
            val hasBtPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (hasBtPermission) {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bluetoothManager?.adapter?.name
            } else {
                null
            }
        }.getOrNull()

        val secure = runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull()
        val global = runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        val globalLegacy = runCatching { Settings.Global.getString(resolver, "device_name") }.getOrNull()
        val system = runCatching { Settings.System.getString(resolver, "device_name") }.getOrNull()

        android.util.Log.d(
            "DeviceName",
            "Lookup: btAdapter=$btAdapterName, secure=$secure, global=$global, globalLegacy=$globalLegacy, system=$system",
        )

        // Priority order: Bluetooth adapter name first (modern API), then bluetooth_name settings, then global settings.
        val names = listOf(btAdapterName, secure, global, globalLegacy, system)

        for (name in names) {
            if (!name.isNullOrBlank()) {
                // Conservative filter: Letters, Digits, and Spaces only.
                // Truncate to 12 chars (safest limit for dash displays).
                val sanitized = name.filter { it.isLetterOrDigit() || it == ' ' }.trim()
                if (sanitized.isNotBlank()) {
                    val result = if (sanitized.length > 12) sanitized.take(12).trim() else sanitized
                    android.util.Log.i("DeviceName", "Selected: '$result' (from raw '$name')")
                    return result
                }
            }
        }

        // Fallback to Brand + Model (e.g. "Samsung S25")
        val brand = Build.BRAND?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: "Phone"
        val rawFallback = if (brand.isNotBlank() && !model.contains(brand, ignoreCase = true)) {
            "$brand $model"
        } else {
            model
        }

        val fallback = rawFallback.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        val result = if (fallback.length > 12) fallback.take(12).trim() else fallback
        android.util.Log.i("DeviceName", "Selected Fallback: '$result'")
        return result.ifBlank { "NavBeam" }
    }
}
