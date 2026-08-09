package com.ipdial.service

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.ipdial.data.repository.AccountRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SipRingtonePlayer(
    private val context: Context,
    private val audioManager: AudioManager,
    private val repo: AccountRepository
) {
    private val TAG = "SipService"

    private var ringtone: Ringtone? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isPlayingRingtone = false
    private var ringtoneJob: Job? = null
    private var ringtoneWakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun playRingtone() {
        ringtoneJob?.cancel()
        if (isPlayingRingtone || ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) return

        if (SipEngine.isDndActive()) {
            Log.d(TAG, "DND active, skipping ringtone and vibration")
            return
        }

        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.d(TAG, "Silent mode, skipping ringtone")
            return
        }

        isPlayingRingtone = true

        if (ringtoneWakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            ringtoneWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPDial:ringtone_wake")
        }
        ringtoneWakeLock?.acquire(30000L)

        ringtoneJob = scope.launch {
            try {
                val ringtoneUriStr = repo.globalRingtone.first()
                val vibrateEnabled = repo.globalVibrate.first()

                withContext(Dispatchers.Main) {
                    if (!isPlayingRingtone || ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) {
                        return@withContext
                    }

                    if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        val ringtoneUri = if (ringtoneUriStr != null) {
                            android.net.Uri.parse(ringtoneUriStr)
                        } else {
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        }

                        var mp: android.media.MediaPlayer? = null
                        try {
                            mediaPlayer?.release()
                            mp = android.media.MediaPlayer()
                            mp.setDataSource(context, ringtoneUri)
                            mp.setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                            mp.isLooping = true

                            withContext(Dispatchers.IO) {
                                mp.prepare()
                            }

                            if (!isPlayingRingtone) {
                                mp.release()
                                return@withContext
                            }
                            mp.start()
                            mediaPlayer = mp
                        } catch (e: Exception) {
                            Log.e(TAG, "MediaPlayer failed for ringtone, falling back to RingtoneManager", e)
                            mp?.release()
                            ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                ringtone?.isLooping = true
                            }
                            ringtone?.play()
                        }
                    }

                    if (vibrateEnabled || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play ringtone", e)
                isPlayingRingtone = false
            }
        }
    }

    fun stopRingtone() {
        ringtoneJob?.cancel()
        ringtoneJob = null
        isPlayingRingtone = false
        try {
            ringtoneWakeLock?.let { if (it.isHeld) it.release() }
            ringtone?.stop()
            ringtone = null
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
        } catch (e: Exception) {}
    }
}
