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
import com.nukie.ambientvolume.R
import com.nukie.ambientvolume.service.*
import com.nukie.ambientvolume.ui.*
import com.nukie.ambientvolume.ui.theme.AmbientVolumeTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.app.AlertDialog as AndroidAlertDialog

class MainActivity : ComponentActivity() {
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
            .setTitle("Engine Stopped")
            .setMessage("The Volume Engine was killed by the system. Would you like to restart it?")
            .setPositiveButton("Restart") { _, _ ->
                val intent = Intent(this, VolumeControlService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            .setNegativeButton("Ignore", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        ProfileManager.init(applicationContext)

        setContent {
            var useSystemTheme by remember { mutableStateOf(true) }
            val lifecycleOwner = LocalLifecycleOwner.current

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        if (!isServiceRunning(VolumeControlService::class.java)) {
                            // Only show if it was actually supposed to be running
                            // But wait, AudioStateRepository.isServiceRunning might be true if killed
                            if (AudioStateRepository.isServiceRunning.value) {
                                showRestartDialog()
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
                            onThemeToggle = { useSystemTheme = it }
                        )
                    }
                }
            }
        }
    }
}

enum class ScreenTab(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String) {
    MONITOR("monitor", Icons.Default.Monitor, "Monitor"),
    ENGINE("engine", Icons.Default.Tune, "Engine"),
    SETTINGS("settings", Icons.Default.Settings, "Settings")
}

@Composable
fun MainNavigation(
    useSystemTheme: Boolean,
    onThemeToggle: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(ScreenTab.MONITOR) }
    val pendingVolumeDecision by AudioStateRepository.pendingVolumeDecision.collectAsState()
    val scope = rememberCoroutineScope()

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
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
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
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                },
                label = "screen_transition"
            ) { tab ->
                when (tab) {
                    ScreenTab.MONITOR -> MonitorScreen()
                    ScreenTab.ENGINE -> EngineScreen()
                    ScreenTab.SETTINGS -> SettingsScreen(useSystemTheme, onThemeToggle)
                }
            }

            // Startup Protection Dialog (Global)
            pendingVolumeDecision?.let { decision ->
                AlertDialog(
                    onDismissRequest = { AudioStateRepository.requestVolumeDecision(null) },
                    title = { Text("Loud Volume Detected") },
                    text = { Text("Your volume is currently at ${(decision.currentVolumePercent * 100).toInt()}%, but your environment is quiet. Lower it automatically?") },
                    confirmButton = {
                        TextButton(onClick = { AudioStateRepository.requestVolumeDecision(null) }) { Text("Yes, Lower It") }
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
                        }) { Text("Keep it loud") }
                    }
                )
            }
        }
    }
}


