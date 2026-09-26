package com.procreate.android.export.dxf

import java.util.Locale

/**
 * Low-level ASCII DXF emitter: every group code and its value get their own line, exactly as the
 * DXF format requires (a "group" is a code line followed by a value line, never combined).
 *
 * All floating-point values go through [Locale.US] regardless of the device's own locale - a
 * device set to Arabic (or any locale using "," as the decimal separator) would otherwise silently
 * corrupt every coordinate written here (12.345 becoming 12,345, which most DXF readers choke on
 * or misparse as two separate numbers).
 */
class DxfWriter {
    private val sb = StringBuilder()

    fun code(code: Int): DxfWriter {
        sb.append(code).append('\n')
        return this
    }

    fun str(v: String): DxfWriter {
        sb.append(v).append('\n')
        return this
    }

    fun int(v: Int): DxfWriter {
        sb.append(v).append('\n')
        return this
    }

    /** DXF floats are always written with a fixed number of decimals in Locale.US. */
    fun double(v: Double): DxfWriter {
        sb.append(String.format(Locale.US, "%.6f", v)).append('\n')
        return this
    }

    fun pair(code: Int, v: String): DxfWriter { code(code); return str(v) }
    fun pair(code: Int, v: Int): DxfWriter { code(code); return int(v) }
    fun pair(code: Int, v: Double): DxfWriter { code(code); return double(v) }

    fun result(): String = sb.toString()
}

/** Hands out unique hex handles (group 5) for every table entry / block / entity - a hand-rolled
 * writer that reuses or skips handles is exactly what triggers AutoCAD's "drawing was repaired"
 * banner on open. */
class DxfHandleCounter(start: Int = 0x30) {
    private var next = start
    fun nextHandle(): String {
        val h = next.toString(16).uppercase()
        next += 1
        return h
    }
    /** The final value to write into $HANDSEED - must be higher than every handle actually used. */
    fun seed(): String = next.toString(16).uppercase()
}
