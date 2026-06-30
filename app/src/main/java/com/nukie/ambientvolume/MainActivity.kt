/*
 * Ambient Volume - Adaptive Volume Engine
 * Copyright (C) 2026 @nukie-git
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.nukie.ambientvolume

import com.nukie.ambientvolume.BuildConfig

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.NotificationManagerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.nukie.ambientvolume.R
import com.nukie.ambientvolume.service.*
import com.nukie.ambientvolume.ui.*
import com.nukie.ambientvolume.ui.theme.AmbientVolumeTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nukie.ambientvolume.util.DebugLogger
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileInputStream
import java.io.OutputStream
import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import android.app.AlertDialog as AndroidAlertDialog

class MainActivity : ComponentActivity() {
    private val exportLogsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { saveLogsToUri(it) }
    }

    private fun saveLogsToUri(uri: android.net.Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val logFile = DebugLogger.getLogFile(this)
                if (logFile.exists()) {
                    FileInputStream(logFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } else {
                    outputStream.write(getString(R.string.error_no_logs_found).toByteArray())
                }
            }
        } catch (e: Exception) {
            // Error handled by system SAF dialog usually
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun showRestartDialog() {
        AndroidAlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_engine_stopped_title))
            .setMessage(getString(R.string.dialog_engine_stopped_message))
            .setPositiveButton(getString(R.string.button_restart)) { _, _ ->
                val intent = Intent(this, VolumeControlService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            .setNegativeButton(getString(R.string.button_ignore), null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        ProfileManager.init(applicationContext)
        if (BuildConfig.DEBUG) {
            DebugLogger.checkAndPurgeOnUpdate(applicationContext, BuildConfig.VERSION_CODE)
        }

        setContent {
            var useSystemTheme by remember { mutableStateOf(true) }
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        if (!isServiceRunning(VolumeControlService::class.java)) {
                            lifecycleOwner.lifecycleScope.launch {
                                // Self-healing: auto-restart if DataStore confirms it was active
                                if (ProfileManager.getServiceWasActive()) {
                                    val intent = Intent(this@MainActivity, VolumeControlService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(intent)
                                    } else {
                                        startService(intent)
                                    }
                                } else if (AudioStateRepository.isServiceRunning.value) {
                                    // Fallback: in-memory flag says running but DataStore says not — show dialog
                                    showRestartDialog()
                                }
                            }
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            AmbientVolumeTheme(useSystemTheme = useSystemTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionsWrapper {
                        MainNavigation(
                            useSystemTheme = useSystemTheme,
                            onThemeToggle = { useSystemTheme = it },
                            onExportLogs = { exportLogsLauncher.launch("ambient_volume_debug_${System.currentTimeMillis()}.log") }
                        )
                    }
                }
            }
        }
    }
}

enum class ScreenTab(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, @StringRes val labelRes: Int) {
    MONITOR("monitor", Icons.Default.Monitor, R.string.tab_monitor),
    ENGINE("engine", Icons.Default.Tune, R.string.tab_engine),
    SETTINGS("settings", Icons.Default.Settings, R.string.tab_settings)
}

@Composable
fun MainNavigation(
    useSystemTheme: Boolean,
    onThemeToggle: (Boolean) -> Unit,
    onExportLogs: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(ScreenTab.MONITOR) }
    val pendingVolumeDecision by AudioStateRepository.pendingVolumeDecision.collectAsStateWithLifecycle()
    val hearingSafetyEnabled by AudioStateRepository.hearingSafetyEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { ScreenTab.entries.size })

    // Sync selectedTab with pager state
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = ScreenTab.entries[pagerState.currentPage]
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                ScreenTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { 
                            scope.launch { 
                                pagerState.animateScrollToPage(tab.ordinal)
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        Box(modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                val tab = ScreenTab.entries[page]
                when (tab) {
                    ScreenTab.MONITOR -> MonitorScreen()
                    ScreenTab.ENGINE -> EngineScreen()
                    ScreenTab.SETTINGS -> SettingsScreen(useSystemTheme, onThemeToggle, onExportLogs)
                }
            }

            // Startup Protection Dialog (Global)
            pendingVolumeDecision?.let { decision ->
                AlertDialog(
                    onDismissRequest = { AudioStateRepository.requestVolumeDecision(null) },
                    title = { Text(stringResource(R.string.dialog_loud_volume_title)) },
                    text = { Text(stringResource(R.string.dialog_loud_volume_message, (decision.currentVolumePercent * 100).toInt())) },
                    confirmButton = {
                        TextButton(onClick = { AudioStateRepository.requestVolumeDecision(null) }) { Text(stringResource(R.string.button_yes_lower)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            val currentVolPercent = decision.currentVolumePercent
                            val smoothedDb = decision.currentDb
                            val minDb = 50.0
                            val maxDb = 90.0
                            val minVolPercent = 0.30f
                            val maxVolPercent = 1.00f
                            
                            val newOffset = if (currentVolPercent <= minVolPercent) {
                                minDb - smoothedDb
                            } else if (currentVolPercent >= maxVolPercent) {
                                maxDb - smoothedDb
                            } else {
                                val dbRange = maxDb - minDb
                                val volRange = maxVolPercent - minVolPercent
                                ((currentVolPercent - minVolPercent) / volRange) * dbRange - smoothedDb + minDb
                            }
                            
                            scope.launch { ProfileManager.setCustomOffset(newOffset) }
                            AudioStateRepository.requestVolumeDecision(null)
                        }) { Text(stringResource(R.string.button_keep_loud)) }
                    }
                )
            }
            
            // 60/60 Hearing Safety Modal
            val safetyThresholdReached by AudioStateRepository.safetyThresholdReached.collectAsStateWithLifecycle()
            if (safetyThresholdReached && hearingSafetyEnabled) {
                AlertDialog(
                    onDismissRequest = { /* Persistent until acknowledged */ },
                    title = { Text(stringResource(R.string.dialog_hearing_safety_title)) },
                    text = { Text(stringResource(R.string.dialog_hearing_safety_message)) },
                    confirmButton = {
                        Button(onClick = { 
                            // Reset safety timer in service and hide modal
                            val intent = Intent(context, VolumeControlService::class.java).apply {
                                action = "ACTION_RESET_SAFETY"
                            }
                            context.startForegroundService(intent)
                        }) {
                            Text(stringResource(R.string.button_understand))
                        }
                    }
                )
            }
        }
    }
}


