package nl.boschtv.tv.core

import kotlin.math.abs

/**
 * Tolerant view over a decoded JSON object. Xtream panels are sloppy about types (numbers as strings,
 * "" or [] where an object is expected), so nothing here ever throws on a type mismatch.
 */
@JvmInline
value class JsonObj(val map: Map<String, Any?>) {

    fun str(key: String): String? = when (val v = map[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        is Double -> if (v % 1.0 == 0.0 && abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Boolean -> v.toString()
        else -> null
    }

    fun long(key: String): Long? = when (val v = map[key]) {
        is Double -> v.toLong()
        is String -> v.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        else -> null
    }

    fun int(key: String): Int? = long(key)?.toInt()

    fun double(key: String): Double? = when (val v = map[key]) {
        is Double -> v
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun bool(key: String): Boolean = when (val v = map[key]) {
        is Boolean -> v
        is Double -> v != 0.0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> false
    }

    fun obj(key: String): JsonObj? = map[key].asObj()

    fun list(key: String): List<Any?> = map[key] as? List<Any?> ?: emptyList()

    /** A value that is either a string or a list of strings; returns the first non-blank one. */
    fun firstString(key: String): String? = when (val v = map[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is List<*> -> v.firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): JsonObj? = (this as? Map<String, Any?>)?.let(::JsonObj)
