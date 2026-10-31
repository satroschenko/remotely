package app.remotely.data.repo

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.encoding.Base64

/**
 * Zala's archive (TVOD) manifest of a programme is sometimes cut short — a two-hour programme comes back as
 * 12 or 24 minutes, differently on each server — while the channel's timeshift stream, the one the official
 * client seeks in, still has all of it. The timeshift stream answers with about 100 minutes from the moment
 * asked for (`playseek`), so a programme is put together from one or more such windows: a static manifest
 * with a period per window, where position 0 is the programme start, just like a recording.
 */
object ArchiveManifest {
    /** One timeshift answer: the manifest and the address it came from (its segments are relative to it). */
    data class Window(val url: String, val mpd: String)

    private val PLAYSEEK = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    private val ISO_DURATION = Regex("""^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$""")

    /** The live address turned into the timeshift one from [fromMs], as the official client does. */
    fun timeshiftUrl(liveUrl: String, fromMs: Long): String? {
        if (!liveUrl.contains("live_hls.zte.com")) return null
        return liveUrl.replace("live_hls.zte.com", "tstv_hls.zte.com") + "&playseek=" + PLAYSEEK.format(Instant.ofEpochMilli(fromMs)) + "-"
    }

    /** `mediaPresentationDuration` in ms; null when the manifest has none or is not one. */
    fun durationMs(mpd: String): Long? = runCatching {
        parseIsoDurationMs(parse(mpd).documentElement.getAttribute("mediaPresentationDuration"))
    }.getOrNull()

    /** The end of the last segment of a timeshift window, epoch ms: where the next window is asked from. */
    fun windowEndMs(mpd: String): Long? = runCatching {
        val root = parse(mpd).documentElement
        val ast = Instant.parse(root.getAttribute("availabilityStartTime")).toEpochMilli()
        val tpl = root.elements("SegmentTemplate").first()
        val scale = tpl.getAttribute("timescale").toLongOrNull() ?: 1L
        segments(tpl).lastOrNull()?.let { ast + (it.first + it.second) * 1000 / scale }
    }.getOrNull()

    /** A downloaded manifest given its own address as base, so it can be handed over as a data: URI. */
    fun withBase(mpd: String, url: String): String? = runCatching {
        val doc = parse(mpd)
        val root = doc.documentElement
        root.insertBefore(baseUrl(doc, url), root.firstChild)
        write(doc)
    }.getOrNull()

    /** Static manifest of [fromMs]..[toMs] from timeshift windows in order; null when they do not cover it. */
    fun fromTimeshift(windows: List<Window>, fromMs: Long, toMs: Long): String? = runCatching {
        var out: Document? = null
        var coveredMs = fromMs
        var index = 0
        for (w in windows) {
            val doc = parse(w.mpd)
            val root = doc.documentElement
            val ast = Instant.parse(root.getAttribute("availabilityStartTime")).toEpochMilli()
            val period = root.elements("Period").firstOrNull() ?: continue
            val periodStartMs = if (index == 0) 0L else coveredMs - fromMs
            var periodEndMs = coveredMs
            val templates = period.elements("SegmentTemplate")
            if (templates.isEmpty()) continue
            for (tpl in templates) {
                val scale = tpl.getAttribute("timescale").toLongOrNull() ?: 1L
                fun epochMs(t: Long) = ast + t * 1000 / scale
                // Each segment once: from where the previous window ended up to the programme end.
                val kept = segments(tpl).filter { (t, d) -> epochMs(t + d) > coveredMs + 500 && epochMs(t) < toMs }
                if (kept.isEmpty()) continue
                periodEndMs = maxOf(periodEndMs, kept.last().let { (t, d) -> epochMs(t + d) })
                tpl.setAttribute("presentationTimeOffset", ((fromMs + periodStartMs - ast) * scale / 1000).toString())
                tpl.removeAttribute("startNumber")
                val timeline = tpl.elements("SegmentTimeline").first()
                while (timeline.firstChild != null) timeline.removeChild(timeline.firstChild)
                for ((t, d) in kept) {
                    timeline.appendChild(doc.createElement("S").apply {
                        setAttribute("t", t.toString())
                        setAttribute("d", d.toString())
                    })
                }
            }
            if (periodEndMs <= coveredMs) continue
            period.setAttribute("id", index.toString())
            period.setAttribute("start", iso(periodStartMs))
            period.insertBefore(baseUrl(doc, w.url), period.firstChild)
            coveredMs = minOf(periodEndMs, toMs)
            if (out == null) {
                out = doc
                for (a in listOf("availabilityStartTime", "publishTime", "minimumUpdatePeriod", "timeShiftBufferDepth")) root.removeAttribute(a)
                root.setAttribute("type", "static")
            } else {
                val target = out.documentElement
                target.appendChild(out.importNode(period, true))
            }
            index++
            if (coveredMs >= toMs) break
        }
        val doc = out ?: return null
        if (coveredMs - fromMs <= 0) return null
        doc.documentElement.setAttribute("mediaPresentationDuration", iso(coveredMs - fromMs))
        write(doc)
    }.getOrNull()

    /** Base64: ExoPlayer splits a data: URI at its comma after decoding, and a manifest is full of commas. */
    fun dataUri(mpd: String): String =
        "data:application/dash+xml;base64," + Base64.encode(mpd.toByteArray(Charsets.UTF_8))

    /** `<S t d r>` runs as (start, duration) pairs in timescale units. */
    private fun segments(tpl: Element): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var next = 0L
        for (s in tpl.elements("S")) {
            val d = s.getAttribute("d").toLongOrNull()?.takeIf { it > 0 } ?: continue
            var t = s.getAttribute("t").toLongOrNull() ?: next
            val repeat = (s.getAttribute("r").toLongOrNull() ?: 0L).coerceIn(0, 100_000)
            repeat((repeat + 1).toInt()) {
                out += t to d
                t += d
            }
            next = t
        }
        return out
    }

    private fun parseIsoDurationMs(text: String): Long? {
        val m = ISO_DURATION.matchEntire(text.trim()) ?: return null
        val (h, min, s) = m.destructured
        return ((h.toDoubleOrNull() ?: 0.0) * 3_600_000 + (min.toDoubleOrNull() ?: 0.0) * 60_000 + (s.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

    private fun iso(ms: Long) = "PT%d.%03dS".format(ms / 1000, ms % 1000)

    private fun baseUrl(doc: Document, url: String): Element =
        doc.createElement("BaseURL").apply { textContent = url.substringBefore('?').substringBeforeLast('/') + "/" }

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun parse(mpd: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // No DTDs or external entities from a server's answer.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(mpd.byteInputStream())

    private fun write(doc: Document): String {
        val w = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(w))
        return w.toString()
    }
}