@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val currentDb by AudioStateRepository.currentDb.collectAsState()
    val rollingMeanDb by AudioStateRepository.rollingMeanDb.collectAsState()
    val currentVolumePercent by AudioStateRepository.currentVolume.collectAsState()
    val isServiceRunning by AudioStateRepository.isServiceRunning.collectAsState()
    val meanInterval by AudioStateRepository.meanInterval.collectAsState()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
    var showAppInfoDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAppInfoDialog = true },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }

        // Noise Level Circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .padding(8.dp)
        ) {
            CircularProgressIndicator(
                progress = { currentDb / 120f },
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
                    text = "dB",
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
            Text(if (isServiceRunning) "Stop Service" else "Start Engine")
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
                        Text("${stringResource(R.string.instant_label)}: ${currentDb.roundToInt()} dB", style = MaterialTheme.typography.labelSmall)
                        Text("${stringResource(R.string.mean_label)} (${meanInterval}s): ${rollingMeanDb.roundToInt()} dB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Volume Status
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Current Volume", style = MaterialTheme.typography.titleSmall)
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
                Text("${(currentVolumePercent * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.End))
            }
        }

        // Debug Card (Conditional)
        if (BuildConfig.DEBUG) {
            DashboardCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Debug Diagnostics", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text("Raw: ${String.format("%.1f", currentDb)} dB", style = MaterialTheme.typography.labelSmall)
                    Text("Mean: ${String.format("%.1f", rollingMeanDb)} dB", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = { Text("About Ambient Volume") },
            text = { Text("Adaptive Volume Engine v1.3.0\n\nAutomates media volume based on room noise.\n\n© 2026 @nukie-git") },
            confirmButton = { TextButton(onClick = { showAppInfoDialog = false }) { Text("OK") } }
        )
    }
}

@Composable
fun EngineScreen() {
    val scope = rememberCoroutineScope()
    val activeProfile by AudioStateRepository.activeProfile.collectAsState()
    val stepSize by AudioStateRepository.stepSize.collectAsState()
    val meanInterval by AudioStateRepository.meanInterval.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Engine Optimization", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Profiles
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Environmental Profile", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VolumeProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = activeProfile == profile,
                            onClick = { scope.launch { ProfileManager.setActiveProfile(profile) } },
                            label = { Text(profile.displayName) }
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
                val options = listOf(1, 2, 3, 5, 7, 10)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(selected = stepSize == opt, onClick = { scope.launch { ProfileManager.setStepSize(opt) } }, label = { Text("${opt} dB") })
                    }
                }
            }
        }

        // Smoothing
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.smoothing_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Averaging Period", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                val options = listOf(3, 5, 7, 10, 15)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        FilterChip(selected = meanInterval == opt, onClick = { scope.launch { ProfileManager.setMeanInterval(opt) } }, label = { Text("${opt}s") })
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(useSystemTheme: Boolean, onThemeToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vibrateEnabled by AudioStateRepository.vibrateEnabled.collectAsState()
    val oemType = remember { OEMManager.getDetectedOEM(context) }
    var showPersistenceAssistant by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Application Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // General Settings
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                // Theme
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Material You Theme", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = useSystemTheme, onCheckedChange = onThemeToggle)
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                // Vibrate
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vibrate_title), style = MaterialTheme.typography.bodyLarge)
                        Text(if (vibrateEnabled) "Active" else "Disabled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vibrateEnabled, onCheckedChange = { scope.launch { ProfileManager.setVibrateEnabled(it) } })
                }
            }
        }

        // Battery Optimization Card
        DashboardCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Battery & Background", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Aggressive power saving on Xiaomi devices can stop the volume engine. Use the assistant to fix this.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showPersistenceAssistant = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Launch Persistence Assistant")
                }
            }
        }

        if (showPersistenceAssistant) {
            PersistenceAssistantDialog(oem = oemType, isDuraSpeed = OEMManager.isDuraSpeedPresent(context), onDismiss = { showPersistenceAssistant = false })
        }
    }
}

@Composable
fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
                text = "Persistence Assistant",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Ensure the Volume Engine stays alive",
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
                        text = "Aggressive power saving on some devices can kill background apps. These steps keep the engine running while your screen is off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 1. Battery Optimization
            ChecklistItem(
                title = "Battery Optimization",
                description = if (isBatteryIgnored) "Already set to 'No Restrictions'" else "Set to 'No Restrictions'",
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
                val oemTitle = if (oem == OEMType.XIAOMI) "${OEMManager.getXiaomiRomName()} Autostart" else oem.title
                val oemDesc = if (isAutostartVerified) "Status Verified" else if (oem == OEMType.XIAOMI) "Allow app to start in background on ${OEMManager.getXiaomiRomName()}" else oem.description
                
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
                    title = "DuraSpeed Management",
                    description = "Ensure Ambient Volume is toggled ON in DuraSpeed settings",
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
                title = "Notification Status",
                description = if (isNotificationsEnabled) "Service alerts are active" else "Ensure service alerts are visible",
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
                Text("I've completed the setup")
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
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


