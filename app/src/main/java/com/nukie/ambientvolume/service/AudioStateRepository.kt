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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioStateRepository {
    private val _currentDb = MutableStateFlow(0f)
    val currentDb: StateFlow<Float> = _currentDb.asStateFlow()

    private val _rollingMeanDb = MutableStateFlow(0f)
    val rollingMeanDb: StateFlow<Float> = _rollingMeanDb.asStateFlow()

    private val _currentVolume = MutableStateFlow(0f)
    val currentVolume: StateFlow<Float> = _currentVolume.asStateFlow()
    
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _activeProfile = MutableStateFlow(VolumeProfile.STANDARD)
    val activeProfile: StateFlow<VolumeProfile> = _activeProfile.asStateFlow()

    // Settings: Haptic feedback
    private val _vibrateEnabled = MutableStateFlow(false)
    val vibrateEnabled: StateFlow<Boolean> = _vibrateEnabled.asStateFlow()

    // Settings: Step size in dB
    private val _stepSize = MutableStateFlow(3)
    val stepSize: StateFlow<Int> = _stepSize.asStateFlow()

    // Settings: Smoothing interval in seconds
    private val _meanInterval = MutableStateFlow(7)
    val meanInterval: StateFlow<Int> = _meanInterval.asStateFlow()

    data class VolumeDecisionRequest(val currentVolumePercent: Float, val suggestedTargetPercent: Float, val currentDb: Double)
    
    private val _pendingVolumeDecision = MutableStateFlow<VolumeDecisionRequest?>(null)
    val pendingVolumeDecision: StateFlow<VolumeDecisionRequest?> = _pendingVolumeDecision.asStateFlow()

    private val _safetyThresholdReached = MutableStateFlow(false)
    val safetyThresholdReached: StateFlow<Boolean> = _safetyThresholdReached.asStateFlow()

    private val _hearingSafetyEnabled = MutableStateFlow(true)
    val hearingSafetyEnabled: StateFlow<Boolean> = _hearingSafetyEnabled.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(true)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    private val _followSystemDark = MutableStateFlow(true)
    val followSystemDark: StateFlow<Boolean> = _followSystemDark.asStateFlow()

    private val _darkModeManual = MutableStateFlow(false)
    val darkModeManual: StateFlow<Boolean> = _darkModeManual.asStateFlow()

    fun updateDb(db: Float) {
        _currentDb.value = db
    }

    fun updateRollingMeanDb(db: Float) {
        _rollingMeanDb.value = db
    }

    fun updateVolume(percent: Float) {
        _currentVolume.value = percent
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun updateActiveProfile(profile: VolumeProfile) {
        _activeProfile.value = profile
    }

    fun updateVibrateEnabled(enabled: Boolean) {
        _vibrateEnabled.value = enabled
    }

    fun updateStepSize(size: Int) {
        _stepSize.value = size
    }

    fun updateMeanInterval(seconds: Int) {
        _meanInterval.value = seconds
    }

    fun requestVolumeDecision(request: VolumeDecisionRequest?) {
        _pendingVolumeDecision.value = request
    }

    fun updateSafetyThresholdReached(reached: Boolean) {
        _safetyThresholdReached.value = reached
    }

    fun updateHearingSafetyEnabled(enabled: Boolean) {
        _hearingSafetyEnabled.value = enabled
    }

    fun updateDynamicColorEnabled(enabled: Boolean) {
        _dynamicColorEnabled.value = enabled
    }

    fun updateFollowSystemDark(enabled: Boolean) {
        _followSystemDark.value = enabled
    }

    fun updateDarkModeManual(enabled: Boolean) {
        _darkModeManual.value = enabled
    }
}
