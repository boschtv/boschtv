package nl.boschtv.tv.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory

/** One programme from an XMLTV guide file. [channel] is the guide's own channel id ("npo1.nl"). */
data class XmlTvProgramme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String?)

/**
 * Reads an XMLTV programme guide: the one file in which a provider lists every programme of every
 * channel. Read as a stream, programme by programme, because the file can be a hundred megabytes.
 */
object XmlTv {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val OFFSET = Regex("""^[+-]\d{4}$""")

    /** "20261122203000 +0100" as clock time in milliseconds; without a zone the time is taken as UTC. */
    fun time(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.length < 14) return null
        val local = runCatching { LocalDateTime.parse(t.substring(0, 14), STAMP) }.getOrNull() ?: return null
        val zone = t.substring(14).trim().takeIf { OFFSET.matches(it) }?.let { ZoneOffset.of(it.substring(0, 3) + ":" + it.substring(3)) } ?: ZoneOffset.UTC
        return local.toInstant(zone).toEpochMilli()
    }

    /** Hands every programme in [input] to [onProgramme], unless [wanted] says its channel is of no interest. */
    fun read(input: InputStream, wanted: (String) -> Boolean = { true }, onProgramme: (XmlTvProgramme) -> Unit) {
        val factory = SAXParserFactory.newInstance()
        // A guide file is plain data: nothing outside the file is ever fetched for it.
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newSAXParser().parse(input, object : DefaultHandler() {
            private var channel: String? = null
            private var start = 0L
            private var stop = 0L
            private var title: String? = null
            private var description: String? = null
            private var text: StringBuilder? = null

            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (qName) {
                    "programme" -> {
                        val ch = a.getValue("channel")?.takeIf { it.isNotBlank() && wanted(it) }
                        val from = time(a.getValue("start")); val to = time(a.getValue("stop"))
                        channel = if (ch != null && from != null && to != null && to > from) ch else null
                        start = from ?: 0; stop = to ?: 0; title = null; description = null
                    }
                    // The first title and description count: the file lists the main language first.
                    "title" -> if (channel != null && title == null) text = StringBuilder()
                    "desc" -> if (channel != null && description == null) text = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, from: Int, length: Int) { text?.let { if (it.length < 600) it.append(ch, from, length) } }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "title" -> text?.let { title = it.toString().trim().ifEmpty { null }; text = null }
                    "desc" -> text?.let { description = it.toString().trim().ifEmpty { null }; text = null }
                    "programme" -> {
                        val ch = channel; val name = title
                        if (ch != null && name != null) onProgramme(XmlTvProgramme(ch, start, stop, name, description))
                        channel = null; text = null
                    }
                }
            }
        })
    }
}
