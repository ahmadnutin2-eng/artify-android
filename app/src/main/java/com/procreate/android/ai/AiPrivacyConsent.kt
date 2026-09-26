package com.procreate.android.ai

import android.content.Context

/**
 * Records whether the user has affirmatively agreed to their chosen plan image being uploaded for
 * analysis.
 *
 * Why a stored consent rather than a notice: plan analysis sends a picture the user selected from
 * their own device to a third party's servers. A photo is personal user data, and Google Play's
 * User Data policy asks for a prominent in-app disclosure plus an affirmative action - a tap that
 * means yes - before any such transfer, not merely a sentence somewhere in the UI describing it.
 * The app previously explained the upload in a panel subtitle and again inside the analysis sheet,
 * which is honest but is disclosure, not consent: nothing in that flow required the user to agree.
 *
 * It lives in [AiKeyStore.PREFS] deliberately. That file is excluded from cloud backup and device
 * transfer, so a restore onto a new device asks again instead of silently inheriting a decision
 * made on different hardware - consent should be given by the person holding the device.
 */
object AiPrivacyConsent {

    private const val KEY_CONSENT = "upload_consent_granted"

    fun hasConsented(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    fun grant(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONSENT, true).apply()
    }

    /**
     * Withdrawing must be as easy as granting, so the settings screen offers it. The next analysis
     * asks again from scratch rather than quietly proceeding on the old answer.
     */
    fun revoke(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONSENT, false).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(AiKeyStore.PREFS, Context.MODE_PRIVATE)
}
