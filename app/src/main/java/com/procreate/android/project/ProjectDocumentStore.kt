package com.procreate.android.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * Owns the app-private editable project package:
 *
 * project_documents/<stable project key hash>/project.json
 * project_documents/<stable project key hash>/layers/<stable layer id hash>.png
 *
 * Layer files are committed first and project.json is committed last. AtomicFile keeps the last
 * valid version of each individual file if Android kills the process during an autosave.
 */
class ProjectDocumentStore private constructor(private val rootDirectory: File) {
    constructor(context: Context) : this(File(context.filesDir, PROJECTS_DIRECTORY))

    @Synchronized
    @Throws(IOException::class)
    fun save(
        projectKey: String,
        document: ProjectDocumentDto,
        bitmapsByLayerId: Map<String, Bitmap> = emptyMap(),
        maskBitmapsByLayerId: Map<String, Bitmap> = emptyMap()
    ): String {
        require(projectKey.isNotBlank()) { "projectKey cannot be blank" }
        val encoded = ProjectDocumentCodec.encodeToBytes(document)
        val layersById = document.rasterLayers.associateBy(RasterLayerDto::id)
        val unknownBitmapIds = bitmapsByLayerId.keys - layersById.keys
        require(unknownBitmapIds.isEmpty()) {
            "Bitmap payload contains unknown layer ids: ${unknownBitmapIds.joinToString()}"
        }
        val unknownMaskIds = maskBitmapsByLayerId.keys - layersById.keys
        require(unknownMaskIds.isEmpty()) {
            "Mask payload contains unknown layer ids: ${unknownMaskIds.joinToString()}"
        }

        val projectDirectory = projectDirectory(projectKey)
        ensureDirectory(projectDirectory)
        document.rasterLayers.forEach { layer ->
            val target = resolveAsset(projectDirectory, layer.bitmapAssetPath)
            val bitmap = bitmapsByLayerId[layer.id]
            if (bitmap != null) {
                if (bitmap.width != layer.pixelWidth || bitmap.height != layer.pixelHeight) {
                    throw IOException(
                        "Layer '${layer.id}' bitmap is ${bitmap.width}x${bitmap.height}, " +
                            "expected ${layer.pixelWidth}x${layer.pixelHeight}"
                    )
                }
                ensureDirectory(target.parentFile ?: projectDirectory)
                writeBitmapAtomically(target, bitmap)
            } else if (!target.exists()) {
                throw FileNotFoundException(
                    "No bitmap supplied and no saved asset exists for layer '${layer.id}'"
                )
            }
            layer.maskAssetPath?.let { maskPath ->
                val maskTarget = resolveAsset(projectDirectory, maskPath)
                val mask = maskBitmapsByLayerId[layer.id]
                if (mask != null) {
                    if (mask.width != layer.pixelWidth || mask.height != layer.pixelHeight) {
                        throw IOException(
                            "Layer '${layer.id}' mask is ${mask.width}x${mask.height}, " +
                                "expected ${layer.pixelWidth}x${layer.pixelHeight}"
                        )
                    }
                    ensureDirectory(maskTarget.parentFile ?: projectDirectory)
                    writeBitmapAtomically(maskTarget, mask)
                } else if (!maskTarget.exists()) {
                    throw FileNotFoundException(
                        "No mask supplied and no saved asset exists for layer '${layer.id}'"
                    )
                }
            }
        }

        val documentFile = File(projectDirectory, DOCUMENT_FILE_NAME)
        writeBytesAtomically(documentFile, encoded)
        return documentFile.absolutePath
    }

    @Synchronized
    @Throws(IOException::class)
    fun load(documentPath: String, verifyRasterAssets: Boolean = true): ProjectDocumentDto {
        val documentFile = ownedDocumentFile(documentPath)
        if (!documentFile.exists() && !File(documentFile.path + ".bak").exists()) {
            throw FileNotFoundException("Project document does not exist: $documentPath")
        }
        val document = ProjectDocumentCodec.decode(AtomicFile(documentFile).readFully())
        if (verifyRasterAssets) {
            document.rasterLayers.forEach { layer ->
                val asset = resolveAsset(documentFile.parentFile, layer.bitmapAssetPath)
                if (!asset.exists() && !File(asset.path + ".bak").exists()) {
                    throw FileNotFoundException(
                        "Project layer '${layer.id}' asset is missing: ${layer.bitmapAssetPath}"
                    )
                }
                layer.maskAssetPath?.let { maskPath ->
                    val mask = resolveAsset(documentFile.parentFile, maskPath)
                    if (!mask.exists() && !File(mask.path + ".bak").exists()) {
                        throw FileNotFoundException(
                            "Project layer '${layer.id}' mask asset is missing: $maskPath"
                        )
                    }
                }
            }
        }
        return document
    }

