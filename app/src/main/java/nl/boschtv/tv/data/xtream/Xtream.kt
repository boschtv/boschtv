package nl.boschtv.tv.data.xtream

import com.squareup.moshi.JsonReader
import nl.boschtv.tv.core.JsonObj
import nl.boschtv.tv.core.asObj
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException

const val USER_AGENT = "BoschTV/2.0 (Android TV)"

/** The IPTV line the user logged in with. */
data class Source(val host: String = "", val username: String = "", val password: String = "") {
    val isConfigured get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    fun live(streamId: Long, ext: String) = "$host/live/$username/$password/$streamId.$ext"
    fun movie(streamId: Long, ext: String?) = "$host/movie/$username/$password/$streamId.${ext.orEmpty().ifBlank { "mp4" }}"
    /** Catch-up: a programme that already started (or ended), as a seekable stream. Times are in the provider's zone. */
    fun timeshift(streamId: Long, startMs: Long, minutes: Int): String {
        val start = java.time.Instant.ofEpochMilli(startMs).atZone(java.time.ZoneId.of("Europe/Amsterdam"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"))
        return "$host/timeshift/$username/$password/$minutes/$start/$streamId.m3u8"
    }

    fun episode(episodeId: String, ext: String?) = "$host/series/$username/$password/$episodeId.${ext.orEmpty().ifBlank { "mp4" }}"

    companion object {
        fun of(host: String, username: String, password: String) =
            Source(normalizeHost(host), username.trim(), password.trim())

        fun normalizeHost(raw: String): String {
            var h = raw.trim().removeSuffix("/")
            if (h.isNotEmpty() && !h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
            return h
        }
    }
}

data class Account(
    val active: Boolean,
    val message: String,
    val expiresAt: Long?,
    val maxConnections: Int,
    val trial: Boolean,
    val formats: List<String>,
)

class AuthException(message: String) : IOException(message)

data class XCategory(val id: String, val name: String)

/** Xtream Codes `player_api.php` client. Large lists are streamed item by item, never held as one blob. */
class XtreamClient(private val http: OkHttpClient) {

    private fun url(src: Source, action: String?, vararg extra: Pair<String, String>): HttpUrl {
        val b = "${src.host}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", src.username)
            .addQueryParameter("password", src.password)
        if (action != null) b.addQueryParameter("action", action)
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun <T> get(url: HttpUrl, empty: T, block: (JsonReader) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server gaf fout ${resp.code}")
            val body = resp.body ?: return empty
            val reader = JsonReader.of(body.source()).apply { isLenient = true }
            return try {
                block(reader)
            } catch (_: EOFException) {
                empty
            }
        }
    }

    fun account(src: Source): Account {
        val root = get(url(src, null), null) { it.readJsonValue().asObj() }
        val u = root?.obj("user_info") ?: throw AuthException("Geen geldig antwoord van de server")
        if (u.int("auth") == 0) throw AuthException("Gebruikersnaam of wachtwoord klopt niet")
        val status = u.str("status").orEmpty().lowercase()
        val exp = u.long("exp_date")
        val expired = status in setOf("expired", "disabled", "banned") ||
            (exp != null && exp * 1000 <= System.currentTimeMillis())
        return Account(
            active = !expired,
            message = if (expired) "Abonnement is verlopen" else "Actief",
            expiresAt = exp?.times(1000),
            maxConnections = u.int("max_connections") ?: 1,
            trial = u.bool("is_trial"),
            formats = u.list("allowed_output_formats").filterIsInstance<String>(),
        )
    }

    fun categories(src: Source, action: String): List<XCategory> {
        val out = ArrayList<XCategory>()
        forEach(src, action) { o ->
            val id = o.str("category_id") ?: return@forEach
            out += XCategory(id, o.str("category_name") ?: return@forEach)
        }
        return out
    }

    /** Streams a top-level JSON array (or id-keyed object) and hands each element to [onItem]. */
    fun forEach(src: Source, action: String, vararg extra: Pair<String, String>, onItem: (JsonObj) -> Unit) {
        get(url(src, action, *extra), Unit) { r ->
            when (r.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) r.readJsonValue().asObj()?.let(onItem)
                    r.endArray()
                }
                JsonReader.Token.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) { r.nextName(); r.readJsonValue().asObj()?.let(onItem) }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
    }

    fun obj(src: Source, action: String, vararg extra: Pair<String, String>): JsonObj? =
        get(url(src, action, *extra), null) { it.readJsonValue().asObj() }
}
