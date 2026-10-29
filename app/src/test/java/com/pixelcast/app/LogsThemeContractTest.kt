package com.pixelcast.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsThemeContractTest {
    private fun src(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
    @Test fun logsAreThemedAndReachableFromSettings() {
        val app = src("app/src/main/java/com/pixelcast/app/CastScreen.kt")
        val logs = src("app/src/main/java/com/pixelcast/app/LogsActivity.kt")
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(app.contains("PixelCastLogsPage("))
        assertTrue(app.contains("showingLogs"))
        assertFalse(app.contains("LogsActivity::class.java"))
        assertFalse(logs.contains("MaterialTheme {"))
        assertFalse(logs.contains("class LogsActivity"))
        assertTrue(logs.contains("maxHeight < 430.dp"))
        assertTrue(logs.contains("onClick = AppLog::clear"))
        assertTrue(logs.contains("Switch(checked = enabled"))
        assertFalse(manifest.contains("com.pixelcast.app.LogsActivity"))
    }
}
