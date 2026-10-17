package com.eyecad.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Locale-independent number formatting for common code (no String.format outside the JVM). */
internal object Format {
    fun num(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val scale = 10.0.pow(decimals)
        val scaled = (abs(v) * scale).roundToLong()
        if (scaled == 0L) return "0"
        val intPart = scaled / scale.toLong()
        val frac = (scaled % scale.toLong()).toString().padStart(decimals, '0').trimEnd('0')
        val sign = if (v < 0) "-" else ""
        return if (frac.isEmpty()) "$sign$intPart" else "$sign$intPart.$frac"
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
