package com.procreate.android.debug

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.procreate.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Developer-only tooling: turns a crash or a manually-flagged issue into a written bug report,
 * using NVIDIA's hosted Llama chat API to translate a raw stack trace/device dump into something
 * readable, instead of a tester having to write one by hand. Everything here is gated on
 * [BuildConfig.DEBUG] and the key being non-blank - both are false in the build that goes to
 * Play, so none of this ever runs (or even has a key to run with) outside a debug build the
 * developer installed directly. See app/build.gradle for why the key can't leak into that build.
 */
object AiBugReporter {

    private const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    // meta/llama-3.1-70b-instruct (the model originally used here) was retired from NVIDIA's
    // catalog at some point - every summarize() call was silently failing (410 Gone) and
    // reportManualIssue/processPendingCrashes were falling back to writing the raw dump with no
    // AI summary, which is how "الإبلاغ عن مشكلة" reports were showing up with no analysis
    // attached. Verified live against NVIDIA's endpoint on 2026-09-11 that this id responds; if
    // it's retired too, list what's currently live with a GET to /v1/models and swap here.
    private const val MODEL = "meta/llama-3.2-11b-vision-instruct"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isAvailable: Boolean get() = BuildConfig.DEBUG && BuildConfig.NVIDIA_API_KEY.isNotBlank()

    private fun reportsDir(context: Context): File =
        File(context.filesDir, "bug_reports").apply { mkdirs() }

    private fun pendingCrashesDir(context: Context): File =
        File(context.filesDir, "pending_crashes").apply { mkdirs() }

    /** Installs a global crash handler that logs the raw crash immediately (must be fast and
     * can't touch the network - the process is about to die) and then hands off to whatever
     * handler was already installed, so normal crash/ANR behavior is untouched. */
    fun installCrashHandler(context: Context) {
        if (!isAvailable) return
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = File(pendingCrashesDir(appContext), "${System.currentTimeMillis()}.txt")
                file.writeText(buildRawDump(appContext, "Crash on thread '${thread.name}'", throwable.stackTraceToString()))
            } catch (_: Throwable) {
                // Already crashing - a logging failure here must never block the real handler.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Call once on startup (after the crash handler is installed) to turn any crash dumps left
     * over from a previous run into AI-written reports, now that the network is available again. */
    fun processPendingCrashes(context: Context) {
        if (!isAvailable) return
        val appContext = context.applicationContext
        scope.launch {
            val dir = pendingCrashesDir(appContext)
            val pending = dir.listFiles { f -> f.isFile } ?: return@launch
            for (file in pending) {
                val raw = runCatching { file.readText() }.getOrNull() ?: continue
                writeReport(appContext, rawDump = raw, kind = "crash")
                file.delete()
            }
        }
    }

    /** The manual "report an issue" path: a short note from whoever's testing, plus the same
     * device/app context a crash report gets, written up by the AI the same way. [screenshot],
     * when given, is shown to the model directly (this is a vision-capable model) instead of
     * just being filed alongside the text - a picture of, say, a misplaced button says more than
     * a sentence trying to describe where it overlaps. */
    fun reportManualIssue(context: Context, userNote: String, screenshot: Bitmap? = null, onDone: (File?) -> Unit = {}) {
        if (!isAvailable) {
            onDone(null)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val raw = buildRawDump(appContext, "Manually flagged issue", "User's description:\n$userNote")
            val file = writeReport(appContext, rawDump = raw, kind = "issue", screenshot = screenshot)
            withContext(Dispatchers.Main) { onDone(file) }
        }
    }

    private suspend fun writeReport(context: Context, rawDump: String, kind: String, screenshot: Bitmap? = null): File? {
        val screenshotBase64 = screenshot?.let { encodeForUpload(it) }
        val summary = summarize(rawDump, screenshotBase64)
        // The summary used to be the *whole* file, so a vague tester note ("app crashes on
        // launch with Arabic text", no repro steps) produced a report that was just as vague
        // ("Likely cause: unclear... Steps: not available") with the original wording gone -
        // nothing left to go back and read for the detail the AI couldn't infer. Keeping the raw
        // note/dump underneath means a vague summary is never a dead end.
        val written = if (summary != null) "$summary\n\n---\nRaw report:\n$rawDump" else rawDump
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(6)
        val file = File(reportsDir(context), "${kind}_${stamp}_$suffix.txt")
        val saved = runCatching {
            file.writeText(written)
            file
        }.getOrNull()
        // Saved next to (not instead of) the text report, using the same stamp/suffix, so the two
        // are easy to pair up when pulling reports off the device later.
        if (saved != null && screenshot != null) {
            runCatching {
                File(reportsDir(context), "${kind}_${stamp}_$suffix.png").outputStream().use { out ->
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
        return saved
    }

    /** Downscaled + JPEG-compressed for the upload, not the saved file - keeps the request small
     * and fast over whatever connection the tester is on, without needing NIM's separate asset
     * upload API for larger images. */
    private fun encodeForUpload(bitmap: Bitmap): String {
        val maxDim = 1024
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildRawDump(context: Context, title: String, body: String): String = buildString {
        appendLine(title)
        appendLine("Time: ${Date()}")
        appendLine("App version: ${runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("unknown")}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine(body)
    }

    /** Blocking HTTP call to NVIDIA's OpenAI-compatible chat completions endpoint - kept as a
     * plain HttpURLConnection call rather than pulling in a new HTTP library dependency for what
     * is, on a device, a handful of calls per debugging session. Always called from Dispatchers.IO.
     * [imageBase64], when given, rides along as an image_url content part - MODEL is a
     * vision-instruct model, so it actually looks at the screenshot rather than the text alone. */
    private fun summarize(rawDump: String, imageBase64: String? = null): String? {
        return try {
            val url = URL(ENDPOINT)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer ${BuildConfig.NVIDIA_API_KEY}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val userContent: Any = if (imageBase64 != null) {
                JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", rawDump) })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$imageBase64") })
                    })
                }
            } else rawDump

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You write short, clear bug reports for an Android app's developer from raw " +
                            "crash dumps or tester notes, sometimes with a screenshot attached. Output " +
                            "plain text with these sections: Summary (one line), Likely cause (best guess " +
                            "from the stack trace/context/screenshot, or 'unclear' if there isn't enough " +
                            "to go on), Steps to reproduce (inferred if possible, otherwise 'not " +
                            "available'), Severity (low/medium/high). If a screenshot is attached, describe " +
                            "only what is actually visible in it - do not invent a crash, a stack trace, or " +
                            "a specific technical cause that the text and image don't support. A screenshot " +
                            "of an unrelated screen (e.g. the phone's own system settings, not this app), or " +
                            "one with no visible problem, plus an empty tester note, means there usually " +
                            "isn't a real bug here: say so plainly (e.g. 'the screenshot doesn't show an " +
                            "app problem and no description was given') instead of guessing one. Be concise."
                    )
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", 500)
            }

            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            if (connection.responseCode !in 200..299) {
                connection.errorStream?.close()
                return null
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val choice = JSONObject(responseText).getJSONArray("choices").getJSONObject(0)
            choice.getJSONObject("message").getString("content")
        } catch (_: Exception) {
            null
        }
    }
}
