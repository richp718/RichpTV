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
    private var triedHls = false
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

    private fun play(i: Int, forceHls: Boolean = false) {
        val p = player ?: return
        val item = queue.getOrNull(i) ?: return
        val url = item.url ?: return
        index = i
        triedHls = forceHls
        Repo.queueSeriesKey?.let { Prefs.setLastEpisode(it, item.key); Prefs.addRecent(it) } ?: Prefs.addRecent(item.key)
        val b = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
        if (forceHls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        val start = if (item.kind != Kind.LIVE) Prefs.position(item.key) else 0L
        if (start > 0) p.setMediaItem(b.build(), start) else p.setMediaItem(b.build())
        p.prepare()
        p.play()
        val extra = if (item.kind == Kind.LIVE) "\nChannel ${i + 1} of ${queue.size}   ·   ▲▼ to change"
                    else if (start > 0) "\nResuming where you left off" else ""
        showOverlay(item.name + extra)
    }

    private fun showOverlay(text: String, ms: Long = 3500) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, ms)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retries = 0
            if (state == Player.STATE_ENDED && !isLive) {
                current?.let { Prefs.savePosition(it.key, 0) }
                if (index + 1 < queue.size) play(index + 1) else finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = current?.url ?: return
            if (!triedHls && !url.contains(".m3u8", true)) {
                play(index, forceHls = true)
                return
            }
            if (isLive && retries < 3) {
                retries++
                showOverlay("Reconnecting… ($retries)")
                val i = index
                handler.postDelayed({ play(i) }, 2000)
                return
            }
            showOverlay("This one won't play right now.\n${error.errorCodeName}", 8000)
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
        handler.removeCallbacksAndMessages(null)
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
