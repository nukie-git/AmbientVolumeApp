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

package com.nukie.ambientvolume.service

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import androidx.annotation.StringRes
import com.nukie.ambientvolume.R

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ambient_volume_settings")

enum class VolumeProfile(
    @StringRes val displayNameRes: Int,
    val baseOffsetDb: Double,
    val smoothing: Double, // Kept for future use (currently unused by rolling mean engine)
    val maxVolumeCap: Float
) {
    LIBRARY(R.string.profile_library, 5.0, 0.05, 0.4f),
    STANDARD(R.string.profile_standard, 10.0, 0.15, 0.8f),
    COMMUTE(R.string.profile_commute, 20.0, 0.3, 1.0f),
    CUSTOM(R.string.profile_custom, 10.0, 0.15, 1.0f)
}

object ProfileManager {
    private val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_profile")
    private val KEY_CUSTOM_OFFSET = doublePreferencesKey("custom_offset")
    private val KEY_VIBRATE_ON_CHANGE = booleanPreferencesKey("vibrate_on_change")
    private val KEY_STEP_SIZE = intPreferencesKey("step_size_db")
    private val KEY_MEAN_INTERVAL = intPreferencesKey("mean_interval_seconds")
    private val KEY_SERVICE_WAS_ACTIVE = booleanPreferencesKey("service_was_active")
    private val KEY_SAFETY_CUMULATIVE_MILLIS = longPreferencesKey("safety_cumulative_millis")
    private val KEY_SAFETY_LAST_RESET_DAY = intPreferencesKey("safety_last_reset_day")
    private val KEY_HEARING_SAFETY_ENABLED = booleanPreferencesKey("hearing_safety_enabled")
    private val KEY_DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
    private val KEY_FOLLOW_SYSTEM_DARK = booleanPreferencesKey("follow_system_dark")
    private val KEY_DARK_MODE_MANUAL = booleanPreferencesKey("dark_mode_manual")

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        appContext = context.applicationContext
        // Sync repository state asynchronously to avoid blocking main thread
        scope.launch {
            try {
                AudioStateRepository.updateActiveProfile(getActiveProfile())
                AudioStateRepository.updateVibrateEnabled(getVibrateEnabled())
                AudioStateRepository.updateStepSize(getStepSize())
                AudioStateRepository.updateMeanInterval(getMeanInterval())
                AudioStateRepository.updateHearingSafetyEnabled(getHearingSafetyEnabled())
                AudioStateRepository.updateDynamicColorEnabled(getDynamicColorEnabled())
                AudioStateRepository.updateFollowSystemDark(getFollowSystemDark())
                AudioStateRepository.updateDarkModeManual(getDarkModeManual())
            } catch (e: Exception) {
                Log.e("ProfileManager", "Failed to initialize profiles from DataStore", e)
                AudioStateRepository.updateActiveProfile(VolumeProfile.STANDARD)
                AudioStateRepository.updateVibrateEnabled(false)
                AudioStateRepository.updateStepSize(3)
                AudioStateRepository.updateMeanInterval(7)
                AudioStateRepository.updateHearingSafetyEnabled(true)
                AudioStateRepository.updateDynamicColorEnabled(true)
                AudioStateRepository.updateFollowSystemDark(true)
                AudioStateRepository.updateDarkModeManual(false)
            }
        }
    }

    suspend fun getActiveProfile(): VolumeProfile {
        val profileName = appContext.dataStore.data.map { preferences ->
            preferences[KEY_ACTIVE_PROFILE] ?: VolumeProfile.STANDARD.name
        }.first()
        
        return try {
            VolumeProfile.valueOf(profileName)
        } catch (e: Exception) {
            VolumeProfile.STANDARD
        }
    }

    suspend fun setActiveProfile(profile: VolumeProfile) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_ACTIVE_PROFILE] = profile.name
        }
        AudioStateRepository.updateActiveProfile(profile)
    }

    suspend fun getCustomOffset(): Double {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_CUSTOM_OFFSET] ?: 10.0
        }.first()
    }

    suspend fun setCustomOffset(offsetDb: Double) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_CUSTOM_OFFSET] = offsetDb
            preferences[KEY_ACTIVE_PROFILE] = VolumeProfile.CUSTOM.name
        }
        AudioStateRepository.updateActiveProfile(VolumeProfile.CUSTOM)
    }

    suspend fun getCurrentOffsetDb(): Double {
        val profile = getActiveProfile()
        return if (profile == VolumeProfile.CUSTOM) {
            getCustomOffset()
        } else {
            profile.baseOffsetDb
        }
    }

    // --- Vibrate on Volume Change ---

    suspend fun getVibrateEnabled(): Boolean {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_VIBRATE_ON_CHANGE] ?: false
        }.first()
    }

    suspend fun setVibrateEnabled(enabled: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_VIBRATE_ON_CHANGE] = enabled
        }
        AudioStateRepository.updateVibrateEnabled(enabled)
    }

    // --- Step Size (dB) ---

    suspend fun getStepSize(): Int {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_STEP_SIZE] ?: 3
        }.first()
    }

    suspend fun setStepSize(size: Int) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_STEP_SIZE] = size
        }
        AudioStateRepository.updateStepSize(size)
    }

    // --- Smoothing Interval (seconds) ---

    suspend fun getMeanInterval(): Int {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_MEAN_INTERVAL] ?: 7
        }.first()
    }

    suspend fun setMeanInterval(seconds: Int) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_MEAN_INTERVAL] = seconds
        }
        AudioStateRepository.updateMeanInterval(seconds)
    }

    // --- Service Active State (for BootReceiver) ---

    suspend fun getServiceWasActive(): Boolean {
        return try {
            appContext.dataStore.data.map { preferences ->
                preferences[KEY_SERVICE_WAS_ACTIVE] ?: false
            }.first()
        } catch (e: Exception) {
            Log.e("ProfileManager", "Error reading service active state", e)
            false
        }
    }

    suspend fun setServiceWasActive(active: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_SERVICE_WAS_ACTIVE] = active
        }
    }

    // --- 60/60 Hearing Safety Persistence ---

    suspend fun getSafetyCumulativeMillis(): Long {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_SAFETY_CUMULATIVE_MILLIS] ?: 0L
        }.first()
    }

    suspend fun updateSafetyCumulativeMillis(millis: Long) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_SAFETY_CUMULATIVE_MILLIS] = millis
        }
    }

    suspend fun getSafetyLastResetDay(): Int {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_SAFETY_LAST_RESET_DAY] ?: -1
        }.first()
    }

    suspend fun setSafetyLastResetDay(day: Int) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_SAFETY_LAST_RESET_DAY] = day
        }
    }

    // --- Hearing Safety Toggle ---

    suspend fun getHearingSafetyEnabled(): Boolean {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_HEARING_SAFETY_ENABLED] ?: true
        }.first()
    }

    suspend fun setHearingSafetyEnabled(enabled: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_HEARING_SAFETY_ENABLED] = enabled
        }
        AudioStateRepository.updateHearingSafetyEnabled(enabled)
    }

    // --- Dynamic Color (Material You) ---

    suspend fun getDynamicColorEnabled(): Boolean {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_DYNAMIC_COLOR_ENABLED] ?: true
        }.first()
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_DYNAMIC_COLOR_ENABLED] = enabled
        }
        AudioStateRepository.updateDynamicColorEnabled(enabled)
    }

    // --- Dark Mode (Follow System / Manual) ---

    suspend fun getFollowSystemDark(): Boolean {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_FOLLOW_SYSTEM_DARK] ?: true
        }.first()
    }

    suspend fun setFollowSystemDark(enabled: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_FOLLOW_SYSTEM_DARK] = enabled
        }
        AudioStateRepository.updateFollowSystemDark(enabled)
    }

    suspend fun getDarkModeManual(): Boolean {
        return appContext.dataStore.data.map { preferences ->
            preferences[KEY_DARK_MODE_MANUAL] ?: false
        }.first()
    }

    suspend fun setDarkModeManual(enabled: Boolean) {
        appContext.dataStore.edit { preferences ->
            preferences[KEY_DARK_MODE_MANUAL] = enabled
        }
        AudioStateRepository.updateDarkModeManual(enabled)
    }
}
