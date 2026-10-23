package com.pixelcast.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URL

/** Everything the screen shows. */
data class UiState(
    val mediaTitle: String?,
    val mediaMime: String,
    val subtitleName: String?,
    val tvs: List<Renderer>,
    val selected: Renderer?,
    val searching: Boolean,
    val searchHint: String,
    val status: CastService.Status,
)

class UiActions(
    val pickMedia: () -> Unit,
    val pickSubtitle: () -> Unit,
    val clearSubtitle: () -> Unit,
    val findTvs: () -> Unit,
    val selectTv: (Renderer) -> Unit,
    val cast: () -> Unit,
    val togglePlay: () -> Unit,
    val rewind: () -> Unit,
    val forward: () -> Unit,
    val stop: () -> Unit,
    val seekTo: (Long) -> Unit,
    val setVolume: (Int) -> Unit,
    val toggleMute: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CastScreen(state: UiState, actions: UiActions) {
    var showingUpdates by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(if (showingUpdates) "Updates" else "PixelCast") },
                navigationIcon = { if (showingUpdates) IconButton(onClick = { showingUpdates = false }) { Text("‹") } },
                actions = { if (!showingUpdates) SettingsMenuButton(onOpenUpdates = { showingUpdates = true }) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (showingUpdates) {
            Column(Modifier.padding(padding).padding(16.dp)) { UpdatesPage() }
        } else LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AnimatedVisibility(visible = state.status.active || state.status.message != "Not casting") {
                    NowPlayingCard(state.status, actions)
                }
            }
            item { MediaCard(state, actions) }
            item { TvCard(state, actions) }
            item {
                Button(
                    onClick = actions.cast,
                    enabled = state.mediaTitle != null && state.selected != null,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_cast), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.selected?.let { "Cast to ${it.name}" } ?: "Cast to TV",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Text(
                    "While casting, the same controls are in your notification shade and on the lock screen, " +
                        "and your phone's volume buttons change the TV's volume. " +
                        "MP4 (H.264/AAC) videos and MP3 songs work best. You can also share a video from " +
                        "Files or Photos straight to PixelCast.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlayingCard(status: CastService.Status, actions: UiActions) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (status.active) status.title else "Not casting",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.active) {
                Text("on ${status.tvName}", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(status.message, style = MaterialTheme.typography.bodyMedium)

            // Seek bar: shows the TV's position; dragging seeks when you let go.
            var dragging by remember { mutableStateOf<Float?>(null) }
            val durationSec = (status.durationMs / 1000f).coerceAtLeast(1f)
            val positionSec = (status.positionMs / 1000f).coerceIn(0f, durationSec)
            Slider(
                value = dragging ?: positionSec,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { actions.seekTo((it * 1000).toLong()) }
                    dragging = null
                },
                valueRange = 0f..durationSec,
                enabled = status.active && status.durationMs > 0,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(((dragging ?: positionSec) * 1000).toLong()), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    if (status.durationMs > 0) formatTime(status.durationMs) else "--:--",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = actions.rewind,
                    enabled = status.active,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(56.dp),
                ) { Icon(painterResource(R.drawable.ic_rewind), contentDescription = "Back 10 seconds") }

                FilledIconButton(
                    onClick = actions.togglePlay,
                    enabled = status.active,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(width = 96.dp, height = 72.dp),
                ) {
                    Icon(
                        painterResource(if (status.playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (status.playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }

                FilledTonalIconButton(
                    onClick = actions.forward,
                    enabled = status.active,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(56.dp),
                ) { Icon(painterResource(R.drawable.ic_forward), contentDescription = "Forward 10 seconds") }

                FilledTonalIconButton(
                    onClick = actions.stop,
                    enabled = status.active,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(56.dp),
                ) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop casting") }
            }

            if (status.active && status.volume >= 0) {
                VolumeRow(status, actions)
            }
        }
    }
}

/** TV volume: mute button and a slider that sets the volume when you let go. */
@Composable
private fun VolumeRow(status: CastService.Status, actions: UiActions) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: status.volume.toFloat()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        IconButton(onClick = actions.toggleMute) {
            Icon(
                painterResource(if (status.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                contentDescription = if (status.muted) "Unmute TV" else "Mute TV",
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { actions.setVolume(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..RenderingControl.MAX_VOLUME.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            if (status.muted && dragging == null) "Muted" else shown.toInt().toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp).width(48.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaCard(state: UiState, actions: UiActions) {
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        SectionTitle("Video or song")
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_play), contentDescription = null) },
            headlineContent = {
                Text(state.mediaTitle ?: "Nothing chosen yet", maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = { if (state.mediaTitle != null) Text(state.mediaMime) },
            trailingContent = {
                FilledTonalButton(onClick = actions.pickMedia, shapes = ButtonDefaults.shapes()) {
                    Text(if (state.mediaTitle == null) "Choose" else "Change")
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )
        // Subtitles only make sense for videos.
        if (state.mediaTitle != null && !state.mediaMime.startsWith("audio")) {
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_subtitles), contentDescription = null) },
                headlineContent = {
                    Text(state.subtitleName ?: "No subtitles", maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(if (state.subtitleName == null) "Optional .srt file" else "Shown on the TV")
                },
                trailingContent = {
                    if (state.subtitleName == null) {
                        FilledTonalButton(onClick = actions.pickSubtitle, shapes = ButtonDefaults.shapes()) {
                            Text("Add")
                        }
                    } else {
                        OutlinedButton(onClick = actions.clearSubtitle, shapes = ButtonDefaults.shapes()) {
                            Text("Remove")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvCard(state: UiState, actions: UiActions) {
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { SectionTitle("TV") }
            if (state.searching) {
                LoadingIndicator(Modifier.padding(end = 16.dp, top = 8.dp).size(40.dp))
            } else {
                OutlinedButton(
                    onClick = actions.findTvs,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.padding(end = 16.dp, top = 8.dp),
                ) { Text("Find TVs") }
            }
        }
        if (state.searchHint.isNotEmpty()) {
            Text(
                state.searchHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        state.tvs.forEach { tv ->
            val isSelected = tv.controlUrl == state.selected?.controlUrl
            ListItem(
                leadingContent = { RadioButton(selected = isSelected, onClick = { actions.selectTv(tv) }) },
                headlineContent = { Text(tv.name) },
                supportingContent = { Text(runCatching { URL(tv.controlUrl).host }.getOrDefault("")) },
                colors = ListItemDefaults.colors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.clickable { actions.selectTv(tv) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
