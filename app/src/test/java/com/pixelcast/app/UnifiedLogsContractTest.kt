package com.pixelcast.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class UnifiedLogsContractTest {
    @Test fun aaYouTubeLogActionsAndPagingArePresent() {
        val path = "app/src/main/java/com/pixelcast/app/UnifiedLogsPage.kt"
        val source = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?.readText() ?: error("Missing $path")
        listOf("followLatest", "onSizeChanged { viewport = it }", "Text(\"Prev\",",
            "Text(\"Next\",", "Text(\"Copy\",", "Text(\"Clear all\",",
            "AppLog.clear()", "Switch(checked = enabled").forEach { assertTrue(it, source.contains(it)) }
        assertTrue(source.contains("Icons.Filled.ContentCopy"))
        assertTrue(source.contains("Icons.Filled.DeleteSweep"))
        assertTrue(source.contains("Icons.AutoMirrored.Filled.KeyboardArrowLeft"))
        assertTrue(source.contains("Icons.AutoMirrored.Filled.KeyboardArrowRight"))
        assertTrue(source.contains("horizontalArrangement = Arrangement.SpaceBetween"))
        assertFalse(source.contains("Text(\"Back\")"))
    }
    @Test fun mixedSourceLogsSortByDateAndTime() {
        val saved = listOf("2026-10-28 23:59:58.000 [UI] old",
            "2026-10-29 10:00:02.001 [LOG] later")
        val activity = listOf("[activity] 10:00:01 event")
        assertEquals(listOf(saved[0], activity[0], saved[1]),
            LogEventOrdering.merge(saved, activity, "2026-10-29"))
    }

    @Test fun runtimeEmitsPrivacySafeLifecycleEvents() {
        val path = "app/src/main/java/com/pixelcast/app/CastService.kt"
        val activity = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(activity.contains("CAST_START_FAILED"))
        assertTrue(activity.contains("CAST_PLAYING"))
        assertTrue(activity.contains("CAST_STOP_REQUESTED"))
        assertFalse(activity.contains("AppLog.add(\"CAST\", e.message"))
    }

}
