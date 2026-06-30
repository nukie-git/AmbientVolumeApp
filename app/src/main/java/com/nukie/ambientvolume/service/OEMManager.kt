/*
 * Ambient Volume - Adaptive Volume Engine
 * Copyright (C) 2026 @nukie-git
 */

package com.nukie.ambientvolume.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

import androidx.annotation.StringRes
import com.nukie.ambientvolume.R

enum class OEMType(@StringRes val titleRes: Int, @StringRes val descRes: Int, val intent: Intent) {
    SAMSUNG(R.string.oem_samsung_title, R.string.oem_samsung_desc, Intent().apply {
        component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
    }),
    XIAOMI(R.string.oem_xiaomi_title, R.string.oem_xiaomi_desc, Intent().apply {
        component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    }),
    HUAWEI(R.string.oem_huawei_title, R.string.oem_huawei_desc, Intent().apply {
        component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
    }),
    OPPO(R.string.oem_oppo_title, R.string.oem_oppo_desc, Intent().apply {
        component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
    }),
    VIVO(R.string.oem_vivo_title, R.string.oem_vivo_desc, Intent().apply {
        component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
    })
}

object OEMManager {
    fun getDetectedOEM(context: Context): OEMType? {
        val manufacturer = Build.MANUFACTURER.uppercase()
        return when {
            manufacturer.contains("SAMSUNG") -> OEMType.SAMSUNG
            manufacturer.contains("XIAOMI") || manufacturer.contains("REDMI") || manufacturer.contains("POCO") -> OEMType.XIAOMI
            manufacturer.contains("HUAWEI") || manufacturer.contains("HONOR") -> OEMType.HUAWEI
            manufacturer.contains("OPPO") || manufacturer.contains("REALME") -> OEMType.OPPO
            manufacturer.contains("VIVO") -> OEMType.VIVO
            else -> null
        }
    }

    fun isDuraSpeedPresent(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.mediatek.duraspeed", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getXiaomiRomName(): String {
        val hyperOsVersion = getSystemProperty("ro.mi.os.version.name")
        if (!hyperOsVersion.isNullOrBlank()) return "HyperOS"
        return "MIUI"
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            get.invoke(c, key) as? String
        } catch (e: Exception) {
            null
        }
    }
}
