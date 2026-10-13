package com.pixelcast.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Tiny HTTP server that streams one local file (a content:// Uri) to the TV.
 * Supports Range requests so the TV can seek, and sends the DLNA headers Samsung expects.
 */
class MediaHttpServer(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    private class Served(val uri: Uri, val mime: String, val path: String)

    @Volatile private var served: Served? = null
    @Volatile private var server: ServerSocket? = null

    /** Starts serving [uri] and returns the URL the TV should load. */
    @Synchronized
    fun serve(uri: Uri, mime: String, tvHost: String): String {
        val socket = server ?: ServerSocket(0).also { s ->
            server = s
            thread(isDaemon = true, name = "tvcast-http") { acceptLoop(s) }
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        // A fresh path per cast stops the TV from reusing a cached earlier file.
        val path = "/media/${UUID.randomUUID()}.$ext"
        served = Served(uri, mime, path)
        return "http://${localIpFacing(tvHost)}:${socket.localPort}$path"
    }

    @Synchronized
    fun shutdown() {
        served = null
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            thread(isDaemon = true, name = "tvcast-http-conn") { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 20_000
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()

                val current = served
                if (current == null || path != current.path || (method != "GET" && method != "HEAD")) {
                    out.write(simple("404 Not Found"))
                    out.flush()
                    return
                }
                stream(current, method, headers["range"], out)
            }
        } catch (e: IOException) {
            // Normal when the TV seeks or stops: it just drops the connection.
            Log.d("MediaHttpServer", "connection ended: ${e.message}")
        }
    }

    private fun stream(item: Served, method: String, range: String?, out: BufferedOutputStream) {
        val afd = resolver.openAssetFileDescriptor(item.uri, "r") ?: run {
            out.write(simple("404 Not Found")); out.flush(); return
        }
        afd.use {
            val total = if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
            var start = 0L
            var end = total - 1
            var partial = false
            range?.let { r ->
                Regex("bytes=(\\d*)-(\\d*)").find(r)?.let { m ->
                    val a = m.groupValues[1]
                    val b = m.groupValues[2]
                    if (a.isNotEmpty()) {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                    } else if (b.isNotEmpty()) {
                        start = maxOf(0L, total - b.toLong()) // "bytes=-500": last 500 bytes
                    }
                    partial = true
                }
            }
            if (total <= 0 || start >= total || end < start) {
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.flush()
                return
            }
            val length = end - start + 1
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${item.mime}\r\n")
                append("Content-Length: $length\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: ${AvTransport.DLNA_FEATURES}\r\n")
                append("Server: Android UPnP/1.0 DLNADOC/1.50 TvCast/0.1\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (method == "GET") {
                afd.createInputStream().use { ins ->
                    ins.channel.position(afd.startOffset + start)
                    val buf = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
            out.flush()
        }
    }

    private fun simple(status: String) =
        "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    companion object {
        /** The phone's IP on the interface that routes to the TV (no packets are sent). */
        fun localIpFacing(host: String): String =
            DatagramSocket().use {
                it.connect(InetAddress.getByName(host), 1900)
                it.localAddress.hostAddress ?: throw IOException("No network route to TV")
            }
    }
}
