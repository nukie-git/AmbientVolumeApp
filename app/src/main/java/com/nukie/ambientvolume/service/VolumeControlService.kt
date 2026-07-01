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
import android.media.AudioDeviceInfo
import android.media.audiofx.AcousticEchoCanceler
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
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
import com.nukie.ambientvolume.util.DebugLogger

class VolumeControlService : Service() {

    private val CHANNEL_ID = "adaptive_volume_v182_channel"
    private val NOTIFICATION_ID = 1030

    private var serviceStartTime = 0L
    private val INITIALIZATION_DEAD_ZONE_MS = 5000L

    private var isRecording = false
    private val preferredSampleRate = 16000
    private val fallbackSampleRate = 8000
    private var activeSampleRate = preferredSampleRate
    private var bufferSize = 0

    private lateinit var audioManager: AudioManager
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var maxVolumeLevel = 0

    // Manual Override Lock State
    private var lastAutoSetVolume = -1
    private var manualOverrideDbLock: Double? = null
    private val OVERRIDE_RELEASE_THRESHOLD_DB = 5.0

    // 20-Second Rolling Mean
    // At ~100ms delay between readings, 200 samples ≈ 20 seconds
    private val DB_BUFFER_SIZE = 200
    private val movingAverage = MovingAverage(DB_BUFFER_SIZE)

    private var lastAdjustedDb: Double? = null
    private val HYSTERESIS_THRESHOLD = 3.0
    private var isFrozen = false

    // Feedback Loop Prevention & Echo Suppression
    private var echoCanceler: AcousticEchoCanceler? = null
    private var lastSystemVolumeTime = 0L
    private var lastSystemVolumeLevel = -1
    private val FEEDBACK_CORRELATION_WINDOW_MS = 50L
    private val SOFTWARE_AEC_MAX_RMS_REDUCTION = 1200.0 // Calibrated for speaker-to-mic coupling
    
    // 1.5s Peak Filter
    private var peakStartTime: Long? = null
    private var lastPeakDb: Double? = null
    private val PEAK_DURATION_THRESHOLD_MS = 1500L
    private val PEAK_DB_THRESHOLD = 10.0

    // 60/60 Safety Rule (Cumulative)
    private var cumulativeHighVolumeMillis = 0L
    private var highVolumeStartTime: Long? = null
    private var lastSafetyCheckTime = 0L
    private val SAFETY_THRESHOLD_PERCENT = 0.60f
    private val SAFETY_DURATION_REQUIRED_MS = 60 * 60 * 1000L // 60 minutes

    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        ProfileManager.init(applicationContext)
        
        
        audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolumeLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        serviceStartTime = SystemClock.elapsedRealtime()
        
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AmbientVolume::WakeLock")
        
        // Initialize vibrator for haptic feedback
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createNotificationChannel()
        
        // Initial load of safety timer from DataStore
        serviceScope.launch {
            val currentDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            val lastResetDay = ProfileManager.getSafetyLastResetDay()
            
            if (currentDay != lastResetDay) {
                ProfileManager.updateSafetyCumulativeMillis(0L)
                ProfileManager.setSafetyLastResetDay(currentDay)
                cumulativeHighVolumeMillis = 0L
            } else {
                cumulativeHighVolumeMillis = ProfileManager.getSafetyCumulativeMillis()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MIUI 14 Stability: PendingIntent action to Stop service
        if (intent?.action == "ACTION_STOP_SERVICE") {
            // User explicitly stopped — mark as intentionally inactive
            serviceScope.launch { ProfileManager.setServiceWasActive(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent?.action == "ACTION_RESET_SAFETY") {
            cumulativeHighVolumeMillis = 0L
            highVolumeStartTime = null
            serviceScope.launch { ProfileManager.updateSafetyCumulativeMillis(0L) }
            AudioStateRepository.updateSafetyThresholdReached(false)
            return START_STICKY
        }

        // Mark service as active for BootReceiver self-healing
        serviceScope.launch { ProfileManager.setServiceWasActive(true) }
        AudioStateRepository.setServiceRunning(true)
        val notification = createNotification()

        try {
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
        } catch (e: Exception) {
            // On Android 14+, starting a FOREGROUND_SERVICE_TYPE_MICROPHONE service from a
            // background context (e.g. the onTaskRemoved -> AlarmManager restart path) can
            // throw ForegroundServiceStartNotAllowedException. This is an OS policy decision,
            // not a recoverable error here — fail cleanly instead of crashing the process.
            // The app's onResume self-heal (triggered from a foreground Activity context,
            // which is exempt from this restriction) is the real recovery path in that case.
            if (BuildConfig.DEBUG) Log.e("VolumeControlService", "Foreground service start blocked by OS", e)
            AudioStateRepository.setServiceRunning(false)
            // Deliberately NOT clearing KEY_SERVICE_WAS_ACTIVE here: the user's intent (engine
            // should be running) hasn't changed, only this specific background restart attempt
            // was blocked by OS policy. Leaving the flag true is what lets onResume self-heal
            // — which runs in a foreground context and isn't subject to this restriction —
            // successfully restart the engine next time the user opens the app.
            stopSelf()
            return START_NOT_STICKY
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart service if swiped away (critical for aggressive OEMs)
        val intent = Intent(applicationContext, VolumeControlService::class.java)
        val pendingIntent = PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // MIUI 14 Stability: NotificationChannel with 'High' importance
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH 
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
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
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.app_description))
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setLargeIcon(getBitmapFromDrawable(this, R.mipmap.ic_launcher_new))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Changed to LOW to avoid persistent status bar clutter on some ROMs while keeping it visible
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.button_stop), stopPendingIntent)
            .build()
    }

