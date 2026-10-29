package sh.zeron.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import sh.zeron.android.design.GroupedRow
import sh.zeron.android.design.ZeronIcons
import sh.zeron.android.design.groupedSection
import sh.zeron.android.design.settingsTag
import sh.zeron.android.voice.VoiceSettingsCopy
import sh.zeron.android.voice.settingsVoiceSubtitle

/**
 * #61: Settings' "Voice" section (iOS `MoreViewController.swift:213-216`,
 * port 04 G25) — one row, "Voice", its subtitle the chosen style; a tap
 * pushes Voice settings. [SettingsScreen] emits it only while voice is
 * offered.
 */
fun LazyListScope.voiceSettingsRow(style: String?, onOpen: () -> Unit) {
    groupedSection(
        key = SettingsSection.Voice.key,
        header = VoiceSettingsCopy.Title,
        items = listOf("voice"),
        itemKey = { it },
    ) { id, position ->
        GroupedRow(
            title = VoiceSettingsCopy.Title,
            position = position,
            subtitle = settingsVoiceSubtitle(style),
            icon = ZeronIcons.Waveform,
            testTag = settingsTag(id),
            onClick = onOpen,
        )
    }
}
