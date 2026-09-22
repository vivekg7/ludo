package com.crylo.ludo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Every sound the game makes. */
enum class Sound { ROLL, LAND, STEP, CAPTURE, HOME, NO_MOVE, WIN }

/**
 * The game's sound effects, synthesised into PCM when the screen opens rather
 * than shipped as audio files: a handful of clicks and chimes costs a few
 * hundred kilobytes of memory while the game is open but no audio data in the
 * APK, the same trade the board makes by being drawn on a Canvas, not bitmaps.
 *
 * Each sound gets its own static [AudioTrack], so different sounds overlap and
 * replaying one just rewinds it. They play on the game usage, so the media
 * volume controls them and they mix with whatever else is playing.
 */
class Sounds {

    /** Muted sounds are still built, so unmuting mid-game needs no rebuild. */
    var enabled = true

    /**
     * Set while the screen is in the background. The turn loop's scheduled bot
     * steps keep running there, and a die rattling from a closed app would be
     * the first sign of it.
     */
    private var paused = false

    private val tracks = arrayOfNulls<AudioTrack>(Sound.entries.size)

    init {
        val random = Random(SEED)
        for (sound in Sound.entries) {
            tracks[sound.ordinal] = try {
                trackOf(render(sound, random))
            } catch (e: RuntimeException) {
                // A device with no audio output to hand simply stays silent.
                null
            }
        }
    }

    fun play(sound: Sound) {
        if (!enabled || paused) return
        val track = tracks[sound.ordinal] ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: IllegalStateException) {
            // The track lost its audio output; skipping one sound is harmless.
        }
    }

    /** Silences what is playing and anything asked for until [resume]. */
    fun pause() {
        paused = true
        for (track in tracks) {
            try {
                track?.stop()
            } catch (e: IllegalStateException) {
                // Already unusable, so already silent.
            }
        }
    }

    fun resume() {
        paused = false
    }

    fun release() {
        for (i in tracks.indices) {
            tracks[i]?.release()
            tracks[i] = null
        }
    }

    private fun trackOf(samples: FloatArray): AudioTrack {
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    // --- synthesis ---------------------------------------------------------

    private fun render(sound: Sound, random: Random): FloatArray = when (sound) {
        // Dice knocking together, slowing down as they settle. Ends a little
        // before the die's tumble so the landing thud has room.
        Sound.ROLL -> buffer(0.5f).also { out ->
            var at = 0f
            var gap = 0.028f
            while (at < 0.46f) {
                knock(out, at, 1800f + random.nextFloat() * 1600f, 0.22f + random.nextFloat() * 0.12f, random)
                at += gap
                gap *= 1.16f
            }
        }

        Sound.LAND -> buffer(0.14f).also { out ->
            tone(out, 0f, 150f, 0.14f, 0.5f, decay = 0.035f)
            knock(out, 0f, 900f, 0.25f, random)
        }

        // A light wooden tap per square a token walks.
        Sound.STEP -> buffer(0.07f).also { out ->
            tone(out, 0f, 620f, 0.07f, 0.32f, decay = 0.014f)
            knock(out, 0f, 2400f, 0.08f, random)
        }

        // A falling slide as the captured token is knocked back to its yard.
        Sound.CAPTURE -> buffer(0.32f).also { out ->
            sweep(out, 0f, 880f, 160f, 0.32f, 0.38f)
            knock(out, 0f, 1200f, 0.3f, random)
        }

        Sound.HOME -> buffer(0.5f).also { out ->
            bell(out, 0f, C6, 0.3f, 0.3f)
            bell(out, 0.11f, G6, 0.39f, 0.3f)
        }

        Sound.NO_MOVE -> buffer(0.34f).also { out ->
            tone(out, 0f, 330f, 0.16f, 0.28f, decay = 0.08f, square = true)
            tone(out, 0.15f, 247f, 0.19f, 0.28f, decay = 0.09f, square = true)
        }

        Sound.WIN -> buffer(1.2f).also { out ->
            listOf(C5, E5, G5).forEachIndexed { i, pitch -> bell(out, i * 0.12f, pitch, 0.4f, 0.22f) }
            bell(out, 0.36f, C6, 0.84f, 0.26f)
            bell(out, 0.36f, E5, 0.84f, 0.14f)
            bell(out, 0.36f, G5, 0.84f, 0.14f)
        }
    }

    private fun buffer(seconds: Float) = FloatArray((seconds * RATE).toInt())

    /** A sine (or soft square) with a click-free attack and an exponential decay. */
    private fun tone(
        out: FloatArray,
        start: Float,
        hz: Float,
        seconds: Float,
        gain: Float,
        decay: Float,
        square: Boolean = false,
    ) {
        val from = (start * RATE).toInt()
        val count = minOf((seconds * RATE).toInt(), out.size - from)
        for (i in 0 until count) {
            val t = i / RATE.toFloat()
            var wave = sin(2 * PI * hz * t).toFloat()
            if (square) wave = (wave * 3f).coerceIn(-1f, 1f) * 0.6f
            out[from + i] += wave * gain * envelope(i, count) * exp(-t / decay)
        }
    }

    /**
     * A tone whose pitch glides from [fromHz] to [toHz]. Only the capture uses
     * it so far, but it takes the same arguments as [tone] and [bell] so the
     * next effect can too.
     */
    @Suppress("SameParameterValue")
    private fun sweep(out: FloatArray, start: Float, fromHz: Float, toHz: Float, seconds: Float, gain: Float) {
        val from = (start * RATE).toInt()
        val count = minOf((seconds * RATE).toInt(), out.size - from)
        var phase = 0.0
        for (i in 0 until count) {
            val progress = i / count.toFloat()
            phase += 2 * PI * (fromHz + (toHz - fromHz) * progress) / RATE
            val wave = sin(phase).toFloat() + 0.3f * sin(2 * phase).toFloat()
            out[from + i] += wave * gain * envelope(i, count) * (1f - progress * 0.7f)
        }
    }

    /** A struck-bell tone: the fundamental plus a quieter, faster-dying overtone. */
    private fun bell(out: FloatArray, start: Float, hz: Float, seconds: Float, gain: Float) {
        tone(out, start, hz, seconds, gain, decay = seconds * 0.4f)
        tone(out, start, hz * 2.76f, seconds * 0.5f, gain * 0.25f, decay = seconds * 0.12f)
    }

    /** A few milliseconds of ringing noise: one hard object tapping another. */
    private fun knock(out: FloatArray, start: Float, hz: Float, gain: Float, random: Random) {
        val from = (start * RATE).toInt()
        val count = minOf((0.02f * RATE).toInt(), out.size - from)
        for (i in 0 until count) {
            val t = i / RATE.toFloat()
            val noise = random.nextFloat() * 2f - 1f
            val ring = sin(2 * PI * hz * t).toFloat()
            out[from + i] += (noise * 0.5f + ring) * gain * envelope(i, count) * exp(-t / 0.004f)
        }
    }

    /** Ramps the first and last couple of milliseconds so nothing pops. */
    private fun envelope(i: Int, count: Int): Float {
        val ramp = RATE / 500
        return minOf(1f, i / ramp.toFloat(), (count - i) / ramp.toFloat())
    }

    private companion object {
        const val RATE = 44100

        // Fixed so the rattle sounds the same every game.
        const val SEED = 7

        const val C5 = 523.25f
        const val E5 = 659.25f
        const val G5 = 783.99f
        const val C6 = 1046.5f
        const val G6 = 1567.98f
    }
}
