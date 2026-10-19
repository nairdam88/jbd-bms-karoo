package com.jbdbms.karoo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jbdbms.karoo.BuildConfig
import com.jbdbms.karoo.data.BmsPreset
import com.jbdbms.karoo.data.BmsRepository
import com.jbdbms.karoo.data.Direction
import com.jbdbms.karoo.data.SettingsStore
import com.jbdbms.karoo.protocol.JbdCodec

/**
 * Friendly settings: three update presets for normal people, with the raw knobs
 * tucked away under Advanced. Protocol logging lives behind Developer options.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val settings by BmsRepository.settings.collectAsStateWithLifecycle()
    val consumers by BmsRepository.consumers.collectAsStateWithLifecycle()
    val rawFrames by BmsRepository.rawFrames.collectAsStateWithLifecycle()

    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var developerMode by rememberSaveable { mutableStateOf(false) }

    val preset = BmsPreset.of(settings)

    LaunchedEffect(showLog) { BmsRepository.setRawEnabled(showLog) }
    DisposableEffect(Unit) {
        onDispose { BmsRepository.setRawEnabled(false) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Text(
            "Choose how often your battery is read. Less frequent updates use less battery.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        GroupTitle("Updates")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip("Battery saver", BmsPreset.BATTERY_SAVER, preset) {
                SettingsStore.update { BmsPreset.BATTERY_SAVER.applyTo(it) }
            }
            PresetChip("Balanced", BmsPreset.BALANCED, preset) {
                SettingsStore.update { BmsPreset.BALANCED.applyTo(it) }
            }
            PresetChip("Fast", BmsPreset.FAST, preset) {
                SettingsStore.update { BmsPreset.FAST.applyTo(it) }
            }
        }
        Text(
            presetExplanation(preset),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        ToggleRow("Reconnect automatically", settings.autoReconnect) { value ->
            SettingsStore.update { it.copy(autoReconnect = value) }
        }

        GroupTitle("Advanced")
        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Text(if (advancedExpanded) "Hide advanced options" else "Show advanced options")
        }
        if (advancedExpanded) {
            ChoiceRow(
                label = "Update rate while using the app",
                options = listOf(500L, 1_000L, 2_000L),
                selected = settings.pollIntervalVisibleMs,
            ) { value -> SettingsStore.update { it.copy(pollIntervalVisibleMs = value) } }
            ChoiceRow(
                label = "Update rate in the background",
                options = listOf(1_000L, 2_000L, 5_000L),
                selected = settings.pollIntervalBackgroundMs,
            ) { value -> SettingsStore.update { it.copy(pollIntervalBackgroundMs = value) } }
            ToggleRow("Pause updates when idle", settings.pauseWhenIdle) { value ->
                SettingsStore.update { it.copy(pauseWhenIdle = value) }
            }
            ToggleRow("Disconnect after 5 minutes idle", settings.disconnectWhenIdle) { value ->
                SettingsStore.update { it.copy(disconnectWhenIdle = value) }
            }
            ToggleRow("Low-power Bluetooth scanning", settings.lowPowerScan) { value ->
                SettingsStore.update { it.copy(lowPowerScan = value) }
            }
            ToggleRow("Low-power Bluetooth connection", settings.lowPowerConnection) { value ->
                SettingsStore.update { it.copy(lowPowerConnection = value) }
            }
        }

        GroupTitle("About")
        Text(
            "Works with JBD, XiaoXiang and Overkill Solar BMS modules.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { SettingsStore.resetToDefaults() }) {
            Text("Reset to defaults")
        }
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 10.dp)
                .clickable {
                    if (!developerMode) {
                        versionTaps++
                        if (versionTaps >= 7) developerMode = true
                    }
                },
        )

        if (developerMode) {
            GroupTitle("Developer options")
            Text(
                "For troubleshooting only. These controls are not needed for normal use.",
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRow("Record protocol log", showLog) { showLog = it }
            Text(
                "Active data streams: $consumers",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (showLog) {
                Text(
                    "Showing the last ${rawFrames.takeLast(30).size} of up to ${settings.rawBufferSize} frames.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                rawFrames.takeLast(30).forEach { frame ->
                    val arrow = if (frame.direction == Direction.TX) "→" else "←"
                    Text(
                        "$arrow ${JbdCodec.toHex(frame.bytes)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                TextButton(onClick = { BmsRepository.clearRaw() }) { Text("Clear log") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetChip(
    label: String,
    value: BmsPreset,
    selected: BmsPreset,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = onClick,
        label = { Text(label) },
    )
}

private fun presetExplanation(preset: BmsPreset): String = when (preset) {
    BmsPreset.BATTERY_SAVER -> "Updates every few seconds. Best for long rides and battery life."
    BmsPreset.BALANCED -> "Updates about once a second. A good balance for most rides."
    BmsPreset.FAST -> "Updates twice a second. Uses the most battery."
    BmsPreset.CUSTOM -> "Custom advanced settings are active."
}

@Composable
private fun GroupTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(formatMs(option)) },
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String =
    if (ms % 1000 == 0L) "${ms / 1000}s" else "${ms}ms"
