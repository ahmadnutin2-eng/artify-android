package com.procreate.android.brushes

/**
 * A reader for Apple's binary property list, enough of it to get at what a Procreate brush says
 * about itself.
 *
 * Every brush in a .brushset carries a `Brush.archive`: an NSKeyedArchiver bplist holding the
 * brush's name and its whole settings table - `maxSize`, `paintOpacity`, `plotSpacing`,
 * `grainDepth`, `taperSize`, `shapeRotation` and the rest. The importer used to treat that file as
 * an opaque marker and throw it away, so a pack of ninety brushes arrived as ninety entries called
 * "imported brush 1" through "imported brush 90", each with invented defaults. The names are in
 * there, plainly, and so are the numbers.
 *
 * The format is public and fixed: an eight-byte header, a table of object offsets at the end, and
 * typed objects in between. Nothing here guesses. Anything this reader cannot make sense of comes
 * back null and the caller falls back to what it did before, so a malformed or unfamiliar archive
 * costs a name rather than an import.
 */
object BinaryPlist {

    private const val HEADER = "bplist00"
    private const val TRAILER_SIZE = 32

    /**
     * A keyed-archiver reference: an index into the `$objects` array, not into the file's own
     * object table. Confusing the two is the mistake this format invites - the two levels look
     * alike and resolving against the wrong one yields the archive's own bookkeeping strings
     * instead of the brush's data.
     */
    @JvmInline
    value class Uid(val index: Int)

    /** A plain file-level reference, as arrays and dictionaries store their members. */
    @JvmInline
    value class RawRef(val index: Int)

    /**
     * Every object in the archive, in table order.
     *
     * NSKeyedArchiver flattens an object graph into this one array and refers to entries by index,
     * so reading it whole and resolving references afterwards is simpler, and cheaper, than walking
     * the graph from its root.
     */
    class Document(private val raw: List<Any?>, private val archived: List<Any?>) {

        /** What a file-level reference points at. */
        private fun deref(reference: Any?): Any? = when (reference) {
            is RawRef -> raw.getOrNull(reference.index)
            else -> reference
        }

        /** Follows a keyed-archiver reference to its object; anything else is returned unchanged. */
        fun resolve(value: Any?): Any? = when (value) {
            is Uid -> archived.getOrNull(value.index)
            else -> value
        }

        /**
         * The value stored under [key] in the first archived dictionary that has it.
         *
         * A brush archive is one object graph with one brush in it, so the first match is the
         * brush's own. Searching rather than walking down from the root keeps this independent of
         * how deeply NSKeyedArchiver happened to nest things.
         */
        fun findValue(key: String): Any? {
            for (entry in archived) {
                val dictionary = entry as? Map<*, *> ?: continue
                for ((keyRef, valueRef) in dictionary) {
                    if (deref(keyRef) != key) continue
                    return resolve(deref(valueRef))
                }
            }
            return null
        }

        fun findString(key: String): String? = (findValue(key) as? String)?.takeIf { it.isNotBlank() }

        fun findFloat(key: String): Float? = when (val value = findValue(key)) {
            is Double -> value.toFloat()
            is Long -> value.toFloat()
            is Int -> value.toFloat()
            else -> null
        }
    }