    /** Decodes one layer as a mutable ARGB bitmap suitable for the current drawing engine. */
    @Throws(IOException::class)
    fun loadLayerBitmap(documentPath: String, layer: RasterLayerDto): Bitmap {
        val documentFile = ownedDocumentFile(documentPath)
        val target = resolveAsset(documentFile.parentFile, layer.bitmapAssetPath)
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = AtomicFile(target).openRead().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IOException("Cannot decode bitmap for layer '${layer.id}'")
        if (bitmap.width != layer.pixelWidth || bitmap.height != layer.pixelHeight) {
            bitmap.recycle()
            throw IOException(
                "Saved bitmap for layer '${layer.id}' is ${bitmap.width}x${bitmap.height}, " +
                    "expected ${layer.pixelWidth}x${layer.pixelHeight}"
            )
        }
        return bitmap
    }

    @Throws(IOException::class)
    fun loadLayerMask(documentPath: String, layer: RasterLayerDto): Bitmap? {
        val relativePath = layer.maskAssetPath ?: return null
        val documentFile = ownedDocumentFile(documentPath)
        val target = resolveAsset(documentFile.parentFile, relativePath)
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = AtomicFile(target).openRead().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IOException("Cannot decode mask for layer '${layer.id}'")
        if (bitmap.width != layer.pixelWidth || bitmap.height != layer.pixelHeight) {
            bitmap.recycle()
            throw IOException(
                "Saved mask for layer '${layer.id}' is ${bitmap.width}x${bitmap.height}, " +
                    "expected ${layer.pixelWidth}x${layer.pixelHeight}"
            )
        }
        return bitmap
    }

    /** Returns the future document path without creating files, for Room bookkeeping. */
    fun documentPath(projectKey: String): String {
        require(projectKey.isNotBlank()) { "projectKey cannot be blank" }
        return File(projectDirectory(projectKey), DOCUMENT_FILE_NAME).absolutePath
    }

    private fun projectDirectory(projectKey: String): File =
        File(rootDirectory, stableFileToken(projectKey))

    private fun ownedDocumentFile(documentPath: String): File {
        val root = rootDirectory.canonicalFile
        val document = File(documentPath).canonicalFile
        if (document.name != DOCUMENT_FILE_NAME || !isWithin(root, document)) {
            throw IOException("Project document path is outside the project store")
        }
        return document
    }

    private fun resolveAsset(projectDirectory: File?, relativePath: String): File {
        if (projectDirectory == null || !ProjectDocumentValidator.isSafeRelativeAssetPath(relativePath)) {
            throw IOException("Unsafe project asset path '$relativePath'")
        }
        val directory = projectDirectory.canonicalFile
        val target = File(directory, relativePath).canonicalFile
        if (!isWithin(directory, target)) {
            throw IOException("Project asset path escapes its project directory")
        }
        return target
    }

    private fun isWithin(directory: File, target: File): Boolean =
        target == directory || target.path.startsWith(directory.path + File.separator)

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create project directory: ${directory.absolutePath}")
        }
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun writeBitmapAtomically(target: File, bitmap: Bitmap) {
        val atomicFile = AtomicFile(target)
        val output = atomicFile.startWrite()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Android could not encode layer bitmap '${target.name}'")
            }
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val PROJECTS_DIRECTORY = "project_documents"
        private const val DOCUMENT_FILE_NAME = "project.json"

        fun defaultBitmapAssetPath(layerId: String): String {
            require(layerId.isNotBlank()) { "layerId cannot be blank" }
            return "layers/${stableFileToken(layerId)}.png"
        }

        fun defaultMaskAssetPath(layerId: String): String {
            require(layerId.isNotBlank()) { "layerId cannot be blank" }
            return "masks/${stableFileToken(layerId)}.png"
        }

        /** Factory for tests and maintenance tools that already have an explicitly scoped root. */
        fun inDirectory(rootDirectory: File): ProjectDocumentStore =
            ProjectDocumentStore(rootDirectory)

        private fun stableFileToken(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }
    }
}
