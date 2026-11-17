package com.personal.hockey.audio

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSynthTest {
    @Test fun every_sound_is_audible_bounded_and_short() {
        for (sfx in Sfx.entries) {
            val pcm = SoundSynth.render(sfx)
            val seconds = pcm.size / SoundSynth.SAMPLE_RATE.toDouble()
            assertTrue("$sfx length $seconds", seconds in 0.05..1.5)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$sfx peak $peak", peak in 3000..32767)
        }
    }

    @Test fun render_is_deterministic() {
        for (sfx in Sfx.entries) assertArrayEquals("$sfx", SoundSynth.render(sfx), SoundSynth.render(sfx))
    }
}
