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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

import com.nukie.ambientvolume.R

/**
 * A wrapper component that manages the requesting and state of required permissions.
 *
 * This component checks for necessary permissions (Audio Record, Bluetooth, and Notifications)
 * based on the Android version and ensures they are granted before rendering the main application [content].
 * It handles rationale dialogs and provides a path to system settings if permissions are permanently denied.
 *
 * @param content The composable content to be displayed once all required permissions are granted.
 */
@Composable
fun PermissionsWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    
    // 1. Android 12+ (API 31+) & Android 13+ (API 33+) Multiple Permissions
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
        )
    }

    // 2. Permission State Handler
    var allPermissionsGranted by remember {
        mutableStateOf(
            permissionsToRequest.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    var showRationaleDialog by remember { mutableStateOf(value = false) }
    var showSettingsDialog by remember { mutableStateOf(value = false) }

    // 3. Request Logic
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
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
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.core_permissions_required),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        
        if (showRationaleDialog) {
            AlertDialog(
                onDismissRequest = { showRationaleDialog = false },
                title = { Text(stringResource(R.string.dialog_permissions_required_title)) },
                text = { Text(stringResource(R.string.dialog_permissions_required_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRationaleDialog = false
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    ) {
                        Text(stringResource(R.string.button_grant))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showRationaleDialog = false }
                    ) {
                        Text(stringResource(R.string.button_deny))
                    }
                }
            )
        }

        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text(stringResource(R.string.dialog_permissions_denied_title)) },
                text = { Text(stringResource(R.string.dialog_permissions_denied_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSettingsDialog = false
                            openAppSettings(context)
                        }
                    ) {
                        Text(stringResource(R.string.button_open_settings))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSettingsDialog = false }
                    ) {
                        Text(stringResource(R.string.button_cancel))
                    }
                }
            )
        }
    }
}

/**
 * Navigates the user to the application's system settings page.
 *
 * This is used as a fallback when permissions are permanently denied and must be
 * manually enabled by the user in the system settings.
 *
 * @param context The context used to start the activity.
 */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    context.startActivity(intent)
}