    private fun getBitmapFromDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(108),
            drawable.intrinsicHeight.coerceAtLeast(108),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun startListening() {
        if (isRecording) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (BuildConfig.DEBUG) Log.e("VolumeControlService", "Microphone permission not granted.")
            stopSelf()
            return
        }

        isRecording = true
        
        DebugLogger.log(this@VolumeControlService, "Engine sensing started. Duty-cycled loop active.")
        
        lastSystemVolumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        serviceScope.launch {
            launch {
                AudioStateRepository.meanInterval.collect { seconds ->
                    // Calculate buffer size in terms of loop cycles (~5s per cycle)
                    val newBufferSize = (seconds / 5.0).roundToInt().coerceAtLeast(1)
                    movingAverage.updateWindowSize(newBufferSize)
                    if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Smoothing interval updated to $seconds s ($newBufferSize cycles)")
                }
            }

            while (isActive && isRecording) {
                if (!audioManager.isMusicActive) {
                    if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Music not active. Sleeping for 20s.")
                    delay(20000)
                    continue
                }

                var loopAudioRecord: AudioRecord? = null
                try {
                    bufferSize = AudioRecord.getMinBufferSize(preferredSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    activeSampleRate = preferredSampleRate
                    
                    if (bufferSize <= 0) {
                        activeSampleRate = fallbackSampleRate
                        bufferSize = AudioRecord.getMinBufferSize(fallbackSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    }

                    val requiredBufferSize = activeSampleRate * 2 // 1 second of 16-bit Mono
                    val actualBufferSize = maxOf(bufferSize, requiredBufferSize)

                    loopAudioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        activeSampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        actualBufferSize
                    )

                    // NOTE: No audio focus is requested here. This is a pure AudioRecord
                    // capture session for RMS analysis — it never plays audio, so requesting
                    // focus (especially AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) only served to
                    // duck the user's currently playing media every duty cycle for no reason.

                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(loopAudioRecord.audioSessionId)
                        echoCanceler?.enabled = true
                    }

                    loopAudioRecord.startRecording()
                    
                    val samplesToRead = activeSampleRate // 1 second exactly
                    val buffer = ShortArray(samplesToRead)
                    var totalRead = 0
                    
                    val startTime = SystemClock.elapsedRealtime()
                    while (totalRead < samplesToRead && isActive && isRecording) {
                        if (SystemClock.elapsedRealtime() - startTime > 1500) break // Safety timeout
                        val readSize = loopAudioRecord.read(buffer, totalRead, samplesToRead - totalRead)
                        if (readSize < 0) {
                            if (BuildConfig.DEBUG) Log.d("VolumeControlService", "Mic seized or error: $readSize")
                            break
                        }
                        totalRead += readSize
                    }

                    if (totalRead > 0) {
                        val isSilence = buffer.take(totalRead).all { it == 0.toShort() }
                        if (!isSilence) {
                            var sumSquares = 0.0
                            for (i in 0 until totalRead) {
                                val sample = buffer[i].toDouble()
                                sumSquares += sample * sample
                            }
                            val rms = sqrt(sumSquares / totalRead)

                            if (rms > 0) {
                                var processedRms = rms
                                val needsSoftwareCorrection = (echoCanceler == null || !echoCanceler!!.enabled) &&
                                        !isExternalAudioOutputActive()
                                if (needsSoftwareCorrection) {
                                    val systemVolumePercent = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / maxVolumeLevel.toDouble()
                                    val reduction = systemVolumePercent * SOFTWARE_AEC_MAX_RMS_REDUCTION
                                    // Cap the reduction at 90% of the measured RMS. The flat,
                                    // content-agnostic model can't tell a quiet passage from a
                                    // loud one, so an uncapped subtraction can zero out the
                                    // signal on quiet playback and freeze the engine entirely.
                                    // Capping guarantees at least some signal always survives.
                                    val cappedReduction = reduction.coerceAtMost(rms * 0.9)
                                    processedRms = sqrt((rms * rms - cappedReduction * cappedReduction).coerceAtLeast(0.0))
                                }

                                if (processedRms > 0) {
                                    val db = 20 * log10(processedRms)
                                    
                                    val currentSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    if (currentSystemVolume > lastSystemVolumeLevel) {
                                        lastSystemVolumeTime = SystemClock.elapsedRealtime()
                                    }
                                    lastSystemVolumeLevel = currentSystemVolume

                                    val timeSinceVolChange = SystemClock.elapsedRealtime() - lastSystemVolumeTime
                                    if (timeSinceVolChange >= FEEDBACK_CORRELATION_WINDOW_MS) {
                                        AudioStateRepository.updateDb(db.toFloat())
                                        
                                        val rollingMeanDb = movingAverage.add(db)
                                        AudioStateRepository.updateRollingMeanDb(rollingMeanDb.toFloat())

                                        val currentProfile = ProfileManager.getActiveProfile()
                                        if (lastAdjustedDb == null || abs(rollingMeanDb - lastAdjustedDb!!) >= HYSTERESIS_THRESHOLD) {
                                            adjustVolumeBasedOnDb(rollingMeanDb, currentProfile)
                                            lastAdjustedDb = rollingMeanDb
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("VolumeControlService", "Error in duty-cycle loop", e)
                } finally {
                    echoCanceler?.enabled = false
                    echoCanceler?.release()
                    echoCanceler = null

                    try {
                        loopAudioRecord?.stop()
                        loopAudioRecord?.release()
                    } catch (e: Exception) {}
                    
                    delay(5000) // Sleep 5 seconds before next hardware cycle
                }
            }
        }
    }

    private var isFirstAdjustment = true

    private suspend fun adjustVolumeBasedOnDb(smoothedDb: Double, profile: VolumeProfile) {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentVolumePercent = currentVolume.toFloat() / maxVolumeLevel.toFloat()
        
        // Push actual volume back to Compose UI StateFlow
        AudioStateRepository.updateVolume(currentVolumePercent)

        // 3. 60/60 Hearing Safety Rule (Epoch-based tracking)
        if (AudioStateRepository.hearingSafetyEnabled.value) {
            val isAboveThreshold = currentVolumePercent > SAFETY_THRESHOLD_PERCENT
            val now = SystemClock.elapsedRealtime()

            // Initialization Gate: Suppress for first 5 seconds
            if (now - serviceStartTime > INITIALIZATION_DEAD_ZONE_MS) {
                if (isAboveThreshold) {
                    if (highVolumeStartTime == null) {
                        highVolumeStartTime = now
                    } else {
                        val sessionElapsed = now - highVolumeStartTime!!
                        cumulativeHighVolumeMillis += sessionElapsed
                        highVolumeStartTime = now // Reset anchor to current epoch
                    }
                } else {
                    highVolumeStartTime = null
                }

                if (cumulativeHighVolumeMillis >= SAFETY_DURATION_REQUIRED_MS) {
                    AudioStateRepository.updateSafetyThresholdReached(true)
                }
                
                // Persist current cumulative time to DataStore
                serviceScope.launch {
                    ProfileManager.updateSafetyCumulativeMillis(cumulativeHighVolumeMillis)
                }
            }
        } else {
            // Safety disabled - ensure values are reset and logic bypassed
            cumulativeHighVolumeMillis = 0L
            highVolumeStartTime = null
        }

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
        val targetVolumeExact = maxVolumeLevel * targetVolPercent
        
        // Job 4: Ignore fractional micro-steps (must be >= 1 full step)
        if (abs(targetVolumeExact - currentVolume) < 1.0) {
            // Lock active state in sync
            AudioStateRepository.updateVolume(currentVolumePercent)
            lastAutoSetVolume = currentVolume
            return
        }

        val targetVolumeUnits = targetVolumeExact.roundToInt()

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
            
            if (BuildConfig.DEBUG) {
                DebugLogger.log(this@VolumeControlService, "Volume Adjusted: ${currentVolume} -> ${newlySetVolume} (Units) based on ${smoothedDb.roundToInt()} dB")
            }

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

    /**
     * Speaker-to-mic bleed-through is only physically possible when audio is routed to the
     * device's built-in speaker. Wired and Bluetooth output make it impossible, so the
     * software AEC fallback (a static estimate with no knowledge of actual playback content)
     * must never run in those cases — it would just be corrupting a clean ambient reading.
     */
    private fun isExternalAudioOutputActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn || audioManager.isWiredHeadsetOn
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
        
        echoCanceler?.enabled = false
        echoCanceler?.release()
        echoCanceler = null
        
        if (isRecording) {
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
