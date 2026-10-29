package com.pixelcast.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Hosted by the main Settings page, sharing its n2k02theme header. */
@Composable
internal fun PixelCastLogsPage(modifier: Modifier = Modifier) {
    N2K02UnifiedLogsPage(modifier = modifier, appName = "PixelCast")
}
