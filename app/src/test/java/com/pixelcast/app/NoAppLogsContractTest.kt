package com.pixelcast.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAppLogsContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .first { it.exists() }.readText()

    @Test fun settingsDoesNotExposeLogsViewer() {
        val screen = source("app/src/main/java/com/pixelcast/app/CastScreen.kt")
        assertFalse(screen.contains("showingLogs"))
        assertFalse(screen.contains("PixelCastLogsPage"))
        assertFalse(screen.contains("Text(\"Logs\")"))
    }

    @Test fun diagnosticsUseOnlyLogcat() {
        val log = source("app/src/main/java/com/pixelcast/app/AppLog.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(log.contains("Log.d("))
        assertFalse(log.contains("appendText("))
        assertFalse(log.contains("createNewFile("))
        assertFalse(manifest.contains("CrashJournalProvider"))
    }
}