    fun parse(bytes: ByteArray): Document? {
        if (bytes.size < HEADER.length + TRAILER_SIZE) return null
        for (i in HEADER.indices) {
            if (bytes[i] != HEADER[i].code.toByte()) return null
        }
        return try {
            read(bytes)
        } catch (_: IndexOutOfBoundsException) {
            // A truncated archive is a missing name, not a failed import.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun read(bytes: ByteArray): Document? {
        val trailer = bytes.size - TRAILER_SIZE
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val refSize = bytes[trailer + 7].toInt() and 0xFF
        val count = readBigEndian(bytes, trailer + 8, 8).toInt()
        val tableOffset = readBigEndian(bytes, trailer + 24, 8).toInt()
        if (offsetSize !in 1..8 || refSize !in 1..8 || count < 0 || count > 1_000_000) return null

        val offsets = IntArray(count) { index ->
            readBigEndian(bytes, tableOffset + index * offsetSize, offsetSize).toInt()
        }
        val raw = offsets.map { offset -> readObject(bytes, offset, refSize) }

        // The archived graph lives in the header's "$objects" array. Everything a brush says about
        // itself is addressed by position in that array, so it has to be assembled before any
        // reference can be followed.
        val objectsArray = raw.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { dictionary ->
                dictionary.entries.firstOrNull { (key, _) ->
                    (key as? RawRef)?.let { raw.getOrNull(it.index) } == "\$objects"
                }?.value
            }
            .mapNotNull { reference -> (reference as? RawRef)?.let { raw.getOrNull(it.index) } }
            .firstOrNull() as? List<*>
            ?: return null

        val archived = objectsArray.map { reference ->
            (reference as? RawRef)?.let { raw.getOrNull(it.index) }
        }
        return Document(raw, archived)
    }

    private fun readObject(bytes: ByteArray, offset: Int, refSize: Int): Any? {
        val marker = bytes[offset].toInt() and 0xFF
        val type = marker ushr 4
        val nibble = marker and 0x0F
        return when (type) {
            0x0 -> when (nibble) {
                0x8 -> false
                0x9 -> true
                else -> null
            }
            0x1 -> readBigEndian(bytes, offset + 1, 1 shl nibble)
            0x2 -> when (1 shl nibble) {
                4 -> Float.fromBits(readBigEndian(bytes, offset + 1, 4).toInt()).toDouble()
                8 -> Double.fromBits(readBigEndian(bytes, offset + 1, 8))
                else -> null
            }
            // Dates are stored as a double but are of no use here; reading them keeps the table
            // aligned with reality rather than silently mapping them to null.
            0x3 -> Double.fromBits(readBigEndian(bytes, offset + 1, 8))
            0x4 -> {
                val (length, start) = readLength(bytes, offset, nibble)
                bytes.copyOfRange(start, start + length)
            }
            0x5 -> {
                val (length, start) = readLength(bytes, offset, nibble)
                String(bytes, start, length, Charsets.US_ASCII)
            }
            0x6 -> {
                val (length, start) = readLength(bytes, offset, nibble)
                String(bytes, start, length * 2, Charsets.UTF_16BE)
            }
            0x8 -> Uid(readBigEndian(bytes, offset + 1, nibble + 1).toInt())
            0xA, 0xC -> {
                val (length, start) = readLength(bytes, offset, nibble)
                List(length) { index ->
                    RawRef(readBigEndian(bytes, start + index * refSize, refSize).toInt())
                }
            }
            0xD -> {
                val (length, start) = readLength(bytes, offset, nibble)
                val map = LinkedHashMap<Any?, Any?>(length)
                for (index in 0 until length) {
                    val key = RawRef(readBigEndian(bytes, start + index * refSize, refSize).toInt())
                    val value = RawRef(
                        readBigEndian(bytes, start + (length + index) * refSize, refSize).toInt()
                    )
                    map[key] = value
                }
                map
            }
            else -> null
        }
    }

    /**
     * An object's element count and where its body starts.
     *
     * A low nibble of 0xF means the real count did not fit in four bits and is stored as an integer
     * object immediately after the marker.
     */
    private fun readLength(bytes: ByteArray, offset: Int, nibble: Int): Pair<Int, Int> {
        if (nibble != 0x0F) return nibble to (offset + 1)
        val sizeMarker = bytes[offset + 1].toInt() and 0xFF
        require(sizeMarker ushr 4 == 0x1) { "expected an integer length" }
        val width = 1 shl (sizeMarker and 0x0F)
        val length = readBigEndian(bytes, offset + 2, width).toInt()
        return length to (offset + 2 + width)
    }

    private fun readBigEndian(bytes: ByteArray, offset: Int, width: Int): Long {
        var value = 0L
        for (i in 0 until width) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
