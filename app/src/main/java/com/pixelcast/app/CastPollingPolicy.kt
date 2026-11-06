package com.pixelcast.app

/**
 * Cast position stays accurate via elapsedRealtime interpolation.
 * Poll a playing TV every second for prompt state changes; paused or stopped
 * sessions only require a five-second remote-state/volume refresh.
 */
internal object CastPollingPolicy {
    fun delaySeconds(active: Boolean, playing: Boolean): Long? = when {
        !active -> null
        playing -> 1L
        else -> 5L
    }
}
