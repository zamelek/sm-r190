@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.pk.budspro.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.pk.budspro.Buds
import dev.pk.budspro.GuardService
import dev.pk.budspro.Link
import dev.pk.budspro.protocol.BudsState
import dev.pk.budspro.protocol.NoiseMode
import dev.pk.budspro.protocol.Placement
import dev.pk.budspro.protocol.TouchOption

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Buds.hasPermission()) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        Buds.wake()
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (!granted) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
    LifecycleStartEffect(Unit) {
        granted = Buds.hasPermission()
        Buds.acquire(Buds.HOLD_UI)
        onStopOrDispose { Buds.release(Buds.HOLD_UI) }
    }

    val link by Buds.link.collectAsState()
    val s by Buds.state.collectAsState()
    val lock by Buds.prefs.lockTouch.collectAsState()
    val guard by Buds.prefs.guard.collectAsState()
    val failures by Buds.failures.collectAsState()
    val connected = link == Link.CONNECTED && s.hasStatus

    Scaffold(topBar = { TopAppBar(title = { Text("Galaxy Buds Pro") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!granted) {
                Section("Permission required") {
                    Text("Allow access to Nearby devices so the app can talk to the earbuds.")
                    Button(onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                        Text("Allow")
                    }
                }
            }

            ConnectionSection(link, s, failures, granted)

            LockSection(
                lock = lock, guard = guard, s = s, connected = connected,
                onLock = { Buds.setLockTouch(it) },
                onGuard = { on ->
                    Buds.prefs.setGuard(on)
                    if (on) {
                        if (!hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        GuardService.start(context)
                    } else {
                        GuardService.stop(context)
                    }
                },
            )

            if (connected) {
                BatterySection(s)
                NoiseSection(s)
                SoundSection(s)
                TouchSection(s, lock)
                FindSection(s)
                s.build?.let { build ->
                    Text(
                        "Firmware build $build · status revision ${s.extRevision}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun hasPermission(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Section(title: String, highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = if (highlight) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ConnectionSection(link: Link, s: BudsState, failures: Int, granted: Boolean) {
    val devices = remember(granted, link) { Buds.bondedDevices() }
    val address by Buds.prefs.address.collectAsState()
    val current = devices.firstOrNull { it.address == address }
    var menu by remember { mutableStateOf(false) }

    Section("Connection") {
        val status = when (link) {
            Link.NO_PERMISSION -> "No Bluetooth permission"
            Link.BT_OFF -> "Bluetooth is off"
            Link.NO_DEVICE -> "Earbuds not found among paired devices"
            Link.CONNECTING -> "Connecting…"
            Link.CONNECTED -> if (s.hasStatus) "Connected" else "Reading status…"
            Link.DISCONNECTED -> "Not connected"
        }
        Text(status, color = if (link == Link.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (devices.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(current?.let { Buds.deviceName(it) } ?: "Choose device")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    devices.forEach { d ->
                        DropdownMenuItem(
                            text = { Text("${Buds.deviceName(d)}  (${d.address})") },
                            onClick = { menu = false; Buds.selectDevice(d.address) },
                        )
                    }
                }
            }
        }
        if (link != Link.CONNECTED && failures >= 2) {
            Text(
                "Make sure the earbuds are connected to this phone and not in a closed case. " +
                    "If Galaxy Wearable runs in another profile, stop it: it may hold the earbuds' control channel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LockSection(
    lock: Boolean, guard: Boolean, s: BudsState, connected: Boolean,
    onLock: (Boolean) -> Unit, onGuard: (Boolean) -> Unit,
) {
    Section("Touchpad", highlight = true) {
        SwitchRow(
            "Lock touch controls",
            checked = lock,
            subtitle = "Taps and holds will no longer control playback. The firmware always keeps the touch beep.",
            onChange = onLock,
        )
        if (connected && s.touchLocked != null && s.touchLocked != lock) {
            Text(
                if (s.touchLocked) "Earbuds currently report: locked" else "Earbuds currently report: unlocked",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
        SwitchRow(
            "Background guard",
            checked = guard,
            subtitle = "Keeps a link to the earbuds and restores the lock right after the case or a reconnect. " +
                "Without it the lock is re-sent on every Bluetooth connection.",
            onChange = onGuard,
        )
    }
}

@Composable
private fun BatterySection(s: BudsState) {
    Section("Battery") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BatteryItem("Left", s.batteryL, placementText(s.placementL))
            BatteryItem("Right", s.batteryR, placementText(s.placementR))
            BatteryItem("Case", s.batteryCase, null)
        }
    }
}

@Composable
private fun BatteryItem(title: String, level: Int?, note: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(level?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineSmall)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun placementText(p: Placement) = when (p) {
    Placement.WEARING -> "in ear"
    Placement.IDLE -> "out of ear"
    Placement.IN_CASE -> "in case"
    Placement.DISCONNECTED -> "disconnected"
    Placement.UNKNOWN -> "—"
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun NoiseSection(s: BudsState) {
    Section("Noise control") {
        Segmented(
            listOf(NoiseMode.OFF to "Off", NoiseMode.ANC to "ANC", NoiseMode.AMBIENT to "Ambient"),
            s.noiseMode, Buds::setNoiseMode,
        )
        when (s.noiseMode) {
            NoiseMode.ANC -> {
                Text("Noise cancelling level", style = MaterialTheme.typography.bodyMedium)
                Segmented(listOf(1 to "High", 0 to "Low"), s.ancLevel, Buds::setAncLevel)
            }
            NoiseMode.AMBIENT -> {
                var value by remember(s.ambientVolume) { mutableFloatStateOf(s.ambientVolume.toFloat()) }
                Text("Ambient sound level: ${value.toInt() + 1} of 4", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = value, onValueChange = { value = it }, valueRange = 0f..3f, steps = 2,
                    onValueChangeFinished = { Buds.setAmbientVolume(value.toInt()) },
                )
            }
        }
        HorizontalDivider()
        SwitchRow(
            "Voice detect",
            checked = s.detectConversations,
            subtitle = "When you speak, ambient sound turns on and media volume drops",
            onChange = Buds::setDetectConversations,
        )
        if (s.detectConversations) {
            Text("Return to previous mode after", style = MaterialTheme.typography.bodyMedium)
            Segmented(listOf(0 to "5 s", 1 to "10 s", 2 to "15 s"), s.detectConversationsDuration, Buds::setDetectConversationsDuration)
        }
    }
}

private val EQ_PRESETS = listOf(
    0 to "Normal", 1 to "Bass boost", 2 to "Soft", 3 to "Dynamic", 4 to "Clear", 5 to "Treble boost",
)

@Composable
private fun SoundSection(s: BudsState) {
    Section("Sound") {
        Text("Equalizer", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EQ_PRESETS.forEach { (id, name) ->
                FilterChip(selected = s.equalizer == id, onClick = { Buds.setEqualizer(id) }, label = { Text(name) })
            }
        }
        HorizontalDivider()
        SwitchRow(
            "Game mode",
            checked = s.gamingMode,
            subtitle = "Lowest audio latency",
            onChange = Buds::setGamingMode,
        )
    }
}

private val TOUCH_OPTIONS = listOf(
    TouchOption.NOISE_CONTROL to "Noise control",
    TouchOption.VOICE_ASSISTANT to "Voice assistant",
    TouchOption.VOLUME to "Volume",
    TouchOption.SPOTIFY to "Spotify",
)

@Composable
private fun TouchSection(s: BudsState, lock: Boolean) {
    Section("Touch controls") {
        if (lock) {
            Text(
                "Touch controls are locked now. These settings apply once you unlock them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Touch and hold", style = MaterialTheme.typography.bodyMedium)
        TouchOptionPicker("Left", s.touchOptionL) { Buds.setTouchOptions(it, s.touchOptionR) }
        TouchOptionPicker("Right", s.touchOptionR) { Buds.setTouchOptions(s.touchOptionL, it) }
        HorizontalDivider()
        SwitchRow(
            "Double tap earbud edge",
            checked = s.outsideDoubleTap,
            subtitle = "A double knock near the earbud edge, detected by vibration. Best kept off: it can trigger when you adjust an earbud",
            onChange = Buds::setOutsideDoubleTap,
        )
    }
}

@Composable
private fun TouchOptionPicker(side: String, value: Int, onSelect: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(side, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(TOUCH_OPTIONS.firstOrNull { it.first == value }?.second ?: "Other ($value)")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TOUCH_OPTIONS.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; onSelect(id) })
                }
            }
        }
    }
}

@Composable
private fun FindSection(s: BudsState) {
    Section("Find my earbuds") {
        Text(
            "The earbuds will beep loudly. Take them out of your ears first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = { Buds.findEarbuds(!s.findingEarbuds) }) {
            Text(if (s.findingEarbuds) "Stop" else "Find earbuds")
        }
    }
}
