package com.beansys.breathing

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.beansys.breathing.shared.BreathingDefaults
import com.beansys.breathing.shared.Preset
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "breathing")

private object SettingKeys {
    val inhaleTenths = intPreferencesKey("inhale_tenths")
    val holdTenths = intPreferencesKey("hold_tenths")
    val exhaleTenths = intPreferencesKey("exhale_tenths")
    val restTenths = intPreferencesKey("rest_tenths")
    val countdownTenths = intPreferencesKey("countdown_tenths")
    val soundEnabled = booleanPreferencesKey("sound_enabled")
}

private data class BreathingSettings(
    val inhale: Double,
    val hold: Double,
    val exhale: Double,
    val rest: Double,
    val countdownMinutes: Double,
    val soundEnabled: Boolean
)

private fun defaultSettings(): BreathingSettings = BreathingSettings(
    inhale = BreathingDefaults.inhaleSeconds,
    hold = BreathingDefaults.holdSeconds,
    exhale = BreathingDefaults.exhaleSeconds,
    rest = BreathingDefaults.restSeconds,
    countdownMinutes = BreathingDefaults.countdownMinutes,
    soundEnabled = true
)

private fun Preferences.toSettings(): BreathingSettings {
    fun tenthsToSeconds(value: Int?, fallback: Double): Double =
        (value ?: (fallback * 10).toInt()) / 10.0

    return BreathingSettings(
        inhale = tenthsToSeconds(this[SettingKeys.inhaleTenths], BreathingDefaults.inhaleSeconds),
        hold = tenthsToSeconds(this[SettingKeys.holdTenths], BreathingDefaults.holdSeconds),
        exhale = tenthsToSeconds(this[SettingKeys.exhaleTenths], BreathingDefaults.exhaleSeconds),
        rest = tenthsToSeconds(this[SettingKeys.restTenths], BreathingDefaults.restSeconds),
        countdownMinutes = tenthsToSeconds(this[SettingKeys.countdownTenths], BreathingDefaults.countdownMinutes),
        soundEnabled = this[SettingKeys.soundEnabled] ?: true
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BreathingScreen()
            }
        }
    }
}

