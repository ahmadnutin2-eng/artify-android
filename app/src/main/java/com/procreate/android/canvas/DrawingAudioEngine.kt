package com.procreate.android.canvas

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * محرك صوت الرسم: يحوّل حركة القلم إلى صوت احتكاك حُبيبي على الورق.
 *
 * All synthesis lives in [PencilGrainSynth]; this class is only the Android plumbing - an
 * [AudioTrack], a feeder thread, and the conversion from touch events into distance travelled.
 * Keeping the two apart is what lets the actual sound be unit-tested (see PencilGrainSynthTest)
 * rather than evaluated by ear on one device.
 *
 * ## What changed, and why it needed changing rather than tuning
 *
 * The previous engine synthesised continuous filtered noise with a 180 Hz sine underneath it, and
 * shaped the result with a gain envelope. It was softened twice and still read as harsh, because
 * the level was never the problem: a pencil does not make a *continuous* sound. Its tip catches and
 * releases on paper fibres thousands of times, and that shower of tiny impacts is what the ear
 * recognises. Continuous noise sounds like static no matter how gently it is filtered, and a steady
 * oscillator under it sounds like a machine.
 *
 * Grains are now scheduled by distance travelled, which also deletes a pile of special cases: there
 * is no gain envelope, no pitch factor, and no "has the finger moved in the last 80 ms" timer -
 * a pen that is not moving simply triggers no grains.
 */
class DrawingAudioEngine(context: Context? = null) {

    init {
        // Kick off decoding of the recorded pencil sample. Nothing waits on it: until it lands the
        // synth uses noise grains, and if the decode fails it keeps using them for good.
        context?.let { PencilSampleBank.ensureLoaded(it) }
    }


    companion object {
        private const val SAMPLE_RATE = 22050
        private const val BUFFER_SIZE = 512

        /** Beyond this, an old position is stale and the gap is not real drawing distance - it is
         * a lifted pen, a new stroke, or a dropped frame. */
        private const val MAX_STEP_PX = 400f
    }

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var isRunning = false
    @Volatile private var isSoundEnabled = true

    private val synth = PencilGrainSynth(SAMPLE_RATE)

    /** Distance the tip has covered but the audio thread has not yet consumed. Written from the
     * touch thread, drained by the audio thread. */
    @Volatile private var pendingDistance = 0f

