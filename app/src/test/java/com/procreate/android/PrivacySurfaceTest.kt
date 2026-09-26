package com.procreate.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the app's privacy surface: what can leave the device, what identifiers it may read, and what
 * the manifest is allowed to ask for.
 *
 * These are not style rules. Each one is a thing Google Play checks and can reject or remove an app
 * over, and each is easy to reintroduce by accident - a analytics SDK added for one screen, a
 * device id pulled in to "tag" a crash report, a permission copied from a snippet. A reviewer reads
 * the manifest and the behaviour, not the intent, so the guard belongs in the build.
 */
class PrivacySurfaceTest {

    private val moduleDir: File by lazy {
        var dir = File(".").canonicalFile
        while (!File(dir, "src/main/AndroidManifest.xml").isFile) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val manifest: String get() = File(moduleDir, "src/main/AndroidManifest.xml").readText()

    private fun mainSources(): Sequence<File> =
        File(moduleDir, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }

    @Test
    fun `manifest is actually found`() {
        assertTrue("Could not locate the manifest from ${File(".").canonicalPath}", manifest.isNotEmpty())
    }

    @Test
    fun `no network call exists outside the known senders`() {
        // Everything that talks to a server must stay somewhere a reader can find it. Both known
        // AI senders are gated as described below. CollaborationClient only starts after the user
        // opens Online, sees the data-sharing notice, and explicitly presses Search. OpenverseClient
        // sends only a search phrase after the online library is opened; canvas pixels are never sent.
        val allowed = setOf(
            "AiBugReporter.kt", "CollaborationClient.kt", "OpenverseClient.kt", "PlanVisionClient.kt"
        )
        val networkApi = Regex("""HttpURLConnection|URLConnection|OkHttp|Retrofit|java\.net\.Socket""")

        val senders = mainSources()
            .filter { networkApi.containsMatchIn(it.readText()) }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "A new file performs network I/O. Anything that sends user data needs the same " +
                "consent gate as PlanVisionClient, and the Play Data safety form needs updating.",
            allowed.toSortedSet(),
            senders
        )
    }

    @Test
    fun `no device or user identifier is read anywhere`() {
        // The app deliberately knows nothing about who is using it. Reading any of these would turn
        // a "collects no data" Data safety declaration into a false one.
        val identifiers = listOf(
            "ANDROID_ID", "getDeviceId", "getSubscriberId", "getSerial", "Build.SERIAL",
            "AdvertisingIdClient", "getMacAddress", "TelephonyManager",
            "getLastKnownLocation", "ContactsContract", "AccountManager"
        )

        val offenders = mainSources().flatMap { file ->
            val text = file.readText()
            identifiers.filter { text.contains(it) }.map { "${file.name}: $it" }
        }.toList()

        assertTrue(
            "Identifier or sensitive-data API introduced:\n" + offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty()
        )
    }

    @Test
    fun `manifest requests no sensitive or unused permission`() {
        val declared = Regex("""uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSortedSet()

        // INTERNET and ACCESS_NETWORK_STATE are the whole list on purpose. The media/storage
        // permissions were removed because the app reads images through the system picker, which
        // needs none - and READ_MEDIA_IMAGES in particular pulls the app into Play's Photo and
        // Video Permissions policy, which refuses apps that could have used the picker instead.
        assertEquals(
            "The set of declared permissions changed. Anything added here must be justified to " +
                "Play, and several require a declaration form.",
            sortedSetOf("ACCESS_NETWORK_STATE", "INTERNET"),
            declared
        )
    }

    @Test
    fun `cleartext http is never enabled`() {
        assertTrue(
            "usesCleartextTraffic must stay off - unencrypted uploads of user images would be " +
                "both a Play finding and a real exposure.",
            !manifest.contains("usesCleartextTraffic=\"true\"")
        )
        assertTrue(
            "A networkSecurityConfig was added; check it does not re-enable cleartext.",
            !manifest.contains("networkSecurityConfig")
        )
    }

    @Test
    fun `the user's API key is excluded from backup`() {
        // allowBackup is on so artwork survives a new device. The credential must not ride along:
        // the settings screen promises the key stays on the device, and both backup mechanisms
        // (pre-31 full backup and 31+ data extraction) have to exclude it for that to be true.
        assertTrue("fullBackupContent rules missing", manifest.contains("@xml/backup_rules"))
        assertTrue("dataExtractionRules missing", manifest.contains("@xml/data_extraction_rules"))

        val prefsFile = "artify_ai_keys.xml"
        val legacy = File(moduleDir, "src/main/res/xml/backup_rules.xml").readText()
        val modern = File(moduleDir, "src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue("backup_rules.xml does not exclude $prefsFile", legacy.contains(prefsFile))
        assertTrue("data_extraction_rules.xml does not exclude $prefsFile", modern.contains(prefsFile))
        // device-transfer is a separate path from cloud-backup; excluding only one still leaks it.
        assertTrue(
            "data_extraction_rules.xml must exclude the key from device-transfer as well as cloud-backup",
            modern.contains("device-transfer") && modern.contains("cloud-backup")
        )
    }

    @Test
    fun `uploading a plan image is gated on stored consent`() {
        val activity = File(
            moduleDir, "src/main/java/com/procreate/android/ui/canvas/CanvasActivity.kt"
        ).readText()

        assertTrue(
            "CanvasActivity no longer checks AiPrivacyConsent before starting plan analysis - " +
                "user images would be uploaded without an affirmative yes.",
            activity.contains("AiPrivacyConsent.hasConsented")
        )

        // The picker must not be reachable from anywhere that skipped the check above.
        val launchSites = Regex("""analyzePlanLauncher\.launch""").findAll(activity).count()
        assertEquals(
            "analyzePlanLauncher is launched from an unexpected number of places; every path to " +
                "it must pass the consent check first.",
            2, launchSites
        )
    }
}
