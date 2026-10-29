package com.pixelcast.app
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class UnifiedLogsContractTest {
    @Test fun aaYouTubeLogActionsAndPagingArePresent() {
        val path = "app/src/main/java/com/pixelcast/app/UnifiedLogsPage.kt"
        val source = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?.readText() ?: error("Missing $path")
        listOf("followLatest", "onSizeChanged { viewport = it }", "Text(\"Prev\")",
            "Text(\"Next\")", "Text(\"Copy\")", "Text(\"Clear all\")",
            "AppLog.clear()", "Switch(checked = enabled").forEach { assertTrue(it, source.contains(it)) }
    }
}
