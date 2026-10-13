package com.pixelcast.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_NOTIFICATIONS = 2
        private const val PREFS = "pixelcast"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var mediaUri: Uri? = null
    private var mediaMime = "video/mp4"
    private var mediaTitle = ""

    private val renderers = mutableListOf<Renderer>()
    private var selected: Renderer? = null
    private var searching = false
    private var userSeeking = false

    private lateinit var mediaLabel: TextView
    private lateinit var findButton: Button
    private lateinit var findProgress: ProgressBar
    private lateinit var tvGroup: RadioGroup
    private lateinit var tvHint: TextView
    private lateinit var castButton: Button
    private lateinit var nowPanel: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playPause: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        restoreLast()
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        if (renderers.isEmpty()) findTvs()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        CastService.listener = ::render
        render(CastService.status)
    }

    override fun onPause() {
        CastService.listener = null
        super.onPause()
    }

    // ---------------------------------------------------------------- media selection

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Platform Activity API; fine for this no-dependency app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && uri != null) {
            // Keep access after the app restarts, so "last file" still works.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setMedia(uri, remember = true)
        }
    }

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
        mediaLabel.text = "$mediaTitle\n$mediaMime"
        if (remember) {
            prefs.edit()
                .putString("mediaUri", uri.toString())
                .putString("mediaMime", mediaMime)
                .putString("mediaTitle", mediaTitle)
                .apply()
        }
        updateCastButton()
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Video"

    // ---------------------------------------------------------------- TV discovery

    private fun findTvs() {
        if (searching) return
        searching = true
        findButton.isEnabled = false
        findProgress.visibility = View.VISIBLE
        tvHint.text = "Looking for TVs on your Wi-Fi…"
        thread(name = "ssdp") {
            val found = runCatching { SsdpDiscovery.discover(this) }.getOrDefault(emptyList())
            runOnUiThread {
                searching = false
                findButton.isEnabled = true
                findProgress.visibility = View.GONE
                found.forEach { r -> if (renderers.none { it.controlUrl == r.controlUrl }) renderers += r }
                tvHint.text = when {
                    found.isNotEmpty() -> "Found ${found.size} TV(s). Pick one:"
                    renderers.isNotEmpty() -> "No new TVs answered. Your last TV is still listed."
                    else -> "No TVs found. Check the TV is on and on the same Wi-Fi, then try again."
                }
                showTvs()
            }
        }
    }

    private fun showTvs() {
        tvGroup.removeAllViews()
        renderers.forEach { r ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = r.name
                textSize = 16f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                tag = r
                isChecked = r.controlUrl == selected?.controlUrl
            }
            tvGroup.addView(button)
        }
        if (selected == null && renderers.size == 1) {
            (tvGroup.getChildAt(0) as RadioButton).isChecked = true
        }
        updateCastButton()
    }

    // ---------------------------------------------------------------- casting

    private fun cast() {
        val uri = mediaUri ?: return
        val tv = selected ?: return
        prefs.edit().putString("tvName", tv.name).putString("tvControl", tv.controlUrl).apply()
        val intent = Intent(this, CastService::class.java)
            .setAction(CastService.ACTION_CAST)
            .putExtra(CastService.EXTRA_URI, uri.toString())
            .putExtra(CastService.EXTRA_MIME, mediaMime)
            .putExtra(CastService.EXTRA_TITLE, mediaTitle)
            .putExtra(CastService.EXTRA_CONTROL_URL, tv.controlUrl)
            .putExtra(CastService.EXTRA_TV_NAME, tv.name)
        startForegroundService(intent)
    }

    private fun send(action: String, position: Long? = null) {
        val intent = Intent(this, CastService::class.java).setAction(action)
        position?.let { intent.putExtra(CastService.EXTRA_POSITION, it) }
        startService(intent)
    }

    private fun render(s: CastService.Status) {
        nowPanel.visibility = if (s.active || s.message != "Not casting") View.VISIBLE else View.GONE
        nowTitle.text = if (s.active) "${s.title}\non ${s.tvName}" else "Not casting"
        statusText.text = s.message
        playPause.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPause.contentDescription = if (s.playing) "Pause" else "Play"
        val controlsEnabled = s.active
        listOf<View>(playPause, seekBar).forEach { it.isEnabled = controlsEnabled }
        if (!userSeeking) {
            seekBar.max = (s.durationMs / 1000).toInt().coerceAtLeast(1)
            seekBar.progress = (s.positionMs / 1000).toInt()
        }
        timeText.text = "${fmt(s.positionMs)} / ${if (s.durationMs > 0) fmt(s.durationMs) else "--:--"}"
    }

    private fun updateCastButton() {
        castButton.isEnabled = mediaUri != null && selected != null
        castButton.text = selected?.let { "Cast to ${it.name}" } ?: "Cast to TV"
    }

    private fun restoreLast() {
        prefs.getString("mediaUri", null)?.let { saved ->
            val uri = Uri.parse(saved)
            val stillReadable = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillReadable) {
                mediaUri = uri
                mediaMime = prefs.getString("mediaMime", null) ?: "video/mp4"
                mediaTitle = prefs.getString("mediaTitle", null) ?: "Video"
                mediaLabel.text = "$mediaTitle\n$mediaMime"
            }
        }
        val name = prefs.getString("tvName", null)
        val control = prefs.getString("tvControl", null)
        if (name != null && control != null) {
            val last = Renderer(name, control)
            renderers += last
            selected = last
            tvHint.text = "Last used TV:"
            showTvs()
        }
        updateCastButton()
    }

    // ---------------------------------------------------------------- UI (built in code, no XML)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        root.addView(heading("1. Choose a video or song"))
        mediaLabel = body("Nothing chosen yet")
        root.addView(mediaLabel)
        root.addView(Button(this).apply {
            text = "Choose file"
            setOnClickListener { pickMedia() }
        })

        root.addView(heading("2. Pick your TV"))
        tvHint = body("")
        root.addView(tvHint)
        tvGroup = RadioGroup(this).apply {
            setOnCheckedChangeListener { group, id ->
                selected = group.findViewById<RadioButton>(id)?.tag as? Renderer
                updateCastButton()
            }
        }
        root.addView(tvGroup)
        val findRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        findButton = Button(this).apply {
            text = "Find TVs"
            setOnClickListener { findTvs() }
        }
        findProgress = ProgressBar(this).apply { visibility = View.GONE }
        findRow.addView(findButton)
        findRow.addView(findProgress, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) })
        root.addView(findRow)

        root.addView(heading("3. Cast"))
        castButton = Button(this).apply {
            text = "Cast to TV"
            isEnabled = false
            setOnClickListener { cast() }
        }
        root.addView(castButton)

        nowPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.argb(24, 26, 115, 232))
            visibility = View.GONE
        }
        nowTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusText = body("")
        seekBar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = fmt(progress * 1000L)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    send(CastService.ACTION_SEEK, bar.progress * 1000L)
                }
            })
        }
        timeText = body("")
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(iconButton(R.drawable.ic_rewind, "Back 10 seconds") { send(CastService.ACTION_REWIND) })
        playPause = iconButton(R.drawable.ic_play, "Play") { send(CastService.ACTION_TOGGLE) }
        controls.addView(playPause)
        controls.addView(iconButton(R.drawable.ic_forward, "Forward 10 seconds") { send(CastService.ACTION_FORWARD) })
        controls.addView(iconButton(R.drawable.ic_stop, "Stop casting") { send(CastService.ACTION_STOP) })

        nowPanel.addView(nowTitle)
        nowPanel.addView(statusText)
        nowPanel.addView(seekBar)
        nowPanel.addView(timeText)
        nowPanel.addView(controls)
        root.addView(nowPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        root.addView(body(
            "\nTip: the same controls (play/pause, seek bar, ±10 s, stop) are in your notification " +
                "shade and lock screen while casting.\n\nWorks best with MP4 (H.264/AAC) videos and MP3 songs. " +
                "You can also share a video from Files or Photos straight to PixelCast."
        ).apply { alpha = 0.7f })

        return ScrollView(this).apply { addView(root) }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.rgb(26, 115, 232))
        contentDescription = label
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        scaleX = 1.3f
        scaleY = 1.3f
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
