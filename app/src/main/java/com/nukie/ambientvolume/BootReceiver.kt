package com.nukie.ambientvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nukie.ambientvolume.service.ProfileManager
import com.nukie.ambientvolume.service.VolumeControlService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Only auto-restart if the service was previously active
            ProfileManager.init(context.applicationContext)
            if (!ProfileManager.getServiceWasActive()) return

            val serviceIntent = Intent(context, VolumeControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
