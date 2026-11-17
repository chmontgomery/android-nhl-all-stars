package com.personal.hockey.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Plays [SoundSynth] clips through SoundPool and buzzes the phone. Failures are silent: the game still works. */
class AndroidFeedback(private val context: Context, autoPrepare: Boolean = true) : GameFeedback {
    private val pool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }.getOrNull()
    private val ids = ConcurrentHashMap<Sfx, Int>()
    private val loaded: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val vibrator: Vibrator? = runCatching {
        // VibratorManager only exists from API 31; minSdk is 29.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    init {
        pool?.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        if (autoPrepare) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { prepare() }
    }

    /** Writes any missing clips to the cache and loads them. Safe to call more than once. */
    fun prepare() {
        val pool = pool ?: return
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        for (sfx in Sfx.entries) {
            if (ids.containsKey(sfx)) continue
            runCatching {
                val file = File(dir, "${sfx.name.lowercase()}-v1.wav")
                if (!file.exists()) writeAtomically(file, Wav.encode(SoundSynth.render(sfx), SoundSynth.SAMPLE_RATE))
                ids[sfx] = pool.load(file.path, 1)
            }
        }
    }

    override fun play(sfx: Sfx) {
        val id = ids[sfx] ?: return
        if (id in loaded) runCatching { pool?.play(id, 1f, 1f, 1, 0, 1f) }
    }

    override fun vibrate(buzz: Buzz) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (buzz) {
            Buzz.LIGHT -> VibrationEffect.createOneShot(30, 120)
            Buzz.STRONG -> VibrationEffect.createWaveform(longArrayOf(0, 120, 60, 200), intArrayOf(0, 255, 0, 200), -1)
            Buzz.HUGE -> VibrationEffect.createWaveform(longArrayOf(0, 200, 80, 300, 80, 400), intArrayOf(0, 255, 0, 255, 0, 255), -1)
        }
        runCatching { v.vibrate(effect) }
    }
}

/**
 * Writes [bytes] to a temp file beside [file], then renames it into place, so a crash mid-write can
 * never leave a truncated clip under the real name (it would be loaded on every later launch).
 */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("could not move ${tmp.name} into place")
    }
}
