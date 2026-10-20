package com.pixelcast.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal UPnP AVTransport client: tells the TV what to play and controls playback.
 * All calls are blocking — run them off the main thread.
 */
class AvTransport(private val controlUrl: String) {

    val host: String get() = URL(controlUrl).host

    /** [subtitleUrl] is an optional .srt file the TV shows over the video. */
    fun setUri(mediaUrl: String, title: String, mime: String, subtitleUrl: String? = null) {
        val metadata = didl(mediaUrl, title, mime, subtitleUrl)
        call(
            "SetAVTransportURI",
            "<CurrentURI>${escape(mediaUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>",
        )
    }

    fun play() = call("Play", "<Speed>1</Speed>").let { }
    fun pause() = call("Pause").let { }
    fun stop() = call("Stop").let { }

    fun seek(positionMs: Long) =
        call("Seek", "<Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>").let { }

    /** Returns e.g. PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT. */
    fun transportState(): String =
        tag(call("GetTransportInfo"), "CurrentTransportState") ?: "UNKNOWN"

    /** Returns (positionMs, durationMs); either may be null if the TV doesn't know yet. */
    fun positionInfo(): Pair<Long?, Long?> {
        val response = call("GetPositionInfo")
        return parseTime(tag(response, "RelTime")) to parseTime(tag(response, "TrackDuration"))
    }

    private fun call(action: String, args: String = ""): String =
        soap(controlUrl, SERVICE_TYPE, action, args)

    companion object {
        const val SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"

        // OP=01: byte-range seeking supported. FLAGS: streaming transfer mode, DLNA 1.5.
        const val DLNA_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        fun didl(url: String, title: String, mime: String, subtitleUrl: String? = null): String {
            val upnpClass = when {
                mime.startsWith("audio") -> "object.item.audioItem.musicTrack"
                mime.startsWith("image") -> "object.item.imageItem.photo"
                else -> "object.item.videoItem"
            }
            val sub = subtitleUrl?.let(::escape)
            return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
                (if (sub != null) """xmlns:sec="http://www.sec.co.kr/" xmlns:pv="http://www.pv.com/pvns/" """ else "") +
                """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
                """<item id="0" parentID="-1" restricted="1">""" +
                "<dc:title>${escape(title)}</dc:title>" +
                "<upnp:class>$upnpClass</upnp:class>" +
                """<res protocolInfo="http-get:*:$mime:$DLNA_FEATURES"""" +
                // pv:* is how many non-Samsung TVs (e.g. LG) find the subtitles.
                (if (sub != null) """ pv:subtitleFileType="srt" pv:subtitleFileUri="$sub"""" else "") +
                ">${escape(url)}</res>" +
                // Samsung TVs read the subtitles from sec:CaptionInfo(Ex).
                (if (sub != null) {
                    """<res protocolInfo="http-get:*:text/srt:*">$sub</res>""" +
                        """<sec:CaptionInfoEx sec:type="srt">$sub</sec:CaptionInfoEx>""" +
                        """<sec:CaptionInfo sec:type="srt">$sub</sec:CaptionInfo>"""
                } else "") +
                "</item></DIDL-Lite>"
        }

        /** Sends one UPnP SOAP action and returns the response body. Blocking. */
        fun soap(controlUrl: String, serviceType: String, action: String, args: String = ""): String {
            val body = """<?xml version="1.0" encoding="utf-8"?>""" +
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
                """<u:$action xmlns:u="$serviceType"><InstanceID>0</InstanceID>$args</u:$action>""" +
                "</s:Body></s:Envelope>"

            val conn = URL(controlUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 6000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
                conn.setRequestProperty("Connection", "close")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val reason = tag(text, "errorDescription") ?: tag(text, "errorCode") ?: ""
                    throw IOException("TV rejected $action (HTTP $code) $reason".trim())
                }
                return text
            } finally {
                conn.disconnect()
            }
        }

        fun escape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()

        fun formatTime(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            return "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
        }

        /** Parses "H:MM:SS" or "H:MM:SS.mmm"; returns null for NOT_IMPLEMENTED / garbage. */
        fun parseTime(value: String?): Long? {
            val parts = value?.split(":") ?: return null
            if (parts.size != 3) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val s = parts[2].toDoubleOrNull() ?: return null
            return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
        }
    }
}
