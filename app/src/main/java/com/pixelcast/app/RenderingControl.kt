package com.pixelcast.app

/**
 * Minimal UPnP RenderingControl client: reads and changes the TV's volume and mute.
 * All calls are blocking — run them off the main thread.
 */
class RenderingControl(private val controlUrl: String) {

    /** The TV's volume (0–[MAX_VOLUME]), or null if it didn't say. */
    fun volume(): Int? =
        AvTransport.tag(call("GetVolume", MASTER), "CurrentVolume")?.toIntOrNull()

    fun setVolume(volume: Int) {
        call("SetVolume", "$MASTER<DesiredVolume>${volume.coerceIn(0, MAX_VOLUME)}</DesiredVolume>")
    }

    fun muted(): Boolean? =
        AvTransport.tag(call("GetMute", MASTER), "CurrentMute")
            ?.let { it == "1" || it.equals("true", ignoreCase = true) }

    fun setMuted(muted: Boolean) {
        call("SetMute", "$MASTER<DesiredMute>${if (muted) 1 else 0}</DesiredMute>")
    }

    private fun call(action: String, args: String): String =
        AvTransport.soap(controlUrl, SERVICE_TYPE, action, args)

    companion object {
        const val SERVICE_TYPE = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val MAX_VOLUME = 100
        private const val MASTER = "<Channel>Master</Channel>"
    }
}
