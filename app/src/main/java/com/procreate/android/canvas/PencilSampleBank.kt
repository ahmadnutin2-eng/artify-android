package com.procreate.android.canvas

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Decodes the bundled pencil recording once, into plain PCM the synth can cut grains out of.
 *
 * ## Why a recording at all
 *
 * The synth's grains were bursts of white noise. That fixed the structural problem - a pencil is a
 * shower of tiny impacts, not a continuous tone - but white noise has no *timbre*: no graphite, no
 * paper fibre, nothing that says "pencil" rather than "hiss". Real recorded material carries all of
 * that for free, and cutting grains from it keeps every property the distance-driven scheduler
 * already gives us: silence when the pen stops, density that follows speed, no drone.
 *
 * ## The source
 *
 * res/raw/pencil_paper.mp3 - "Pencil #1" by Joseph SARDIN, bigsoundbank.com, released CC0 (public
 * domain): commercial use permitted worldwide, no attribution required. CC0 specifically, rather
 * than a "free for personal use" clip, because this ships inside an app sold on Play.
 *
 * ## Robustness
 *
 * Decoding happens on a background thread and can fail - a codec can be busy, a device can lack the
 * decoder. Nothing waits on it: [samples] is null until it succeeds, and the synth falls back to
 * noise grains meanwhile. Sound is a nicety; it must never be able to stall or crash drawing.
 */
object PencilSampleBank {

    /** Decoded mono PCM, or null while decoding or if it failed. Read from the audio thread. */
    @Volatile var samples: ShortArray? = null
        private set

    /** Sample rate of [samples]; only meaningful once it is non-null. */
    @Volatile var sampleRate: Int = 0
        private set

    @Volatile private var started = false

    /** How much of the clip to keep. A few seconds is ample variety for grains, and holding all 33
     * would cost megabytes of heap for material no one could distinguish. */
    private const val MAX_SECONDS = 6

    fun ensureLoaded(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(isDaemon = true, name = "pencil-sample-decode") {
            try {
                decode(app)?.let { (pcm, rate) ->
                    sampleRate = rate
                    samples = pcm
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Pencil sample unavailable; synth will use noise grains", t)
            }
        }
    }

    private fun decode(context: Context): Pair<ShortArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.resources.openRawResourceFd(R_RAW_PENCIL).use { fd ->
                extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            val fmt = format ?: return null
            extractor.selectTrack(track)

            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val limit = rate * MAX_SECONDS

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val out = ShortArray(limit)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (written < limit) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        // Fold to mono: the synth is mono, and a stereo clip would otherwise read
                        // as interleaved garbage at double speed.
                        var i = 0
                        while (i + channels <= shorts.limit() && written < limit) {
                            var sum = 0
                            for (c in 0 until channels) sum += shorts.get(i + c).toInt()
                            out[written++] = (sum / channels).toShort()
                            i += channels
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            if (written < rate / 2) return null // less than half a second decoded: not usable
            return out.copyOf(written) to rate
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private const val TAG = "PencilSampleBank"
    private const val TIMEOUT_US = 10_000L
    private val R_RAW_PENCIL = com.procreate.android.R.raw.pencil_paper
}
