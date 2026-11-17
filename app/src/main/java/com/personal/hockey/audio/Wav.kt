package com.personal.hockey.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit mono PCM WAV writer. */
object Wav {
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataSize); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataSize)
        samples.forEach { b.putShort(it) }
        return b.array()
    }
}
