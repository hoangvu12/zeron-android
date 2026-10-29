package sh.zeron.android.core

import android.content.Context
import java.util.UUID

/**
 * This install's stable device id (`CoreConfig.deviceId`), minted once and
 * kept in its own prefs file — the port of Zeron Android's
 * `AppModel.deviceId` (`android-` + 8 hex). Committed synchronously: a
 * second process start must never mint a second id.
 */
object DeviceId {

    fun read(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = "android-" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY, id).commit()
        return id
    }

    private const val KEY = "deviceId"
}
