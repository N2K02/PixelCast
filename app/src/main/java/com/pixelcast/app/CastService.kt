package com.pixelcast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns one cast session: serves the file to the TV, sends it DLNA commands,
 * polls the TV for position, and shows the media notification
 * (play/pause, seek bar, back/forward 10 s, stop).
 */
class CastService : Service() {

    /** Snapshot of the cast for the UI. */
    data class Status(
        val active: Boolean = false,
        val tvName: String = "",
        val title: String = "",
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val message: String = "Not casting",
    )

    companion object {
        private const val TAG = "CastService"

        const val ACTION_CAST = "com.pixelcast.CAST"
        const val ACTION_PLAY = "com.pixelcast.PLAY"
        const val ACTION_PAUSE = "com.pixelcast.PAUSE"
        const val ACTION_TOGGLE = "com.pixelcast.TOGGLE"
        const val ACTION_REWIND = "com.pixelcast.REWIND"
        const val ACTION_FORWARD = "com.pixelcast.FORWARD"
        const val ACTION_SEEK = "com.pixelcast.SEEK"
        const val ACTION_STOP = "com.pixelcast.STOP"

        const val EXTRA_URI = "uri"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTROL_URL = "controlUrl"
        const val EXTRA_TV_NAME = "tvName"
        const val EXTRA_POSITION = "position"

        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val SKIP_MS = 10_000L

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var status = Status()
            private set

        /** Set by the activity while it's visible; always called on the main thread. */
        var listener: ((Status) -> Unit)? = null

        private fun publish(s: Status) {
            status = s
            mainHandler.post { listener?.invoke(s) }
        }
    }

    private lateinit var session: MediaSession
    private lateinit var notificationManager: NotificationManager
    private val server by lazy { MediaHttpServer(this) }

    // Every DLNA call and every state change happens on this one thread, so no locking is needed.
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null

    private var transport: AvTransport? = null
    private var tvName = ""
    private var title = ""
    private var playing = false
    private var positionMs = 0L
    private var positionAt = 0L
    private var durationMs = 0L
    private var started = false      // TV has reported PLAYING at least once
    private var ended = false        // TV stopped by itself at the end of the file
    private var holdPollUntil = 0L   // ignore stale poll results right after a command
    private var failures = 0
    private var message = ""
    private var error = false