@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val currentDb by AudioStateRepository.currentDb.collectAsStateWithLifecycle()
    val rollingMeanDb by AudioStateRepository.rollingMeanDb.collectAsStateWithLifecycle()
    val currentVolumePercent by AudioStateRepository.currentVolume.collectAsStateWithLifecycle()
    val isServiceRunning by AudioStateRepository.isServiceRunning.collectAsStateWithLifecycle()
    val meanInterval by AudioStateRepository.meanInterval.collectAsStateWithLifecycle()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
    var showAppInfoDialog by remember { mutableStateOf(false) }

    var showMicInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Privacy info dialog
        if (showMicInfo) {
            AlertDialog(
                onDismissRequest = { showMicInfo = false },
                title = { Text(stringResource(R.string.dialog_privacy_title)) },
                text = { Text(stringResource(R.string.dialog_privacy_message)) },
                confirmButton = { TextButton(onClick = { showMicInfo = false }) { Text(stringResource(R.string.button_got_it)) } }
            )
        }

        // Header Geometry Structure
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            // 1. The Upper Privacy Info Anchor (Upper Far-Left solo icon)
            IconButton(
                onClick = { showMicInfo = true },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(32.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.desc_privacy_info),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 2. Centered Main Title Row & "About" Trigger
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable { showAppInfoDialog = true }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.Info, 
                    contentDescription = stringResource(R.string.desc_about_engine), 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Noise Level Circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .padding(8.dp)
        ) {
            CircularProgressIndicator(
                progress = { (currentDb / 120f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 14.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentDb.roundToInt().toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = stringResource(R.string.unit_db),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Service Control
        Button(
            onClick = {
                val intent = Intent(context, VolumeControlService::class.java)
                if (isServiceRunning) context.stopService(intent)
                else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                    else context.startService(intent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(if (isServiceRunning) Icons.Default.Close else Icons.Default.PlayArrow, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isServiceRunning) stringResource(R.string.button_stop_service) else stringResource(R.string.button_start_engine))
        }

        // Live Engine Visualizer
        if (BuildConfig.DEBUG) {
            DashboardCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.live_engine_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val maxDbDisplay = 120f
                    val animatedInstant by animateFloatAsState((currentDb / maxDbDisplay).coerceIn(0f, 1f), tween(300), label = "")
                    val animatedMean by animateFloatAsState((rollingMeanDb / maxDbDisplay).coerceIn(0f, 1f), tween(500), label = "")
                    
                    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
                    val primary = MaterialTheme.colorScheme.primary
                    
                    Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                        val barH = size.height / 2f - 2.dp.toPx()
                        val cr = CornerRadius(8.dp.toPx())
                        drawRoundRect(surfaceVariant, size = Size(size.width, barH), cornerRadius = cr)
                        drawRoundRect(primary.copy(0.2f), size = Size(size.width * animatedInstant, barH), cornerRadius = cr)
                        
                        val yOff = barH + 4.dp.toPx()
                        drawRoundRect(surfaceVariant, size = Size(size.width, barH), cornerRadius = cr, topLeft = Offset(0f, yOff))
                        drawRoundRect(primary, size = Size(size.width * animatedMean, barH), cornerRadius = cr, topLeft = Offset(0f, yOff))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${stringResource(R.string.instant_label)}: ${currentDb.roundToInt()} ${stringResource(R.string.unit_db)}", style = MaterialTheme.typography.labelSmall)
                        Text("${stringResource(R.string.mean_label)} (${meanInterval}s): ${rollingMeanDb.roundToInt()} ${stringResource(R.string.unit_db)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        // Volume Status
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.label_current_volume), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = currentVolumePercent,
                    onValueChange = { 
                        val newVol = (it * maxVolume).roundToInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        AudioStateRepository.updateVolume(it)
                    },
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary)
                )
                Text(stringResource(R.string.format_percentage, (currentVolumePercent * 100).roundToInt()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.End))
            }
        }

        // Debug Metric: Ambient Noise dB (Monitor Tab - Conditional Debug Build)
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Debug Metric: Ambient Noise ${currentDb.roundToInt()} ${stringResource(R.string.unit_db)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp), // Safeguard against bottom navigation bar bleed
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = { Text(stringResource(R.string.dialog_about_title)) },
            text = { 
                Column {
                    Text(stringResource(R.string.label_version, BuildConfig.VERSION_NAME), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.label_changelog))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.label_copyright), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.label_credits),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAppInfoDialog = false }) { Text(stringResource(R.string.button_ok)) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineScreen() {
    val scope = rememberCoroutineScope()
    val activeProfile by AudioStateRepository.activeProfile.collectAsStateWithLifecycle()
    val stepSize by AudioStateRepository.stepSize.collectAsStateWithLifecycle()
    val meanInterval by AudioStateRepository.meanInterval.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.label_engine_optimization), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Profiles
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.label_environmental_profile), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VolumeProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = activeProfile == profile,
                            onClick = { 
                                scope.launch { 
                                    ProfileManager.setActiveProfile(profile)
                                    // Instant snap: clear moving average
                                    AudioStateRepository.updateRollingMeanDb(0f)
                                } 
                            },
                            label = { 
                                Text(
                                    stringResource(profile.displayNameRes),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) 
                            }
                        )
                    }
                }
            }
        }

        // Sensitivity
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.sensitivity_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.step_size_title), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                val options = listOf(1, 3, 5, 7, 10, 15)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = stepSize == opt,
                            onClick = { scope.launch { ProfileManager.setStepSize(opt) } },
                            label = {
                                Text(
                                    stringResource(R.string.format_db, opt),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        )
                    }
                }
            }
        }

        // Smoothing
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.smoothing_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.label_averaging_period), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                val options = listOf(3, 5, 7, 10, 15, 20, 30)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = meanInterval == opt,
                            onClick = { scope.launch { ProfileManager.setMeanInterval(opt) } },
                            label = { 
                                Text(
                                    stringResource(R.string.format_seconds, opt),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) 
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(useSystemTheme: Boolean, onThemeToggle: (Boolean) -> Unit, onExportLogs: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vibrateEnabled by AudioStateRepository.vibrateEnabled.collectAsStateWithLifecycle()
    val hearingSafetyEnabled by AudioStateRepository.hearingSafetyEnabled.collectAsStateWithLifecycle()
    val currentDb by AudioStateRepository.currentDb.collectAsStateWithLifecycle()
    val oemType = remember { OEMManager.getDetectedOEM(context) }
    var showPersistenceAssistant by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.label_application_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // General Settings
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                // Theme
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_material_you_theme), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = useSystemTheme, onCheckedChange = onThemeToggle)
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                // Vibrate
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vibrate_title), style = MaterialTheme.typography.bodyLarge)
                        Text(if (vibrateEnabled) stringResource(R.string.status_active) else stringResource(R.string.status_disabled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vibrateEnabled, onCheckedChange = { scope.launch { ProfileManager.setVibrateEnabled(it) } })
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                // Hearing Safety
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_hearing_safety_warning), style = MaterialTheme.typography.bodyLarge)
                        Text(if (hearingSafetyEnabled) stringResource(R.string.status_active_tracking) else stringResource(R.string.status_disabled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = hearingSafetyEnabled, onCheckedChange = { scope.launch { ProfileManager.setHearingSafetyEnabled(it) } })
                }
            }
        }

        // Battery Optimization Card
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.label_battery_background), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.desc_battery_optimization), style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showPersistenceAssistant = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_launch_assistant))
                }
            }
        }

        if (showPersistenceAssistant) {
            PersistenceAssistantDialog(oem = oemType, isDuraSpeed = OEMManager.isDuraSpeedPresent(context), onDismiss = { showPersistenceAssistant = false })
        }

        // Debug Logs Panel (Settings Tab - Conditional Debug Build)
        if (BuildConfig.DEBUG) {
            DashboardCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.label_debug_logs), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onExportLogs,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Share, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.button_export_logs))
                        }
                        Button(
                            onClick = { DebugLogger.clearLogs(context) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.button_clear))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp) // Adjusted padding for density
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = Color.Black.copy(alpha = 0.05f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersistenceAssistantDialog(oem: OEMType?, isDuraSpeed: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    
    // Live checking of statuses
    var isBatteryIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var isNotificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var isAutostartVerified by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while(true) {
            isBatteryIgnored = isIgnoringBatteryOptimizations(context)
            isNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            delay(1500)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.title_persistence_assistant),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.subtitle_persistence_assistant),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Help Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.desc_persistence_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 1. Battery Optimization
            ChecklistItem(
                title = stringResource(R.string.item_battery_optimization),
                description = if (isBatteryIgnored) stringResource(R.string.desc_battery_ignored) else stringResource(R.string.desc_battery_not_ignored),
                isDone = isBatteryIgnored,
                onClick = {
                    if (!isBatteryIgnored) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            )

            // 2. OEM Autostart / Background Management
            if (oem != null) {
                val oemTitle = if (oem == OEMType.XIAOMI) stringResource(R.string.format_xiaomi_autostart_title, OEMManager.getXiaomiRomName()) else stringResource(oem.titleRes)
                val oemDesc = if (isAutostartVerified) stringResource(R.string.status_verified) else if (oem == OEMType.XIAOMI) stringResource(R.string.format_autostart_desc, OEMManager.getXiaomiRomName()) else stringResource(oem.descRes)
                
                ChecklistItem(
                    title = oemTitle,
                    description = oemDesc,
                    isDone = isAutostartVerified,
                    onClick = {
                        isAutostartVerified = true
                        try {
                            val i = oem.intent
                            i.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            context.startActivity(i)
                        } catch (e: Exception) {
                            // Fallback to app details
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context.startActivity(intent)
                        }
                    }
                )
            }

            // 3. DuraSpeed (Mediatek/Doogee)
            if (isDuraSpeed) {
                ChecklistItem(
                    title = stringResource(R.string.item_duraspeed),
                    description = stringResource(R.string.desc_duraspeed),
                    isDone = false,
                    onClick = {
                        try {
                            val intent = Intent().apply {
                                component = ComponentName("com.mediatek.duraspeed", "com.mediatek.duraspeed.MainActivity")
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Manual instruction fallback
                        }
                    }
                )
            }

            // 4. Notification Status
            ChecklistItem(
                title = stringResource(R.string.item_notifications),
                description = if (isNotificationsEnabled) stringResource(R.string.desc_notifications_enabled) else stringResource(R.string.desc_notifications_disabled),
                isDone = isNotificationsEnabled,
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.button_setup_completed))
            }
        }
    }
}

@Composable
fun ChecklistItem(title: String, description: String, isDone: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isDone) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
