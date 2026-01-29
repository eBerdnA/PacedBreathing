package com.beansys.breathing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

class SoundPlayer {
    private val sampleRate = 44_100
    private val maxCacheSize = 12
    private val sweepCache = LinkedHashMap<String, ShortArray>(maxCacheSize, 0.75f, true)

    private var toneTrack: AudioTrack? = null
    private var cueTrack: AudioTrack? = null

    fun playInhale(duration: Double) {
        playToneSweep(start = 220.0, end = 278.0, duration = duration)
    }

    fun playExhale(duration: Double) {
        playToneSweep(start = 278.0, end = 220.0, duration = duration)
    }

    fun playSuccess() {
        val duration = 0.38
        val frames = (sampleRate * duration).toInt()
        val buffer = ShortArray(frames)

        val notesHz = doubleArrayOf(392.00, 493.88, 587.33)
        val noteDurations = doubleArrayOf(0.11, 0.11, 0.16)
        val noteStarts = doubleArrayOf(0.00, 0.11, 0.22)
        val sparkleHz = doubleArrayOf(1568.0, 1976.0)

        fun smoothEnv(x: Double): Double {
            val a = kotlin.math.sin(0.5 * Math.PI * min(1.0, x / 0.15))
            val d = kotlin.math.sin(0.5 * Math.PI * min(1.0, (1.0 - x) / 0.35))
            return min(a, d)
        }

        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate

            var f = notesHz[2]
            var segStart = noteStarts[2]
            var segDur = noteDurations[2]
            if (t < noteStarts[1]) {
                f = notesHz[0]; segStart = noteStarts[0]; segDur = noteDurations[0]
            } else if (t < noteStarts[2]) {
                f = notesHz[1]; segStart = noteStarts[1]; segDur = noteDurations[1]
            }

            val localT = t - segStart
            val x = max(0.0, min(1.0, localT / segDur))
            val env = smoothEnv(x)

            val main =
                sin(2.0 * Math.PI * f * localT) * 0.85 +
                    sin(2.0 * Math.PI * (2.0 * f) * localT) * 0.15

            val sparkle =
                (sin(2.0 * Math.PI * sparkleHz[0] * t) +
                    sin(2.0 * Math.PI * sparkleHz[1] * t)) * 0.02

            var s = (main * env * 0.22) + (sparkle * env)
            s = tanh(s * 1.3) / 1.3
            buffer[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }

        cueTrack?.stopSafely()
        cueTrack?.release()
        cueTrack = buildTrack(frames)
        cueTrack?.write(buffer, 0, buffer.size)
        cueTrack?.play()
    }

    fun stop() {
        toneTrack?.stopSafely()
        toneTrack?.release()
        toneTrack = null
        cueTrack?.stopSafely()
        cueTrack?.release()
        cueTrack = null
    }

    fun release() {
        stop()
    }

    private fun playToneSweep(start: Double, end: Double, duration: Double) {
        if (duration <= 0.01) return

        val key = cacheKey(start, end, duration)
        val cached = sweepCache[key]
        val buffer = cached ?: generateSweep(start, end, duration).also { cacheBuffer(key, it) }

        toneTrack?.stopSafely()
        toneTrack?.release()
        toneTrack = buildTrack(buffer.size)
        toneTrack?.write(buffer, 0, buffer.size)
        toneTrack?.play()
    }

    private fun generateSweep(start: Double, end: Double, duration: Double): ShortArray {
        val totalFrames = (sampleRate * duration).toInt()
        val buffer = ShortArray(totalFrames)
        val fadeDuration = min(0.2, duration * 0.2)
        val fadeFrames = max(1, (sampleRate * fadeDuration).toInt())

        for (i in 0 until totalFrames) {
            val t = i.toDouble() / totalFrames
            val frequency = start + (end - start) * t
            val base = sin(2.0 * Math.PI * frequency * (i.toDouble() / sampleRate))

            val fadeIn = min(1.0, i.toDouble() / fadeFrames)
            val fadeOut = min(1.0, (totalFrames - i).toDouble() / fadeFrames)
            val envelope = min(fadeIn, fadeOut)

            val sample = base * envelope * 0.18
            buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }

        return buffer
    }

    private fun cacheKey(start: Double, end: Double, duration: Double): String {
        val rounded = round(duration * 2) / 2
        return String.format("%.1f-%.1f-%.1f", start, end, rounded)
    }

    private fun cacheBuffer(key: String, buffer: ShortArray) {
        if (sweepCache.containsKey(key)) return
        sweepCache[key] = buffer
        if (sweepCache.size > maxCacheSize) {
            val iterator = sweepCache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun buildTrack(samples: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
    }

    private fun AudioTrack.stopSafely() {
        runCatching {
            if (playState == AudioTrack.PLAYSTATE_PLAYING || playState == AudioTrack.PLAYSTATE_PAUSED) {
                stop()
            }
        }
    }
}
