package nl.boschtv.tv.setup

import nl.boschtv.tv.data.xtream.CredentialParser
import nl.boschtv.tv.data.xtream.Source
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tiny one-page web server so the IPTV login can be typed or pasted on a phone instead of with the
 * remote. Only runs while the login screen is open, on the local network.
 */
class SetupServer(private val onSubmit: (Source) -> Unit) {
    private var socket: ServerSocket? = null
    val port = 8765

    val address: String? get() = localIp()?.let { "http://$it:$port" }

    fun start() {
        if (socket != null) return
        val server = runCatching { ServerSocket(port) }.getOrNull() ?: return
        socket = server
        thread(isDaemon = true, name = "setup-server") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::handle) } }
            }
        }
    }

    fun stop() { runCatching { socket?.close() }; socket = null }

    private fun handle(s: Socket) {
        s.soTimeout = 8000
        val input = s.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        var message = ""
        if (request.startsWith("POST")) {
            val buf = CharArray(length.coerceIn(0, 16_384))
            var read = 0
            while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            val form = String(buf, 0, read).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val src = CredentialParser.parse(form["link"].orEmpty())
                ?: Source.of(form["host"].orEmpty(), form["user"].orEmpty(), form["pass"].orEmpty())
            message = if (src.isConfigured) { onSubmit(src); "Verstuurd. Kijk op je tv." } else "Vul een link in, of server, gebruikersnaam en wachtwoord."
        }
        val body = page(message).toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body); flush()
        }
    }

    private fun page(message: String) = """
<!doctype html><html lang="nl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BoschTV instellen</title>
<style>body{font-family:system-ui,sans-serif;background:#0A0A24;color:#F2F5FC;margin:0;padding:24px;max-width:480px;margin:auto}
h1{font-size:24px}label{display:block;margin:16px 0 6px;color:#C3C8E0;font-size:14px}
input,textarea{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #2A2960;background:#15143F;color:#F2F5FC;font-size:16px}
button{margin-top:24px;width:100%;padding:16px;border:0;border-radius:28px;background:#2979FF;color:#fff;font-size:17px;font-weight:700}
p{color:#9096B5;line-height:1.5}.msg{color:#7FB0FF;font-weight:600}</style>
<h1>BoschTV instellen</h1><p class="msg">$message</p>
<form method="post" action="/"><label for="link">Plak je IPTV-link (Xtream of M3U)</label>
<textarea id="link" name="link" rows="3" autocapitalize="off"></textarea>
<p>Of vul de gegevens los in:</p>
<label for="host">Server</label><input id="host" name="host" placeholder="http://voorbeeld.nl" autocapitalize="off">
<label for="user">Gebruikersnaam</label><input id="user" name="user" autocapitalize="off">
<label for="pass">Wachtwoord</label><input id="pass" name="pass" autocapitalize="off">
<button type="submit">Naar de tv sturen</button></form></html>"""

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