@Composable
private fun BreathingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = context.dataStore
    val settingsFlow = remember {
        dataStore.data.map { prefs -> prefs.toSettings() }
    }
    val settings by settingsFlow.collectAsState(initial = defaultSettings())

    var isRunning by rememberSaveable { mutableStateOf(false) }
    var phaseIndex by rememberSaveable { mutableIntStateOf(0) }
    var phaseElapsed by rememberSaveable { mutableDoubleStateOf(0.0) }
    var remainingCountdown by rememberSaveable { mutableDoubleStateOf(settings.countdownMinutes * 60.0) }
    var didCompleteCountdown by rememberSaveable { mutableStateOf(false) }
    var shouldFinishCycle by rememberSaveable { mutableStateOf(false) }

    val phases = remember(settings.inhale, settings.hold, settings.exhale, settings.rest) {
        BreathingDefaults.phases(settings.inhale, settings.hold, settings.exhale, settings.rest)
    }
    val currentPhase = phases[phaseIndex]
    val phaseProgress = min(
        phaseElapsed / max(currentPhase.durationSeconds, 0.01),
        1.0
    )
    val remainingPhase = max(currentPhase.durationSeconds - phaseElapsed, 0.0)
    val breathsPerMinute = BreathingDefaults.breathsPerMinute(phases)
    val countdownActive = settings.countdownMinutes > 0.01
    val countdownDisplay = formatCountdown(remainingCountdown)

    val soundPlayer = remember { SoundPlayer() }

    DisposableEffect(isRunning) {
        val activity = context as? Activity
        if (isRunning) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            soundPlayer.release()
        }
    }

    LaunchedEffect(settings.inhale, settings.hold, settings.exhale, settings.rest, settings.countdownMinutes) {
        phaseIndex = 0
        phaseElapsed = 0.0
        remainingCountdown = settings.countdownMinutes * 60.0
        didCompleteCountdown = false
        shouldFinishCycle = false
        if (isRunning) {
            isRunning = false
        }
    }

    LaunchedEffect(settings.soundEnabled) {
        if (!settings.soundEnabled) {
            soundPlayer.stop()
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            soundPlayer.stop()
        } else if (settings.soundEnabled) {
            playSoundForPhase(soundPlayer, currentPhase.name, currentPhase.durationSeconds)
        }
    }

    LaunchedEffect(isRunning, phaseIndex, settings.inhale, settings.hold, settings.exhale, settings.rest, settings.countdownMinutes) {
        if (!isRunning) return@LaunchedEffect
        var lastTick = SystemClock.elapsedRealtime()
        while (isRunning) {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTick) / 1000.0
            lastTick = now

            if (countdownActive && !didCompleteCountdown) {
                remainingCountdown -= delta
                if (remainingCountdown <= 0) {
                    remainingCountdown = 0.0
                    shouldFinishCycle = true
                }
            }

            if (currentPhase.durationSeconds <= 0.01) {
                advancePhase(
                    phases = phases,
                    currentIndex = phaseIndex,
                    shouldFinishCycle = shouldFinishCycle,
                    onAdvance = { nextIndex, finished ->
                        if (finished) {
                            isRunning = false
                            shouldFinishCycle = false
                            didCompleteCountdown = true
                            soundPlayer.stop()
                            if (settings.soundEnabled) {
                                soundPlayer.playSuccess()
                            }
                        } else {
                            phaseIndex = nextIndex
                            phaseElapsed = 0.0
                            if (settings.soundEnabled) {
                                playSoundForPhase(soundPlayer, phases[nextIndex].name, phases[nextIndex].durationSeconds)
                            }
                        }
                    }
                )
                delay(50)
                continue
            }

            phaseElapsed += delta
            if (phaseElapsed >= currentPhase.durationSeconds) {
                advancePhase(
                    phases = phases,
                    currentIndex = phaseIndex,
                    shouldFinishCycle = shouldFinishCycle,
                    onAdvance = { nextIndex, finished ->
                        if (finished) {
                            isRunning = false
                            shouldFinishCycle = false
                            didCompleteCountdown = true
                            soundPlayer.stop()
                            if (settings.soundEnabled) {
                                soundPlayer.playSuccess()
                            }
                        } else {
                            phaseIndex = nextIndex
                            phaseElapsed = 0.0
                            if (settings.soundEnabled) {
                                playSoundForPhase(soundPlayer, phases[nextIndex].name, phases[nextIndex].durationSeconds)
                            }
                        }
                    }
                )
            }
            delay(50)
        }
    }

    val background = Brush.linearGradient(
        colors = listOf(Color(0xFF19242F), Color(0xFF0D4747)),
        start = Offset.Zero,
        end = Offset.Infinite
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.alpha(if (isRunning) 0f else 1f)
            ) {
                Text(
                    text = "Paced Breathing",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Slow the tempo and follow the ring",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            BreathingRing(
                phaseName = currentPhase.name,
                remainingSeconds = remainingPhase,
                progress = phaseProgress.toFloat(),
                phaseColor = Color(currentPhase.colorHex)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = String.format(Locale.getDefault(), "%.1f breaths/min", breathsPerMinute),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.alpha(if (isRunning) 0f else 1f)
                )

                if (countdownActive) {
                    Text(
                        text = if (isRunning) "Session $countdownDisplay" else "Session $countdownDisplay remaining",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            if (isRunning) {
                                isRunning = false
                                soundPlayer.stop()
                            } else {
                                if (remainingCountdown <= 0) {
                                    remainingCountdown = settings.countdownMinutes * 60.0
                                }
                                didCompleteCountdown = false
                                shouldFinishCycle = false
                                isRunning = true
                                if (settings.soundEnabled) {
                                    playSoundForPhase(soundPlayer, currentPhase.name, currentPhase.durationSeconds)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(BreathingDefaults.actionTintHex))
                    ) {
                        Text(text = if (isRunning) "Pause" else "Start", color = Color.White)
                    }

                    Button(
                        onClick = {
                            phaseIndex = 0
                            phaseElapsed = 0.0
                            remainingCountdown = settings.countdownMinutes * 60.0
                            shouldFinishCycle = false
                            didCompleteCountdown = false
                            soundPlayer.stop()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        enabled = !isRunning,
                        modifier = Modifier.alpha(if (isRunning) 0f else 1f)
                    ) {
                        Text(text = "Reset", color = Color.White)
                    }
                }

                TextButton(
                    onClick = {
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.inhaleTenths] = (BreathingDefaults.inhaleSeconds * 10).toInt()
                                prefs[SettingKeys.holdTenths] = (BreathingDefaults.holdSeconds * 10).toInt()
                                prefs[SettingKeys.exhaleTenths] = (BreathingDefaults.exhaleSeconds * 10).toInt()
                                prefs[SettingKeys.restTenths] = (BreathingDefaults.restSeconds * 10).toInt()
                                prefs[SettingKeys.countdownTenths] = (BreathingDefaults.countdownMinutes * 10).toInt()
                            }
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier.alpha(if (isRunning) 0f else 1f)
                ) {
                    Text(text = "Reset to Defaults", color = Color.White.copy(alpha = 0.85f))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(if (isRunning) 0f else 1f)
                ) {
                    Text("Sound", color = Color.White.copy(alpha = 0.85f))
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = settings.soundEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                dataStore.edit { prefs ->
                                    prefs[SettingKeys.soundEnabled] = enabled
                                }
                            }
                        }
                    )
                }
            }

            if (!isRunning) {
                PresetBar(
                    presets = BreathingDefaults.presets,
                    onPreset = { preset ->
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.inhaleTenths] = (preset.inhale * 10).toInt()
                                prefs[SettingKeys.holdTenths] = (preset.hold * 10).toInt()
                                prefs[SettingKeys.exhaleTenths] = (preset.exhale * 10).toInt()
                                prefs[SettingKeys.restTenths] = (preset.rest * 10).toInt()
                            }
                        }
                    }
                )
            }

            if (!isRunning) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(20.dp)
                ) {
                DurationRow(
                    title = "Inhale",
                    value = settings.inhale,
                    onValueChange = { newValue ->
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.inhaleTenths] = (newValue * 10).toInt()
                            }
                        }
                    }
                )
                DurationRow(
                    title = "Hold",
                    value = settings.hold,
                    onValueChange = { newValue ->
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.holdTenths] = (newValue * 10).toInt()
                            }
                        }
                    }
                )
                DurationRow(
                    title = "Exhale",
                    value = settings.exhale,
                    onValueChange = { newValue ->
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.exhaleTenths] = (newValue * 10).toInt()
                            }
                        }
                    }
                )
                DurationRow(
                    title = "Rest",
                    value = settings.rest,
                    onValueChange = { newValue ->
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.restTenths] = (newValue * 10).toInt()
                            }
                        }
                    }
                )
                DurationRow(
                    title = "Session (min)",
                    value = settings.countdownMinutes,
                    range = 0.0..15.0,
                    unit = "min",
                    onValueChange = { newValue ->
                        scope.launch {
                            dataStore.edit { prefs ->
                                prefs[SettingKeys.countdownTenths] = (newValue * 10).toInt()
                            }
                        }
                    }
                )
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetBar(
    presets: List<Preset>,
    onPreset: (Preset) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Button(onClick = { showSheet = true }) {
                Text(text = "Breathing Presets", color = Color.White)
                PaddingValues(16.dp)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF182530)
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(
                    text = "Breathing Presets",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                presets.forEach { preset ->
                    TextButton(
                        onClick = {
                            showSheet = false
                            onPreset(preset)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = preset.name, color = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BreathingRing(
    phaseName: String,
    remainingSeconds: Double,
    progress: Float,
    phaseColor: Color
) {
    val ringSize = 220.dp
    val strokeWidth = 18.dp

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(ringSize)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                style = Stroke(width = strokeWidth.toPx())
            )
            drawArc(
                color = phaseColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = phaseName, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                text = "${remainingSeconds.roundToInt()}s",
                fontSize = 18.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun DurationRow(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Double> = 0.0..10.0,
    unit: String = "s",
    onValueChange: (Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.weight(1f))
            Text(String.format(Locale.getDefault(), "%.1f%s", value, unit), color = Color.White.copy(alpha = 0.7f))
        }
        val steps = ((range.endInclusive - range.start) / 0.5).toInt() - 1
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(roundToHalf(it.toDouble())) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = max(0, steps)
        )
    }
}

private fun roundToHalf(value: Double): Double = round(value * 2.0) / 2.0

private fun formatCountdown(seconds: Double): String {
    val totalSeconds = max(seconds, 0.0).toInt()
    val minutes = totalSeconds / 60
    val remaining = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, remaining)
}

private fun advancePhase(
    phases: List<com.beansys.breathing.shared.Phase>,
    currentIndex: Int,
    shouldFinishCycle: Boolean,
    onAdvance: (nextIndex: Int, finished: Boolean) -> Unit
) {
    val nextIndex = BreathingDefaults.nextPhaseIndex(currentIndex, phases)
    if (shouldFinishCycle && nextIndex == 0) {
        onAdvance(nextIndex, true)
    } else {
        onAdvance(nextIndex, false)
    }
}

private fun playSoundForPhase(soundPlayer: SoundPlayer, phaseName: String, duration: Double) {
    when (phaseName) {
        "Inhale" -> soundPlayer.playInhale(duration)
        "Exhale" -> soundPlayer.playExhale(duration)
    }
}
