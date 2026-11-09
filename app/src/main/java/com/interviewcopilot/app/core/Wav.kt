package com.interviewcopilot.app.core

import java.io.ByteArrayOutputStream

/** Encodes 16 kHz mono float samples as a 16-bit PCM WAV file, for upload to a speech API. */
object Wav {
    fun encode(samples: FloatArray, sampleRate: Int = 16_000): ByteArray {
        val dataLen = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)

        fun int32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }

        fun int16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int32(36 + dataLen)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int32(16)
        int16(1) // PCM
        int16(1) // mono
        int32(sampleRate)
        int32(sampleRate * 2)
        int16(2)
        int16(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        int32(dataLen)
        for (f in samples) {
            val v = (f.coerceIn(-1f, 1f) * 32767f).toInt()
            int16(v)
        }
        return out.toByteArray()
    }
}
