package com.pixelcast.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Shows when an update is available, downloading or ready to install; hidden otherwise.
 * Put it near the top of the main screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    dev.n2k02.updater.N2K02UpdateScreen(
        controller = LiveUpdater.controller,
        installGuard = LiveUpdater.installGuard,
        modifier = modifier,
    )
}


@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Notes(release: AppUpdater.Release) {
    if (release.notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        release.notes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
    }
}


/**
 * The gear in the top bar. Its menu has "Update", which shows the installed version and the
 * update status, and checks for updates when tapped (the update card on the screen shows the result).
 */
@Composable
fun SettingsMenuButton() {
    var open by remember { mutableStateOf(false) }
    val state by LiveUpdater.controller.status.collectAsState()
    val experimentalEnabled by LiveUpdater.experimentalEnabled.collectAsState()
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (experimentalEnabled) "Experimental updates: on" else "Experimental updates: off") },
                onClick = { open = false; LiveUpdater.setExperimentalEnabled(!experimentalEnabled) },
            )

            DropdownMenuItem(
                text = {
                    Column {
                        Text("Update")
                        Text(
                            when (val u = state) {
                                is dev.n2k02.updater.UpdateStatus.Checking -> "Checking…"
                                is dev.n2k02.updater.UpdateStatus.Available -> "Version ${u.release.tag} available"
                                is dev.n2k02.updater.UpdateStatus.Downloading -> "Downloading…"
                                is dev.n2k02.updater.UpdateStatus.Ready, is dev.n2k02.updater.UpdateStatus.NeedsInstallPermission -> "Ready to install"
                                is dev.n2k02.updater.UpdateStatus.Current -> "Up to date · v${BuildConfig.VERSION_NAME}"
                                else -> "v${BuildConfig.VERSION_NAME} · check for updates"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_system_update), contentDescription = null) },
                onClick = {
                    open = false
                    // Other states already show their card with the right buttons.
                    if (state is dev.n2k02.updater.UpdateStatus.Idle || state is dev.n2k02.updater.UpdateStatus.Current || state is dev.n2k02.updater.UpdateStatus.Failed) {
                        LiveUpdater.controller.check(manual = true)
                    }
                },
            )
        }
    }
}
