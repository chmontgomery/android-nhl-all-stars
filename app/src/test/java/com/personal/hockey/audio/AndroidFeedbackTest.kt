package com.personal.hockey.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AndroidFeedbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun prepare_writes_every_clip_to_the_cache() {
        AndroidFeedback(context, autoPrepare = false).prepare()
        val files = File(context.cacheDir, "sfx").listFiles()!!.map { it.name }
        assertEquals(Sfx.entries.size, files.size)
        assertTrue(files.all { it.endsWith(".wav") })
    }

    @Test fun prepared_clips_are_complete_and_leave_no_temp_files() {
        AndroidFeedback(context, autoPrepare = false).prepare()
        val dir = File(context.cacheDir, "sfx")
        for (sfx in Sfx.entries) {
            val file = File(dir, "${sfx.name.lowercase()}-v1.wav")
            assertEquals(sfx.name, Wav.encode(SoundSynth.render(sfx), SoundSynth.SAMPLE_RATE).size.toLong(), file.length())
        }
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun a_write_replaces_a_half_written_temp_file_and_appears_whole() {
        val dir = File(context.cacheDir, "atomic").apply { mkdirs() }
        val target = File(dir, "clip.wav")
        File(dir, "clip.wav.tmp").writeBytes(byteArrayOf(1, 2))   // left by a crash mid-write
        writeAtomically(target, ByteArray(1000) { it.toByte() })
        assertEquals(1000L, target.length())
        assertTrue(dir.listFiles()!!.map { it.name } == listOf("clip.wav"))
    }

    @Test fun play_and_vibrate_never_throw() {
        val feedback = AndroidFeedback(context, autoPrepare = false)
        Sfx.entries.forEach { feedback.play(it) }
        feedback.prepare()
        Sfx.entries.forEach { feedback.play(it) }
        Buzz.entries.forEach { feedback.vibrate(it) }
    }

    @Test @Config(sdk = [29]) fun works_on_api_29() = constructs_and_vibrates()
    @Test @Config(sdk = [30]) fun works_on_api_30() = constructs_and_vibrates()
    @Test @Config(sdk = [34]) fun works_on_api_34() = constructs_and_vibrates()

    private fun constructs_and_vibrates() {
        val feedback = AndroidFeedback(context, autoPrepare = false)
        feedback.prepare()
        Sfx.entries.forEach { feedback.play(it) }
        Buzz.entries.forEach { feedback.vibrate(it) }
    }
}
