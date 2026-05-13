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

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.app.AlarmManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nukie.ambientvolume.BuildConfig
import com.nukie.ambientvolume.MainActivity
import com.nukie.ambientvolume.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.abs

class VolumeControlService : Service() {

    private val CHANNEL_ID = "adaptive_volume_v131_channel"
    private val NOTIFICATION_ID = 1

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private lateinit var audioManager: AudioManager
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var maxVolumeLevel = 0

    // Manual Override Lock State
    private var lastAutoSetVolume = -1
    private var manualOverrideDbLock: Double? = null
    private val OVERRIDE_RELEASE_THRESHOLD_DB = 5.0

    // 5-Second Rolling Mean (replaces EMA)
    // At ~100ms delay between readings, 50 samples ≈ 5 seconds
    private val DB_BUFFER_SIZE = 50
    private val movingAverage = MovingAverage(DB_BUFFER_SIZE)

    private var lastAdjustedDb: Double? = null
    private val HYSTERESIS_THRESHOLD = 3.0
    private var isFrozen = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        ProfileManager.init(applicationContext)
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolumeLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AmbientVolume::WakeLock")
        
        // Initialize vibrator for haptic feedback
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MIUI 14 Stability: PendingIntent action to Stop service
        if (intent?.action == "ACTION_STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        AudioStateRepository.setServiceRunning(true)
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this, 
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquire wake lock to keep CPU alive for audio processing
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L /*10 minutes fallback*/)
            }
        } catch (e: Exception) {}

        startListening()

        return START_STICKY
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app needs the mic or audio focus (like Assistant or Phone)
                if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Audio focus lost. Pausing engine sensing.")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Audio focus regained.")
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart service if swiped away (critical for aggressive OEMs)
        val intent = Intent(applicationContext, VolumeControlService::class.java)
        val pendingIntent = PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // MIUI 14 Stability: NotificationChannel with 'High' importance
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Adaptive Volume Control",
                NotificationManager.IMPORTANCE_HIGH 
            ).apply {
                description = "Runs the background noise monitoring to adapt system volume"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        // Content intent: tap notification to open MainActivity
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action
        val stopIntent = Intent(this, VolumeControlService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Engine is Active")
            .setContentText("Listening to ambient noise to adapt volume...")
            .setSmallIcon(R.drawable.ic_adaptive_volume)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun startListening() {
        if (isRecording) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (BuildConfig.DEBUG) Log.e("VolumeControlService", "Microphone permission not granted.")
            stopSelf()
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

            audioRecord?.startRecording()
            isRecording = true
            serviceScope.launch {
            // Dynamic Smoothing Interval Observer
            launch {
                AudioStateRepository.meanInterval.collect { seconds ->
                    val newBufferSize = seconds * 10 // 100ms interval = 10 samples/sec
                    movingAverage.updateWindowSize(newBufferSize)
                    if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Smoothing interval updated to $seconds s ($newBufferSize samples)")
                }
            }

            val buffer = ShortArray(bufferSize)
                while (isActive && isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (readSize == AudioRecord.ERROR_INVALID_OPERATION || readSize == AudioRecord.ERROR_BAD_VALUE) {
                        if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Mic seized by another app (Assistant/Gemini). Freezing state.")
                        isFrozen = true
                        delay(2000)
                        continue
                    }

                    if (readSize > 0) {
                        // Zero-Value Buffer Handling (Assistant/Phone concurrency)
                        val isSilence = buffer.take(readSize).all { it == 0.toShort() }
                        if (isSilence) {
                            if (!isFrozen) {
                                if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Mic returning silence. Potential Assistant focus. Freezing.")
                                isFrozen = true
                            }
                            delay(1000)
                            continue
                        }

                        // Regaining focus from freeze state
                        if (isFrozen) {
                            if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Regaining mic access. Ramping volume...")
                            isFrozen = false
                            lastAdjustedDb = null
                            movingAverage.clear()
                        }

                        // Ambient Noise Sensing: Calculate RMS
                        var sumSquares = 0.0
                        for (i in 0 until readSize) {
                            val sample = buffer[i].toDouble()
                            sumSquares += sample * sample
                        }
                        val rms = sqrt(sumSquares / readSize)

                        // Convert to decibels (dB)
                        if (rms > 0) {
                            val db = 20 * log10(rms)
                            
                            // Push instantaneous dB to UI (for visualizer background layer)
                            AudioStateRepository.updateDb(db.toFloat())

                            // 5-Second Rolling Mean
                            val rollingMeanDb = movingAverage.add(db)

                            // Push rolling mean to UI (for visualizer foreground layer)
                            AudioStateRepository.updateRollingMeanDb(rollingMeanDb.toFloat())

                            val currentProfile = ProfileManager.getActiveProfile()
                            
                            // Hysteresis check (+/- 3dB dead zone) using rolling mean
                            if (lastAdjustedDb == null || abs(rollingMeanDb - lastAdjustedDb!!) >= HYSTERESIS_THRESHOLD) {
                                adjustVolumeBasedOnDb(rollingMeanDb, currentProfile)
                                lastAdjustedDb = rollingMeanDb
                            }
                        }
                    }
                    delay(100)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("VolumeControlService", "Error starting AudioRecord", e)
            stopSelf()
        }
    }

    private var isFirstAdjustment = true

    private suspend fun adjustVolumeBasedOnDb(smoothedDb: Double, profile: VolumeProfile) {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentVolumePercent = currentVolume.toFloat() / maxVolumeLevel.toFloat()
        
        // Push actual volume back to Compose UI StateFlow
        AudioStateRepository.updateVolume(currentVolumePercent)

        // SpotMute / Mutify Protection (Zero-Volume Suspension)
        if (currentVolume == 0) {
            return
        }
        
        // Block auto-adjustments if waiting for user decision
        if (AudioStateRepository.pendingVolumeDecision.value != null) {
            return
        }

        // Volume Mapping Logic
        // Apply the active profile's offset
        val targetOutputDb = smoothedDb + ProfileManager.getCurrentOffsetDb()

        // Define baseline (50dB = 30% volume) and maximum (90dB = 100% volume)
        val minDb = 50.0
        val maxDb = 90.0
        val minVolPercent = 0.30
        val maxVolPercent = profile.maxVolumeCap.toDouble() // Apply Max Volume Cap from Profile

        val targetVolPercent = when {
            targetOutputDb <= minDb -> minVolPercent
            targetOutputDb >= maxDb -> maxVolPercent
            else -> {
                val dbRange = maxDb - minDb
                val volRange = maxVolPercent - minVolPercent
                minVolPercent + ((targetOutputDb - minDb) / dbRange) * volRange
            }
        }

        // Startup Protection Logic
        if (isFirstAdjustment) {
            isFirstAdjustment = false
            // If the user's current volume is significantly higher than what the algorithm suggests, ask first!
            if (currentVolumePercent > targetVolPercent + 0.10) {
                AudioStateRepository.requestVolumeDecision(
                    AudioStateRepository.VolumeDecisionRequest(
                        currentVolumePercent, 
                        targetVolPercent.toFloat(), 
                        smoothedDb
                    )
                )
                return
            }
        }
        
        // Check for manual intervention
        if (lastAutoSetVolume != -1 && currentVolume != lastAutoSetVolume) {
            if (manualOverrideDbLock == null) {
                manualOverrideDbLock = smoothedDb
                if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Manual volume change detected. Auto-Learning new profile.")
                
                // Learn the custom offset
                val newOffset = if (currentVolumePercent <= minVolPercent) {
                    minDb - smoothedDb 
                } else if (currentVolumePercent >= maxVolPercent) {
                    maxDb - smoothedDb 
                } else {
                    val dbRange = maxDb - minDb
                    val volRange = maxVolPercent - minVolPercent
                    ((currentVolumePercent - minVolPercent) / volRange) * dbRange - smoothedDb + minDb
                }

                ProfileManager.setCustomOffset(newOffset)
            }
        }

        // Check if we should release the manual lock
        if (manualOverrideDbLock != null) {
            val dbDifference = abs(smoothedDb - manualOverrideDbLock!!)
            if (dbDifference >= OVERRIDE_RELEASE_THRESHOLD_DB) {
                manualOverrideDbLock = null
                if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Environment changed by $dbDifference dB. Releasing lock.")
            } else {
                // Lock is active. Skip auto-adjust, but keep state in sync
                AudioStateRepository.updateVolume(currentVolumePercent)
                lastAutoSetVolume = currentVolume
                return
            }
        }

        // Calculate the target volume units
        val targetVolumeUnits = (maxVolumeLevel * targetVolPercent).roundToInt()

        var newlySetVolume = currentVolume

        // Step-size-aware volume adjustment
        val stepSizeDb = AudioStateRepository.stepSize.value
        val dbPerUnit = (maxDb - minDb) / maxVolumeLevel
        val stepUnits = (stepSizeDb / dbPerUnit).roundToInt().coerceAtLeast(1)

        if (currentVolume < targetVolumeUnits) {
            newlySetVolume = (currentVolume + stepUnits).coerceAtMost(targetVolumeUnits)
        } else if (currentVolume > targetVolumeUnits) {
            newlySetVolume = (currentVolume - stepUnits).coerceAtLeast(targetVolumeUnits)
        }

        if (newlySetVolume != currentVolume) {
            // Bluetooth Buffer: Small delay to handle wireless latency
            if (isBluetoothOutputActive()) {
                delay(500)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newlySetVolume, 0)

            // Haptic feedback on volume step change
            if (AudioStateRepository.vibrateEnabled.value) {
                triggerHapticFeedback()
            }
        }

        lastAutoSetVolume = newlySetVolume
    }

    /**
     * Triggers a short 10ms haptic tick when the volume changes.
     * Uses VibrationEffect on API 26+.
     */
    private fun triggerHapticFeedback() {
        try {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(10)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Haptic feedback failed: ${e.message}")
        }
    }

    private fun isBluetoothOutputActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isFirstAdjustment = true
        AudioStateRepository.setServiceRunning(false)
        AudioStateRepository.updateDb(0f)
        AudioStateRepository.updateRollingMeanDb(0f)
        movingAverage.clear()
        serviceJob.cancel()
        
        if (isRecording) {
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("VolumeControlService", "Error stopping AudioRecord", e)
            }
            audioRecord?.release()
            audioRecord = null
            isRecording = false
        }
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null 
    }
}
