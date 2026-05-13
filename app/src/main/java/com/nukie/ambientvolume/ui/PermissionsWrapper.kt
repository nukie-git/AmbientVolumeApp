/*
 * Ambient Volume - Adaptive Volume Engine
 * Copyright (C) 2026 @nukie-git
 */

package com.nukie.ambientvolume.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun PermissionsWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    
    // 1. Android 12+ (API 31+) & Android 13+ (API 33+) Multiple Permissions
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
    }

    // 2. Permission State Handler
    var allPermissionsGranted by remember {
        mutableStateOf(permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // 3. Request Logic
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            allPermissionsGranted = true
            showRationaleDialog = false
            showSettingsDialog = false
        } else {
            val activity = context as? ComponentActivity
            val shouldShowRationale = permissionsToRequest.any { 
                activity?.shouldShowRequestPermissionRationale(it) == true 
            }
            if (shouldShowRationale) {
                showRationaleDialog = true
            } else {
                // Permanently denied (MIUI's strict manager does this easily)
                showSettingsDialog = true
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    if (allPermissionsGranted) {
        // 5. Initialization: UI and Service can only start if we reach here
        content()
    } else {
        // Wait state / UI while asking for permissions
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Core permissions are required for the Adaptive Volume Engine.",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        
        if (showRationaleDialog) {
            AlertDialog(
                onDismissRequest = { showRationaleDialog = false },
                title = { Text("Permissions Required") },
                text = { Text("This app needs Microphone access to sense ambient noise, and Bluetooth permissions to dynamically adjust your headset's volume. Please grant them to continue.") },
                confirmButton = {
                    TextButton(onClick = {
                        showRationaleDialog = false
                        permissionLauncher.launch(permissionsToRequest)
                    }) { Text("Grant") }
                },
                dismissButton = {
                    TextButton(onClick = { showRationaleDialog = false }) { Text("Deny") }
                }
            )
        }

        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Permissions Denied") },
                text = { Text("Essential permissions have been permanently denied by MIUI. Please open System Settings, go to App Permissions, and grant Microphone and Bluetooth access.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSettingsDialog = false
                        openAppSettings(context)
                    }) { Text("Open Settings") }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

// Helper function to deep-link to App Settings
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
