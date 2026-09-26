package com.procreate.android.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class OpenverseImage(
    val id: String,
    val title: String,
    val creator: String,
    val license: String,
    val licenseVersion: String,
    val imageUrl: String,
    val thumbnailUrl: String,
    val sourceUrl: String,
    val licenseUrl: String
) {
    val licenseLabel: String
        get() = when (license.lowercase()) {
            "cc0" -> "CC0${versionSuffix()}"
            "pdm" -> "Public Domain"
            else -> "CC ${license.uppercase()}${versionSuffix()}"
        }

    fun layerName(): String = buildString {
        append(title.ifBlank { "Openverse" }.take(48))
        if (creator.isNotBlank()) append(" — ").append(creator.take(36))
        append(" — ").append(licenseLabel)
    }.take(100)

    private fun versionSuffix() = licenseVersion
        .takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
        ?.let { " $it" }
        .orEmpty()
}

/**
 * Anonymous Openverse search plus image download. Search only starts after the user opens the
 * online library, and only the typed search phrase is sent. Artwork on the canvas never leaves the
 * device. Results are restricted to licenses that permit both commercial use and modification.
 */
object OpenverseClient {
    private const val MAX_DOWNLOAD_BYTES = 28L * 1024L * 1024L
    private const val USER_AGENT = "Artify-Android/1.9 (Openverse integration)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, page: Int = 1): List<OpenverseImage> = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.openverse.org")
            .addPathSegment("v1")
            .addPathSegment("images")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("page_size", "20")
            .addQueryParameter("mature", "false")
            .addQueryParameter("license_type", "commercial,modification")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Openverse search failed (${response.code})")
            val json = JSONObject(response.body?.string() ?: error("Empty Openverse response"))
            val results = json.optJSONArray("results") ?: return@use emptyList()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val imageUrl = item.optString("url")
                    val thumbnailUrl = item.optString("thumbnail")
                    if (!imageUrl.isHttps() || !thumbnailUrl.isHttps()) continue
                    add(
                        OpenverseImage(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            creator = item.optString("creator"),
                            license = item.optString("license"),
                            licenseVersion = item.optString("license_version"),
                            imageUrl = imageUrl,
                            thumbnailUrl = thumbnailUrl,
                            sourceUrl = item.optString("foreign_landing_url").takeIf { it.isHttps() }.orEmpty(),
                            licenseUrl = item.optString("license_url").takeIf { it.isHttps() }.orEmpty()
                        )
                    )
                }
            }
        }
    }

    suspend fun download(image: OpenverseImage, maxWidth: Int, maxHeight: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            sequenceOf(image.imageUrl, image.thumbnailUrl)
                .distinct()
                .mapNotNull { url -> runCatching { downloadAndDecode(url, maxWidth, maxHeight) }.getOrNull() }
                .firstOrNull()
        }

    private fun downloadAndDecode(url: String, maxWidth: Int, maxHeight: Int): Bitmap {
        require(url.isHttps())
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val bytes = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Image download failed (${response.code})")
            val body = response.body ?: error("Empty image response")
            val announcedLength = body.contentLength()
            if (announcedLength > MAX_DOWNLOAD_BYTES) error("Image is too large")
            body.byteStream().use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOWNLOAD_BYTES) error("Image is too large")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Unsupported image format")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth &&
            bounds.outHeight / (sample * 2) >= maxHeight
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("Image decoder returned no bitmap")
    }

    private fun String.isHttps() = startsWith("https://", ignoreCase = true)
}
