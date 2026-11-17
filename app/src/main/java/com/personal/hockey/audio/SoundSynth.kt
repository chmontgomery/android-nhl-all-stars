package com.personal.hockey.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Builds every sound effect from sine waves, noise and envelopes; no audio files. Deterministic. */
object SoundSynth {
    const val SAMPLE_RATE = 22_050

    fun render(sfx: Sfx, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val seconds = when (sfx) {
            Sfx.STICK -> 0.08; Sfx.PASS -> 0.12; Sfx.SHOT -> 0.2; Sfx.BOARDS -> 0.25; Sfx.SAVE -> 0.2
            Sfx.HIT -> 0.3; Sfx.WHISTLE -> 0.5; Sfx.BUZZER -> 0.9; Sfx.HORN -> 1.2; Sfx.CHEER -> 1.4
        }
        val x = DoubleArray((seconds * sampleRate).roundToInt())
        val noise = Random(7)
        val sr = sampleRate.toDouble()
        var lp = 0.0
        fun lowNoise(k: Double): Double { lp += k * (noise.nextDouble(-1.0, 1.0) - lp); return lp }
        when (sfx) {
            Sfx.STICK -> fill(x, sr) { t -> (sin(2 * PI * 900 * t) * 0.6 + noise.nextDouble(-1.0, 1.0) * 0.4) * exp(-t * 60) }
            Sfx.PASS -> sweep(x, sr, 500.0, 900.0) { t -> sin(PI * min(1.0, t / 0.12)) }
            Sfx.SHOT -> fill(x, sr) { t -> (lowNoise(0.5) * 2.0 + sin(2 * PI * 180 * t)) * exp(-t * 18) }
            Sfx.BOARDS -> fill(x, sr) { t -> (lowNoise(0.15) * 3.0 + sin(2 * PI * 90 * t)) * exp(-t * 14) }
            Sfx.SAVE -> fill(x, sr) { t -> (sin(2 * PI * 1400 * t) * 0.5 + noise.nextDouble(-1.0, 1.0) * 0.5) * exp(-t * 25) }
            Sfx.HIT -> fill(x, sr) { t -> (lowNoise(0.25) * 3.0 + sin(2 * PI * 70 * t)) * exp(-t * 11) }
            Sfx.WHISTLE -> fill(x, sr) { t ->
                val f = 2800 + 120 * sin(2 * PI * 38 * t)
                sin(2 * PI * f * t) * min(1.0, t / 0.01) * min(1.0, (0.5 - t) / 0.05)
            }
            Sfx.BUZZER -> fill(x, sr) { t ->
                var v = 0.0
                for (k in 1..5 step 2) v += sin(2 * PI * 220 * k * t) / k
                v * min(1.0, t / 0.01) * min(1.0, (0.9 - t) / 0.08)
            }
            Sfx.HORN -> fill(x, sr) { t ->
                val v = sin(2 * PI * 150 * t) + sin(2 * PI * 225 * t) * 0.8 + sin(2 * PI * 300 * t) * 0.4
                v * min(1.0, t / 0.03) * min(1.0, (1.2 - t) / 0.2)
            }
            Sfx.CHEER -> fill(x, sr) { t ->
                val swell = sin(PI * t / 1.4)
                lowNoise(0.35) * 4.0 * swell * (0.7 + 0.3 * sin(2 * PI * 7 * t))
            }
        }
        return toPcm(x)
    }

    private inline fun fill(x: DoubleArray, sr: Double, f: (t: Double) -> Double) {
        for (i in x.indices) x[i] = f(i / sr)
    }

    /** Sine whose pitch glides from [from] to [to] Hz across the clip. */
    private fun sweep(x: DoubleArray, sr: Double, from: Double, to: Double, envelope: (Double) -> Double) {
        var phase = 0.0
        val seconds = x.size / sr
        fill(x, sr) { t ->
            phase += 2 * PI * (from + (to - from) * t / seconds) / sr
            sin(phase) * envelope(t)
        }
    }

    private fun toPcm(x: DoubleArray): ShortArray {
        val peak = max(x.maxOfOrNull { abs(it) } ?: 0.0, 1e-9)
        return ShortArray(x.size) { i ->
            (x[i] / peak * 0.8 * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
