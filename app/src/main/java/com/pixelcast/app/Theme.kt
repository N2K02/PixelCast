package com.pixelcast.app

import androidx.compose.runtime.Composable
import dev.n2k02.theme.N2K02Theme

/** Shared Material 3 Expressive design system used by all N2K02 apps. */
@Composable
fun PixelCastTheme(content: @Composable () -> Unit) {
    N2K02Theme(content = content)
}
