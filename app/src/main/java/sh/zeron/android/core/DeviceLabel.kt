package sh.zeron.android.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * This phone's name for presence and attribution (`CoreConfig.deviceName`,
 * the port of iOS's `UIDevice.current.name`): the user's own device name
 * if set, else the model, else `"Android"` — never blank.
 *
 * Read once per process at startup. Label *sanitising* (control
 * characters, the byte cap) is the Rust core's job — Kotlin only picks
 * the name.
 */
object DeviceLabel {

    fun read(context: Context): String {
        val deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Android"
    }
}
