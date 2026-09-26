package com.procreate.android.collaboration

import android.content.Context
import com.procreate.android.BuildConfig

object CollaborationConfig {
    private const val PREFS = "collaboration"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_CLIENT_ID = "client_id"
    private const val DEFAULT_SERVER_URL = "wss://artify-collaboration-server.onrender.com"

    fun serverUrl(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SERVER_URL, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: BuildConfig.COLLAB_SERVER_URL.trim().takeIf(String::isNotEmpty)
        ?: DEFAULT_SERVER_URL

    fun setServerUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, value.trim()).apply()
    }

    fun clientId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CLIENT_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_CLIENT_ID, it).apply()
        }
    }

    fun displayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISPLAY_NAME, null)
            ?: "Artist ${clientId(context).takeLast(4).uppercase()}"
    }

    fun setDisplayName(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()
    }
}
