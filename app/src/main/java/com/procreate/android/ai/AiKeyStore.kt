package com.procreate.android.ai

import android.content.Context
import com.procreate.android.BuildConfig

/**
 * The effective API keys for the plan-analysis feature, from either of two sources.
 *
 * Why this exists: [BuildConfig] keys come from local.properties and are deliberately compiled to
 * the empty string in a release build, so no key can ever ship inside the Play Store APK. That is
 * the right call for secrecy, but on its own it left the feature visibly present and permanently
 * broken for every Play user - they would tap "تحليل المخطط بالذكاء الاصطناعي" and be told to edit
 * a developer file they do not have. A shipped feature that cannot work is a Play policy problem
 * (Broken Functionality) as much as a user-experience one.
 *
 * So a second source is added rather than the feature being removed: the user supplies their own
 * key, which is stored only on their device. The developer build keeps working with no setup (the
 * BuildConfig key wins), and the Play build works for anyone who pastes a key in. Nothing is
 * hidden, nothing is deleted, and still no key travels inside the APK.
 *
 * [init] must run before any read; [com.procreate.android.ArtifyApplication] calls it.
 */
object AiKeyStore {

    /**
     * A file of its own, separate from the app's ordinary preferences, purely so it can be excluded
     * from backup: res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml name this file.
     *
     * Both backup mechanisms work at file granularity - there is no way to exclude a single key
     * from a shared file - so keeping the credential here is what lets the rest of the preferences
     * (drawing sound, gesture guide) still restore onto a new device while the key does not. The
     * settings screen tells the user the key stays on their device; with the key sitting in the
     * general preferences file that claim would simply have been untrue, since allowBackup is on
     * and the file would have been copied to their Google Drive.
     */
    internal const val PREFS = "artify_ai_keys"
    private const val KEY_GEMINI = "user_gemini_api_key"
    private const val KEY_NVIDIA = "user_nvidia_api_key"

    // Held as a field because PlanVisionClient runs on background threads with no Context of its
    // own, and reading SharedPreferences on every request would be needless disk I/O per API call.
    @Volatile private var userGemini: String = ""
    @Volatile private var userNvidia: String = ""
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        userGemini = prefs.getString(KEY_GEMINI, "").orEmpty()
        userNvidia = prefs.getString(KEY_NVIDIA, "").orEmpty()
    }

    /** The build-time key when present (developer build), otherwise whatever the user supplied. */
    val geminiKey: String
        get() = BuildConfig.GEMINI_API_KEY.ifBlank { userGemini }

    val nvidiaKey: String
        get() = BuildConfig.NVIDIA_API_KEY.ifBlank { userNvidia }

    fun hasAnyKey(): Boolean = geminiKey.isNotBlank() || nvidiaKey.isNotBlank()

    /** True when the key came from local.properties, so the settings screen can say it is fixed. */
    fun isGeminiFromBuild(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    fun setUserGeminiKey(context: Context, key: String) {
        val trimmed = key.trim()
        userGemini = trimmed
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEMINI, trimmed).apply()
    }

    fun setUserNvidiaKey(context: Context, key: String) {
        val trimmed = key.trim()
        userNvidia = trimmed
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_NVIDIA, trimmed).apply()
    }

    /** Shown in the settings field so a saved key is recognisable without exposing it in full. */
    fun maskedGemini(): String = mask(userGemini)

    private fun mask(key: String): String = when {
        key.isBlank() -> ""
        key.length <= 8 -> "•".repeat(key.length)
        else -> key.take(4) + "•".repeat(8) + key.takeLast(4)
    }
}
