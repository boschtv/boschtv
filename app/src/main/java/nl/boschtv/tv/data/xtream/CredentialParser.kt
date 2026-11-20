package nl.boschtv.tv.data.xtream

import java.net.URI
import java.net.URLDecoder

/** Pulls host/username/password out of a pasted Xtream or M3U link, or "server: / user: / pass:" text. */
object CredentialParser {
    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private fun labeled(names: String) = Regex("""(?im)^\s*(?:$names)\s*[:=]\s*(\S+)\s*$""")
    private val server = labeled("server|host|url|portal")
    private val user = labeled("username|user|login|gebruikersnaam")
    private val pass = labeled("password|pass|wachtwoord")

    fun parse(text: String): Source? {
        for (m in urlRegex.findAll(text)) {
            val uri = runCatching { URI(m.value.trim()) }.getOrNull() ?: continue
            val q = uri.rawQuery?.split("&")?.mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else decode(it.substring(0, i)) to decode(it.substring(i + 1))
            }?.toMap().orEmpty()
            val u = q["username"]?.takeIf(String::isNotBlank) ?: continue
            val p = q["password"]?.takeIf(String::isNotBlank) ?: continue
            if (uri.scheme == null || uri.rawAuthority == null) continue
            return Source.of("${uri.scheme}://${uri.rawAuthority}", u, p)
        }
        val h = server.find(text)?.groupValues?.get(1) ?: return null
        val u = user.find(text)?.groupValues?.get(1) ?: return null
        val p = pass.find(text)?.groupValues?.get(1) ?: return null
        return Source.of(h, u, p)
    }

    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
}
