package com.pixelcast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("pixelcast", MODE_PRIVATE) }

    private var mediaUri: Uri? = null
    private var mediaTitle by mutableStateOf<String?>(null)
    private var mediaMime by mutableStateOf("video/mp4")

    private val tvs = mutableStateListOf<Renderer>()
    private var selected by mutableStateOf<Renderer?>(null)
    private var searching by mutableStateOf(false)
    private var searchHint by mutableStateOf("")
    private var castStatus by mutableStateOf(CastService.status)

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep access after the app restarts, so the last file is still there.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setMedia(uri, remember = true)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        restoreLast()
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) findTvs()

        val actions = UiActions(
            pickMedia = { pickMedia.launch(arrayOf("video/*", "audio/*")) },
            findTvs = ::findTvs,
            selectTv = { selected = it },
            cast = ::cast,
            togglePlay = { send(CastService.ACTION_TOGGLE) },
            rewind = { send(CastService.ACTION_REWIND) },
            forward = { send(CastService.ACTION_FORWARD) },
            stop = { send(CastService.ACTION_STOP) },
            seekTo = { send(CastService.ACTION_SEEK, it) },
        )
        setContent {
            PixelCastTheme {
                CastScreen(
                    UiState(
                        mediaTitle = mediaTitle,
                        mediaMime = mediaMime,
                        tvs = tvs.toList(),
                        selected = selected,
                        searching = searching,
                        searchHint = searchHint,
                        status = castStatus,
                    ),
                    actions,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        CastService.listener = { castStatus = it }
        castStatus = CastService.status
    }

    override fun onPause() {
        CastService.listener = null
        super.onPause()
    }

    // ---------------------------------------------------------------- media

    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return
        // Shared files are only readable for this session, so don't remember them.
        setMedia(uri, remember = false)
    }

    private fun setMedia(uri: Uri, remember: Boolean) {
        mediaUri = uri
        mediaMime = contentResolver.getType(uri) ?: "video/mp4"
        mediaTitle = displayName(uri)
        if (remember) {
            prefs.edit()
                .putString("mediaUri", uri.toString())
                .putString("mediaMime", mediaMime)
                .putString("mediaTitle", mediaTitle)
                .apply()
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Video"

    // ---------------------------------------------------------------- TVs

    private fun findTvs() {
        if (searching) return
        searching = true
        searchHint = "Looking for TVs on your Wi-Fi…"
        thread(name = "ssdp") {
            val found = runCatching { SsdpDiscovery.discover(this) }.getOrDefault(emptyList())
            runOnUiThread {
                searching = false
                found.forEach { r -> if (tvs.none { it.controlUrl == r.controlUrl }) tvs += r }
                if (selected == null && tvs.size == 1) selected = tvs.first()
                searchHint = when {
                    found.isNotEmpty() -> "Found ${found.size} TV(s)"
                    tvs.isNotEmpty() -> "No new TVs answered. Your last TV is still listed."
                    else -> "No TVs found. Check the TV is on and on the same Wi-Fi, then try again."
                }
            }
        }
    }

    // ---------------------------------------------------------------- casting

    private fun cast() {
        val uri = mediaUri ?: return
        val tv = selected ?: return
        prefs.edit().putString("tvName", tv.name).putString("tvControl", tv.controlUrl).apply()
        startForegroundService(
            Intent(this, CastService::class.java)
                .setAction(CastService.ACTION_CAST)
                .putExtra(CastService.EXTRA_URI, uri.toString())
                .putExtra(CastService.EXTRA_MIME, mediaMime)
                .putExtra(CastService.EXTRA_TITLE, mediaTitle)
                .putExtra(CastService.EXTRA_CONTROL_URL, tv.controlUrl)
                .putExtra(CastService.EXTRA_TV_NAME, tv.name)
        )
    }

    private fun send(action: String, position: Long? = null) {
        val intent = Intent(this, CastService::class.java).setAction(action)
        position?.let { intent.putExtra(CastService.EXTRA_POSITION, it) }
        startService(intent)
    }

    private fun restoreLast() {
        prefs.getString("mediaUri", null)?.let { saved ->
            val uri = Uri.parse(saved)
            val stillReadable = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillReadable) {
                mediaUri = uri
                mediaMime = prefs.getString("mediaMime", null) ?: "video/mp4"
                mediaTitle = prefs.getString("mediaTitle", null) ?: "Video"
            }
        }
        val name = prefs.getString("tvName", null)
        val control = prefs.getString("tvControl", null)
        if (name != null && control != null) {
            val last = Renderer(name, control)
            tvs += last
            selected = last
        }
    }
}
