package com.pixelcast.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/** A DLNA media renderer (e.g. a Samsung TV) that we can send media to. */
data class Renderer(val name: String, val controlUrl: String)

/**
 * Finds DLNA renderers on the Wi-Fi network using SSDP (UPnP discovery).
 * Blocking — call from a background thread.
 */
object SsdpDiscovery {
    private const val TAG = "SsdpDiscovery"
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private val SEARCH_TARGETS = listOf(
        AvTransport.SERVICE_TYPE,
        "urn:schemas-upnp-org:device:MediaRenderer:1",
    )

    fun discover(context: Context, timeoutMs: Long = 4000): List<Renderer> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("tvcast-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 500
                val group = InetAddress.getByName(SSDP_ADDR)
                for (st in SEARCH_TARGETS) {
                    val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n").toByteArray()
                    // UDP is lossy; send each search twice.
                    repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT)) }
                }
                val buf = ByteArray(8192)
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    header(text, "LOCATION")?.let { locations += it }
                }
            }
        } finally {
            lock.release()
        }
        return locations
            .mapNotNull { loc ->
                runCatching { describe(loc) }
                    .onFailure { Log.w(TAG, "Could not read $loc", it) }
                    .getOrNull()
            }
            .distinctBy { it.controlUrl }
    }

    private fun header(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Reads the device description XML and finds the AVTransport control URL. */
    private fun describe(location: String): Renderer? {
        val xml = httpGet(location)
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var friendlyName: String? = null
        var urlBase: String? = null
        var inService = false
        var serviceType = ""
        var controlUrl = ""
        var avTransportControl: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "friendlyName" -> parser.nextText().trim().let { if (friendlyName == null) friendlyName = it }
                    "URLBase" -> urlBase = parser.nextText().trim()
                    "service" -> {
                        inService = true; serviceType = ""; controlUrl = ""
                    }
                    "serviceType" -> if (inService) serviceType = parser.nextText().trim()
                    "controlURL" -> if (inService) controlUrl = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "service") {
                    if (avTransportControl == null && serviceType.contains("AVTransport")) {
                        avTransportControl = controlUrl
                    }
                    inService = false
                }
            }
            event = parser.next()
        }
        val control = avTransportControl ?: return null
        val base = URL(urlBase?.takeIf { it.isNotEmpty() } ?: location)
        return Renderer(
            name = friendlyName ?: base.host,
            controlUrl = URL(base, control).toString(),
        )
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 4000
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
