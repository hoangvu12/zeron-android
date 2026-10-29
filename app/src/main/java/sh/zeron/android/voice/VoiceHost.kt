package sh.zeron.android.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import sh.zeron.android.design.LocalFeedback
import sh.zeron.android.design.Toaster

/**
 * The call's app-wide side effects (#60, the iOS scene-level pieces of
 * `RemoteVoiceController`), composed once inside the root's feedback and
 * toast hosts. Draws nothing:
 *
 * - the microphone prompt the call's Prepare step asks for (iOS asks
 *   inside the call too), with the notification permission riding along
 *   for the call notification — only the microphone is required;
 * - the screen kept awake while a call runs (iOS `isIdleTimerDisabled`);
 * - the controller's haptics, through the root's [LocalFeedback];
 * - a failure while the stage isn't showing → a toast with **Settings**
 *   (microphone denied) or **Retry** (call again, then the stage).
 */
@Composable
fun VoiceHost(controller: VoiceController, toaster: Toaster) {
    val context = LocalContext.current
    val view = LocalView.current
    val feedback = LocalFeedback.current
    DisposableEffect(controller, feedback, toaster) {
        controller.haptic = feedback::haptic
        controller.onNotice = { notice ->
            toaster.show(notice.message, notice.action) {
                controller.noticeAction(notice) { openMicrophoneSettings(context) }
            }
        }
        onDispose {
            controller.haptic = {}
            controller.onNotice = {}
        }
    }

    val snapshot by controller.snapshot.collectAsState()
    DisposableEffect(snapshot.live) {
        view.keepScreenOn = snapshot.live
        onDispose { view.keepScreenOn = false }
    }

    val request by controller.permissionRequest.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        controller.completePermission(result[Manifest.permission.RECORD_AUDIO] == true)
    }
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        runCatching { launcher.launch(permissions) }.onFailure { controller.completePermission(false) }
    }
}

/** iOS `openSettingsURLString`: the app's details page, where the microphone permission lives. */
private fun openMicrophoneSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
