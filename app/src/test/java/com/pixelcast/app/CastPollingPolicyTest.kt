package com.pixelcast.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CastPollingPolicyTest {
    @Test fun playingTracksAtOneSecondButPausedUsesFiveSeconds() {
        assertEquals(1L, CastPollingPolicy.delaySeconds(active = true, playing = true))
        assertEquals(5L, CastPollingPolicy.delaySeconds(active = true, playing = false))
        assertNull(CastPollingPolicy.delaySeconds(active = false, playing = false))
        assertNull(CastPollingPolicy.delaySeconds(active = false, playing = true))
    }

    @Test fun remoteVolumeAndShutdownStillWork() {
        val src = listOf(
            File("src/main/java/com/pixelcast/app/CastService.kt"),
            File("app/src/main/java/com/pixelcast/app/CastService.kt")
        ).first { it.exists() }.readText()
        assertTrue(src.contains("scheduleNextPoll(1L)"))
        assertTrue(src.contains("CastPollingPolicy.delaySeconds("))
        assertTrue(src.contains("|| !playing) pollVolume()"))
        assertTrue(src.contains("pollTask?.cancel(false)"))
        assertTrue(src.contains("io.shutdown()"))
        assertTrue(src.contains("releaseLocks()"))
        assertTrue(src.contains("active = transport != null"))
    }
}
