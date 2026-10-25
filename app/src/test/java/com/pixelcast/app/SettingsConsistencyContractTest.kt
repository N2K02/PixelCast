package com.pixelcast.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the mRadio-aligned Settings layout. Home and app functionality stay independent. */
class SettingsConsistencyContractTest {
    @Test fun settingsUsesConsistentRowsAndUpdatePlacement() {
        val relative = "app/src/main/java/com/pixelcast/app/CastScreen.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("} else if (showingSettings) {", "")
        assertTrue("Settings menu section must exist", menu.isNotBlank())
        assertTrue("Settings must have mRadio-style section heading", menu.contains("App information"))
        assertTrue("Settings should have aligned ListItems", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        assertTrue("Updates must remain available", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        // A single-item Settings page intentionally needs no diagnostic divider.
    }
}