    private var shownMeta: Pair<String, Long>? = null
    private var shownNotification: Triple<Boolean, String, String>? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for the video or song playing on your TV"
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "PixelCast").apply {
            setCallback(sessionCallback, mainHandler)
            setSessionActivity(openAppIntent())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAST -> {
                // Must go foreground right away when started with startForegroundService().
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                val uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY)
                val mime = intent.getStringExtra(EXTRA_MIME) ?: "video/mp4"
                val newTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
                val controlUrl = intent.getStringExtra(EXTRA_CONTROL_URL) ?: return START_NOT_STICKY
                val newTv = intent.getStringExtra(EXTRA_TV_NAME) ?: "TV"
                io.execute { load(uri, mime, newTitle, controlUrl, newTv) }
            }
            ACTION_PLAY -> io.execute { play() }
            ACTION_PAUSE -> io.execute { pause() }
            ACTION_TOGGLE -> io.execute { if (playing) pause() else play() }
            ACTION_REWIND -> io.execute { seekTo(currentPosition() - SKIP_MS) }
            ACTION_FORWARD -> io.execute { seekTo(currentPosition() + SKIP_MS) }
            ACTION_SEEK -> {
                val target = intent.getLongExtra(EXTRA_POSITION, 0)
                io.execute { seekTo(target) }
            }
            ACTION_STOP -> stopCasting()
            else -> io.execute { if (transport == null) mainHandler.post { stopSelf() } }
        }
        return START_NOT_STICKY
    }

    /** Called by the system media controls (notification, lock screen, Bluetooth buttons). */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() { io.execute { play() } }
        override fun onPause() { io.execute { pause() } }
        override fun onStop() { stopCasting() }
        override fun onSeekTo(pos: Long) { io.execute { seekTo(pos) } }
        override fun onRewind() { io.execute { seekTo(currentPosition() - SKIP_MS) } }
        override fun onFastForward() { io.execute { seekTo(currentPosition() + SKIP_MS) } }
        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_REWIND -> io.execute { seekTo(currentPosition() - SKIP_MS) }
                ACTION_FORWARD -> io.execute { seekTo(currentPosition() + SKIP_MS) }
                ACTION_STOP -> stopCasting()
            }
        }
    }

    // ---------------------------------------------------------------- DLNA (io thread only)

    private fun load(uri: Uri, mime: String, newTitle: String, controlUrl: String, newTv: String) {
        transport?.let { old -> runCatching { old.stop() } }
        val t = AvTransport(controlUrl)
        transport = t
        title = newTitle
        tvName = newTv
        playing = false
        started = false
        ended = false
        failures = 0
        error = false
        durationMs = 0
        markPosition(0)
        update("Connecting to $tvName…")
        try {
            acquireLocks()
            val url = server.serve(uri, mime, t.host)
            Log.i(TAG, "Casting $url to $controlUrl")
            t.setUri(url, title, mime)
            // Some TVs need a moment after SetAVTransportURI before they accept Play.
            retry(times = 4, delayMs = 700) { t.play() }
            playing = true
            markPosition(0)
            holdPolling(3000)
            startPolling()
            update("Playing on $tvName")
        } catch (e: Exception) {
            Log.w(TAG, "Cast failed", e)
            fail("Couldn't play on $tvName: ${e.message}")
        }
    }

    private fun play() {
        val t = transport ?: return
        command("play") { t.play() }
        if (!error) {
            playing = true
            // After the video finished, Play starts it again from the beginning.
            markPosition(if (ended) 0 else positionMs)
            ended = false
            update("Playing on $tvName")
        }
    }

    private fun pause() {
        val t = transport ?: return
        val pos = currentPosition()
        command("pause") { t.pause() }
        if (!error) {
            playing = false
            markPosition(pos)
            update("Paused")
        }
    }

    private fun seekTo(target: Long) {
        val t = transport ?: return
        val pos = if (durationMs > 0) target.coerceIn(0, durationMs - 1000) else target.coerceAtLeast(0)
        command("seek") { t.seek(pos) }
        if (!error) {
            markPosition(pos)
            update(null)
        }
    }

    private fun command(name: String, block: () -> Unit) {
        try {
            block()
            holdPolling(1500)
        } catch (e: Exception) {
            Log.w(TAG, "$name failed", e)
            update("TV didn't accept $name: ${e.message}")
        }
    }

    private fun poll() {
        val t = transport ?: return
        if (SystemClock.elapsedRealtime() < holdPollUntil) return
        try {
            val state = t.transportState()
            val (pos, dur) = t.positionInfo()
            failures = 0
            if (error) error = false
            if (dur != null && dur > 0) durationMs = dur
            var newMessage: String? = null
            when (state) {
                "PLAYING" -> {
                    playing = true; started = true; ended = false; newMessage = "Playing on $tvName"
                }
                "PAUSED_PLAYBACK" -> {
                    playing = false; newMessage = "Paused"
                }
                "STOPPED", "NO_MEDIA_PRESENT" -> if (started) {
                    playing = false; ended = true; newMessage = "Finished — press play to watch again"
                }
            }
            if (pos != null) markPosition(pos)
            update(newMessage)
        } catch (e: Exception) {
            if (++failures >= 5) fail("Lost connection to $tvName")
        }
    }

    private fun startPolling() {
        if (pollTask == null) {
            pollTask = io.scheduleWithFixedDelay({ poll() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    private fun holdPolling(ms: Long) {
        holdPollUntil = SystemClock.elapsedRealtime() + ms
    }

    private fun markPosition(ms: Long) {
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
    }

    private fun currentPosition(): Long {
        val pos = if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs
        return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
    }

    private fun fail(msg: String) {
        error = true
        playing = false
        update(msg)
    }

    private inline fun retry(times: Int, delayMs: Long, block: () -> Unit) {
        var last: Exception? = null
        repeat(times) {
            try {
                block(); return
            } catch (e: Exception) {
                last = e
                Thread.sleep(delayMs)
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    // ---------------------------------------------------------------- stop & cleanup

    private fun stopCasting() {
        io.execute {
            pollTask?.cancel(false)
            pollTask = null
            transport?.let { t -> runCatching { t.stop() } }
            transport = null
            playing = false
            server.shutdown()
            releaseLocks()
            publish(Status(message = "Stopped"))
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        pollTask?.cancel(false)
        io.shutdown()
        server.shutdown()
        releaseLocks()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // FULL_HIGH_PERF still keeps Wi-Fi awake for a foreground service.
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelCast:cast")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PixelCast:cast")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---------------------------------------------------------------- session + notification

    private fun update(newMessage: String?) {
        if (newMessage != null) message = newMessage
        val position = currentPosition()

        val meta = title to durationMs
        if (meta != shownMeta) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "on $tvName")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build()
            )
            shownMeta = meta
        }

        val state = when {
            error -> PlaybackState.STATE_ERROR
            transport == null -> PlaybackState.STATE_NONE
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                )
                // With no previous/next actions, Android puts the first two custom
                // actions in those slots: back 10 s on the left, forward 10 s on the right.
                .addCustomAction(customAction(ACTION_REWIND, "Back 10s", R.drawable.ic_rewind))
                .addCustomAction(customAction(ACTION_FORWARD, "Forward 10s", R.drawable.ic_forward))
                .addCustomAction(customAction(ACTION_STOP, "Stop casting", R.drawable.ic_stop))
                .setState(state, position, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
                .apply { if (error) setErrorMessage(message) }
                .build()
        )

        val shown = Triple(playing, title, tvName)
        if (shown != shownNotification) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
            shownNotification = shown
        }

        publish(
            Status(
                active = transport != null,
                tvName = tvName,
                title = title,
                playing = playing,
                positionMs = position,
                durationMs = durationMs,
                message = message,
            )
        )
    }

    private fun customAction(action: String, label: String, icon: Int) =
        PlaybackState.CustomAction.Builder(action, label, icon).build()

    private fun buildNotification(): Notification {
        val playPause = if (playing) {
            notificationAction(R.drawable.ic_pause, "Pause", ACTION_PAUSE)
        } else {
            notificationAction(R.drawable.ic_play, "Play", ACTION_PLAY)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(title.ifEmpty { "PixelCast" })
            .setContentText(if (tvName.isEmpty()) "Connecting…" else "on $tvName")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            // Android 12 and older use these buttons; Android 13+ builds them from the session.
            .addAction(notificationAction(R.drawable.ic_rewind, "Back 10s", ACTION_REWIND))
            .addAction(playPause)
            .addAction(notificationAction(R.drawable.ic_forward, "Forward 10s", ACTION_FORWARD))
            .addAction(notificationAction(R.drawable.ic_stop, "Stop", ACTION_STOP))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun notificationAction(icon: Int, label: String, action: String): Notification.Action {
        val intent = Intent(this, CastService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pending).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
