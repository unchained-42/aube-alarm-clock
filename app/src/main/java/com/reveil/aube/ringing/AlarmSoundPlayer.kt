package com.reveil.aube.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import kotlin.math.roundToInt

/**
 * Plays the alarm sound and controls how loud it is through the system's ALARM stream volume
 * — the same channel the hardware volume buttons drive — rather than MediaPlayer's own
 * per-instance gain (`MediaPlayer.setVolume`). The per-instance route proved unreliable for a
 * slow ramp on real hardware: the alarm played at full volume regardless of the low value
 * passed at start, and only actually reflected a quieter setting once something else (a later
 * ramp tick, a screen touch) happened to nudge it — a known class of OEM audio-HAL quirk with
 * per-track software gain during a cold/Doze wake-up. Driving the stream itself is the exact
 * mechanism the OS already guarantees works, since hardware buttons rely on nothing else.
 */
class AlarmSoundPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }

    // Remembered so the user's own alarm-volume preference is restored once ringing ends,
    // rather than being left wherever our last ramp tick happened to leave it.
    private var savedStreamVolume: Int? = null

    // What the stream should be at right now, independent of what it actually is — the ramp
    // loop only calls setVolume() once a second, so between ticks the only thing standing
    // between "silent" and the target is whatever last set it. reassertVolume() re-applies
    // this on demand, e.g. the instant AlarmRingingService sees a foreign VOLUME_CHANGED
    // broadcast, instead of waiting for the next tick to notice and correct it.
    private var targetVolume: Float = 1f

    fun start(customUri: String?, startVolume: Float = 1f) {
        stop()

        val uri: Uri = customUri?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getValidRingtoneUri(context)
            ?: return

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.isLooping = true
            player.setDataSource(context, uri)
            player.prepare()
            mediaPlayer = player
            setVolume(startVolume)
            player.start()
        } catch (e: Exception) {
            player.release()
            mediaPlayer = null
        }
    }

    fun setVolume(volume: Float) {
        if (mediaPlayer == null) return
        targetVolume = volume.coerceIn(0f, 1f)
        applyTargetVolume()
    }

    /** Re-applies [targetVolume] without changing it — for correcting a foreign change. */
    fun reassertVolume() {
        if (mediaPlayer != null) applyTargetVolume()
    }

    private fun applyTargetVolume() {
        if (savedStreamVolume == null) {
            savedStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        // Never rounds down to 0 (silent) — a "barely audible" start should still be audible.
        val index = (targetVolume * max).roundToInt().coerceIn(1, max)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
    }

    fun stop() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {
                // Already stopped/released — nothing to do.
            }
            release()
        }
        mediaPlayer = null
        savedStreamVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        savedStreamVolume = null
    }
}
