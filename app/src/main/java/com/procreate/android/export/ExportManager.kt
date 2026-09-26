package com.procreate.android.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.procreate.android.R
import java.io.File
import java.io.FileOutputStream

class ExportManager(private val context: Context) {

    private val exportDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    fun exportToPNG(bitmap: Bitmap, filename: String): File {
        val file = File(exportDir, "$filename.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    fun exportToJPEG(bitmap: Bitmap, filename: String, quality: Int): File {
        val file = File(exportDir, "$filename.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }

    /** Exports a single flattened page PDF containing the artwork. */
    fun exportToPDF(flattened: Bitmap, filename: String): File {
        val file = File(exportDir, "$filename.pdf")
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(flattened.width, flattened.height, 1).create()
        val page = document.startPage(pageInfo)
        page.canvas.drawBitmap(flattened, 0f, 0f, null)
        document.finishPage(page)
        FileOutputStream(file).use { out -> document.writeTo(out) }
        document.close()
        return file
    }

    /** Writes the already-serialized DXF text to a file, following the exact same pattern as
     * exportToPDF above (write to exportDir, return the File). */
    fun exportToDXF(dxfText: String, filename: String): File {
        val file = File(exportDir, "$filename.dxf")
        file.writeText(dxfText, Charsets.US_ASCII)
        return file
    }

    fun saveToGallery(bitmap: Bitmap, title: String) {
        MediaStore.Images.Media.insertImage(
            context.contentResolver,
            bitmap,
            title,
            "Exported from Artify"
        )
    }

    /** Copies an already-exported file into the public Downloads folder via MediaStore (no
     * WRITE_EXTERNAL_STORAGE needed on API 29+ for an app's own inserted entries) - this is the
     * actual "save locally" the share sheet alone doesn't provide: the file lands somewhere the
     * user can find it in any file manager afterward, independent of whether they also choose to
     * share it anywhere. Returns null if the write failed for any reason (caller falls back to
     * treating the export as share-only). */
    fun saveToDownloads(file: File, mimeType: String): Uri? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    /** Shares a previously-exported file via a FileProvider content:// URI (safe on modern Android). */
    fun shareDirectly(file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export)))
    }

    /** For a DXF export that also carries a raster sidecar (the flattened brush/paint layers a
     * DXF IMAGE entity references by relative filename - a DXF can never embed pixel data inline)
     * - both files need to land in the same folder on the receiving end, which a single-file
     * ACTION_SEND can't guarantee. */
    fun shareMultiple(files: List<File>, mimeType: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export)))
    }
}