    init {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(BUFFER_SIZE * 4)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            startSynthesisThread()
        } catch (e: Exception) {
            e.printStackTrace()
            audioTrack = null
        }
    }

    private fun startSynthesisThread() {
        val track = audioTrack ?: return
        isRunning = true
        track.play()

        audioThread = Thread {
            // Android's audio priority gives this small blocking writer regular deadlines without
            // assigning Java MAX_PRIORITY, which can compete with the pen/UI thread on busy devices.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val floats = FloatArray(BUFFER_SIZE)
            val shorts = ShortArray(BUFFER_SIZE)

            while (isRunning) {
                adoptSampleWhenReady()

                // Claim the distance accumulated since the last buffer and hand it to the synth,
                // which spreads it across the buffer rather than triggering it all at sample 0.
                val travelled = pendingDistance
                pendingDistance = 0f

                synth.render(floats, BUFFER_SIZE, travelled)
                for (i in 0 until BUFFER_SIZE) {
                    shorts[i] = (floats[i] * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
                }

                try {
                    track.write(shorts, 0, BUFFER_SIZE)
                } catch (e: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private var sampleAdopted = false

    /**
     * Hands the decoded recording to the synth once it is ready, resampled to this engine's rate
     * and normalised.
     *
     * Runs on the audio thread between buffers, so the swap happens between grains rather than in
     * the middle of one. Resampling is nearest-neighbour on purpose: these are 2-9 ms fragments
     * chosen at random offsets, and no one can hear interpolation error in material like that.
     */
    private fun adoptSampleWhenReady() {
        if (sampleAdopted) return
        val pcm = PencilSampleBank.samples ?: return
        val srcRate = PencilSampleBank.sampleRate
        if (srcRate <= 0 || pcm.isEmpty()) { sampleAdopted = true; return }

        val ratio = srcRate.toFloat() / SAMPLE_RATE
        val outLen = (pcm.size / ratio).toInt().coerceAtLeast(1)
        val floats = FloatArray(outLen)
        var peak = 1e-6f
        for (i in 0 until outLen) {
            val v = pcm[(i * ratio).toInt().coerceIn(0, pcm.size - 1)] / 32768f
            floats[i] = v
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        // Normalise so the recording's own level cannot change how loud drawing is - the grain
        // envelope and the master volume are the only things that should decide that.
        val norm = 0.9f / peak
        for (i in floats.indices) floats[i] *= norm

        synth.source = floats
        sampleAdopted = true
    }

    /** تُستدعى عند بدء لمسة الرسم على اللوحة. */
    fun startStroke(pressure: Float) {
        if (!isSoundEnabled) return
        pendingDistance = 0f
        synth.beginStroke(pressure)
    }

    /**
     * تُستدعى لحظياً مع كل حركة لمس.
     *
     * @param speedPxPerSec السرعة اللحظية بالبكسل في الثانية
     * @param pressure ضغط القلم (0..1)
     * @param distanceStepPx المسافة المقطوعة بين العينتين بالبكسل (اختياري)
     */
    fun updateMotion(speedPxPerSec: Float, pressure: Float, distanceStepPx: Float = -1f) {
        if (!isSoundEnabled) return
        val speed = speedPxPerSec.coerceIn(0f, 6000f)
        synth.setMotion(speed, pressure)

        val step = if (distanceStepPx >= 0f) {
            distanceStepPx.coerceIn(0f, MAX_STEP_PX)
        } else {
            // Buffer-fraction proportional step based on speed
            val dt = 1f / 60f // typical touch interval
            (speed * dt).coerceIn(0f, MAX_STEP_PX)
        }
        pendingDistance = (pendingDistance + step).coerceAtMost(MAX_STEP_PX * 3f)
    }

    /** تُستدعى عند رفع القلم. لا يوجد صوت انفصال - تتوقف الحُبيبات وحسب. */
    fun endStroke() {
        pendingDistance = 0f
        synth.endStroke()
    }

    /** ضبط ملف الصوت (رصاص مخملي، قلم حبر ناعم، ريشة فنية). */
    fun setSoundProfile(profile: DrawingSoundProfile) {
        synth.profile = profile
    }

    fun getSoundProfile(): DrawingSoundProfile = synth.profile

    /** Makes AUTO sound like the material being used instead of one sample for every brush. */
    fun setBrushMaterial(brushType: BrushType, toolMode: ToolMode) {
        synth.automaticProfile = when {
            toolMode == ToolMode.SMUDGE || toolMode == ToolMode.BLUR -> DrawingSoundProfile.SOFT_BRUSH
            brushType == BrushType.Ink -> DrawingSoundProfile.SMOOTH_MARKER
            brushType == BrushType.Paint || brushType == BrushType.Airbrush || brushType == BrushType.Smudge ->
                DrawingSoundProfile.SOFT_BRUSH
            else -> DrawingSoundProfile.VELVET_PENCIL
        }
    }

    /** ضبط مستوى صوت الرسم (0.0 إلى 1.0). */
    fun setVolume(volume: Float) {
        synth.volume = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = synth.volume

    /** تفعيل/تعطيل صوت الرسم بالكامل. */
    fun setSoundEnabled(enabled: Boolean) {
        isSoundEnabled = enabled
        if (!enabled) {
            pendingDistance = 0f
            synth.reset()
        }
    }

    fun isSoundEnabled(): Boolean = isSoundEnabled

    /** تحرير موارد الصوت عند إغلاق العرض. */
    fun release() {
        isRunning = false
        synth.reset()
        try {
            audioThread?.interrupt()
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }
}

/** Persists the user's drawing-sound settings across app restarts. */
object DrawingSoundPrefs {
    private const val PREFS = "procreate_prefs"
    private const val KEY_SOUND_ENABLED = "drawing_sound_enabled"
    private const val KEY_SOUND_VOLUME = "drawing_sound_volume"
    private const val KEY_SOUND_PROFILE = "drawing_sound_profile"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOUND_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun getVolume(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SOUND_VOLUME, 0.75f)

    fun setVolume(context: Context, volume: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SOUND_VOLUME, volume.coerceIn(0f, 1f)).apply()
    }

    fun getProfile(context: Context): DrawingSoundProfile {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOUND_PROFILE, null)
        return try {
            if (name != null) DrawingSoundProfile.valueOf(name) else DrawingSoundProfile.AUTO
        } catch (e: Exception) {
            DrawingSoundProfile.AUTO
        }
    }

    fun setProfile(context: Context, profile: DrawingSoundProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOUND_PROFILE, profile.name).apply()
    }
}
