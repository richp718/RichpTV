package com.richpproductions.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private var player: ExoPlayer? = null
    private var index = 0
    private var retries = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val queue: List<Item> get() = Repo.queue
    private val current: Item? get() = queue.getOrNull(index)
    private val isLive: Boolean get() = current?.kind == Kind.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        playerView.setShowSubtitleButton(true)
        playerView.controllerShowTimeoutMs = 4000
        index = intent.getIntExtra("index", 0)
        if (queue.isEmpty()) finish()
    }

    override fun onStart() {
        super.onStart()
        if (queue.isEmpty()) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Repo.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        p.addListener(listener)
        p.playWhenReady = true
        playerView.player = p
        player = p
        play(index)
    }

    override fun onStop() {
        savePosition()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        playerView.player = null
        super.onStop()
    }

    /** Different ways to open the same stream: TS, HLS, as-is. Tried in order until one plays. */
    private var candidates: List<Pair<String, Boolean>> = emptyList()
    private var attempt = 0
    private var resumeAt = 0L
    private val stallCheck = Runnable {
        if (player?.playbackState != Player.STATE_READY) tryNext("No picture after 12 seconds")
    }

    private fun swapExt(url: String, from: String, to: String): String? {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val rest = if (q >= 0) url.substring(q) else ""
        if (!path.endsWith(from, true)) return null
        return path.substring(0, path.length - from.length) + to + rest
    }

    private fun candidatesFor(item: Item): List<Pair<String, Boolean>> {
        val url = item.url ?: return emptyList()
        val out = LinkedHashSet<Pair<String, Boolean>>()
        if (item.kind == Kind.LIVE) {
            val tsUrl = swapExt(url, ".m3u8", ".ts") ?: (if (swapExt(url, ".ts", ".ts") != null) url else null)
            val hlsUrl = swapExt(url, ".ts", ".m3u8") ?: (if (swapExt(url, ".m3u8", ".m3u8") != null) url else null)
            val ts = tsUrl?.let { it to false }
            val hls = hlsUrl?.let { it to true }
            if (Prefs.liveFormat == "m3u8") { hls?.let { out.add(it) }; ts?.let { out.add(it) } }
            else { ts?.let { out.add(it) }; hls?.let { out.add(it) } }
        }
        out.add(url to false)
        out.add(url to true)
        return out.toList()
    }

    private fun play(i: Int) {
        val item = queue.getOrNull(i) ?: return
        if (item.url == null) return
        index = i
        Repo.queueSeriesKey?.let { Prefs.setLastEpisode(it, item.key); Prefs.addRecent(it) } ?: Prefs.addRecent(item.key)
        candidates = candidatesFor(item)
        attempt = 0
        resumeAt = if (item.kind != Kind.LIVE) Prefs.position(item.key) else 0L
        startAttempt()
        val extra = if (item.kind == Kind.LIVE) "\nChannel ${i + 1} of ${queue.size}   ·   ▲▼ to change"
                    else if (resumeAt > 0) "\nResuming where you left off" else ""
        showOverlay(item.name + extra)
    }

    private fun startAttempt() {
        val p = player ?: return
        val item = current ?: return
        val (url, hls) = candidates.getOrNull(attempt) ?: return
        handler.removeCallbacks(stallCheck)
        val b = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
        if (hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (resumeAt > 0) p.setMediaItem(b.build(), resumeAt) else p.setMediaItem(b.build())
        p.prepare()
        p.play()
        handler.postDelayed(stallCheck, if (item.kind == Kind.LIVE) 12_000L else 25_000L)
    }

    private fun tryNext(reason: String) {
        handler.removeCallbacks(stallCheck)
        if (attempt + 1 < candidates.size) {
            attempt++
            startAttempt()
            return
        }
        if (isLive && retries < 2) {
            retries++
            attempt = 0
            showOverlay("Reconnecting… ($retries)")
            handler.postDelayed({ startAttempt() }, 2000)
            return
        }
        player?.stop()
        showOverlay("This channel won't play right now.\n$reason\nPress ▲▼ to try another channel.", 10_000)
    }

    private fun showOverlay(text: String, ms: Long = 3500) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, ms)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                handler.removeCallbacks(stallCheck)
                retries = 0
                // remember which stream type works for this provider
                val url = candidates.getOrNull(attempt)?.first ?: ""
                if (isLive && Repo.isXtream) {
                    val path = url.substringBefore('?')
                    if (path.endsWith(".m3u8", true)) Prefs.liveFormat = "m3u8"
                    else if (path.endsWith(".ts", true)) Prefs.liveFormat = "ts"
                }
            }
            if (state == Player.STATE_ENDED && !isLive) {
                current?.let { Prefs.savePosition(it.key, 0) }
                if (index + 1 < queue.size) play(index + 1) else finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            tryNext(error.errorCodeName)
        }
    }

    private fun savePosition() {
        val item = current ?: return
        val p = player ?: return
        if (item.kind == Kind.LIVE) return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur > 0 && pos > dur - 90_000) Prefs.savePosition(item.key, 0)
        else if (pos > 30_000) Prefs.savePosition(item.key, pos)
    }

    private fun zap(delta: Int) {
        if (queue.size < 2) return
        retries = 0
        handler.removeCallbacks(stallCheck)
        play(((index + delta) % queue.size + queue.size) % queue.size)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isLive) {
            val controls = playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (!controls) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controls) { zap(1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
