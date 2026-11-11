package com.richpproductions.tv

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grid TV guide: channels down the side, programmes across a 3-hour timeline.
 *  OK on a channel = live preview in the top-left box; OK again on the same channel = full screen.
 *  Hold OK = favorite / hide that channel. */
class GuideActivity : AppCompatActivity() {

    private val windowMinutes = 180
    private var winStart = 0L          // unix seconds
    private var pxPerMin = 4f
    private val time = SimpleDateFormat("h:mm a", Locale.US)

    private lateinit var catAdapter: CatAdapter
    private lateinit var rowAdapter: RowAdapter
    private lateinit var rows: RecyclerView
    private lateinit var infoTitle: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoDesc: TextView
    private var channels: List<Item> = emptyList()
    private var catJob: Job? = null
    private var focusJob: Job? = null

    // ----- live preview -----
    private lateinit var previewView: PlayerView
    private lateinit var previewHint: TextView
    private var preview: ExoPlayer? = null
    private var previewKey: String? = null
    private var previewCands: List<Pair<String, Boolean>> = emptyList()
    private var previewAttempt = 0
    private val previewListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) previewHint.visibility = View.GONE
        }
        override fun onPlayerError(error: PlaybackException) {
            previewAttempt++
            if (previewAttempt < previewCands.size) startPreviewAttempt()
            else { previewHint.text = "This channel isn't playing right now."; previewHint.visibility = View.VISIBLE }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_guide)
        rows = findViewById(R.id.rows)
        infoTitle = findViewById(R.id.infoTitle)
        infoTime = findViewById(R.id.infoTime)
        infoDesc = findViewById(R.id.infoDesc)
        previewView = findViewById(R.id.preview)
        previewHint = findViewById(R.id.previewHint)

        val now = System.currentTimeMillis() / 1000
        winStart = now - (now % 1800)   // round down to the half hour

        val dm = resources.displayMetrics
        val timelinePx = dm.widthPixels - dp(220) - dp(24) - dp(210) - dp(10) - dp(6)
        pxPerMin = timelinePx / windowMinutes.toFloat()
        buildTimeHeader()

        val live = Repo.visible(Repo.live)
        val cats = Repo.groups(live)
        catAdapter = CatAdapter(
            onFocus = { i ->
                catJob?.cancel()
                catJob = lifecycleScope.launch { delay(250); catAdapter.select(i); showCategory(i) }
            },
            onClick = { i ->
                catJob?.cancel(); catAdapter.select(i); showCategory(i)
                rows.post { rows.layoutManager?.findViewByPosition(0)?.requestFocus() }
            }
        )
        val catList = findViewById<RecyclerView>(R.id.cats)
        catList.layoutManager = LinearLayoutManager(this)
        catList.itemAnimator = null
        catList.adapter = catAdapter
        catAdapter.set(cats, 0)

        rowAdapter = RowAdapter()
        rows.layoutManager = LinearLayoutManager(this)
        rows.itemAnimator = null
        rows.adapter = rowAdapter

        val status = findViewById<TextView>(R.id.status)
        if (cats.isEmpty()) {
            status.text = "No live channels to show."
            status.visibility = View.VISIBLE
        } else if (!Repo.hasGuide) {
            status.text = "Your playlist didn't include a TV guide.\nAdd a TV guide link in Settings → Change playlist."
            status.visibility = View.VISIBLE
        }
        showCategory(0)
        catList.post { catList.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildTimeHeader() {
        val header = findViewById<FrameLayout>(R.id.timeHeader)
        header.removeAllViews()
        val offset = dp(210) + dp(10)
        var m = 0
        while (m < windowMinutes) {
            val tv = TextView(this)
            tv.text = time.format(Date((winStart + m * 60) * 1000))
            tv.setTextColor(ContextCompat.getColor(this, R.color.muted))
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER_VERTICAL
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.leftMargin = offset + (m * pxPerMin).toInt() + dp(4)
            header.addView(tv, lp)
            m += 30
        }
        val nowMin = (System.currentTimeMillis() / 1000 - winStart) / 60f
        val line = findViewById<View>(R.id.nowLine)
        line.translationX = offset + dp(3) + nowMin * pxPerMin
    }

    private fun showCategory(i: Int) {
        val name = catAdapter.cats.getOrNull(i)?.first ?: return
        channels = Repo.visible(Repo.live).filter { it.group == name }
        rowAdapter.notifyDataSetChanged()
        rows.scrollToPosition(0)
        channels.firstOrNull()?.let { showInfo(it) }
    }

    private fun showInfo(ch: Item) {
        infoTitle.text = ch.name
        infoTime.text = ""
        infoDesc.text = ""
        focusJob?.cancel()
        focusJob = lifecycleScope.launch {
            val list = Repo.epg(ch)
            val now = System.currentTimeMillis() / 1000
            val cur = list.firstOrNull { it.start <= now && it.end > now } ?: list.firstOrNull()
            if (cur == null) {
                infoTime.text = if (Repo.hasGuide) "No guide info for this channel" else ""
                return@launch
            }
            val next = list.getOrNull(list.indexOf(cur) + 1)
            infoTitle.text = "${ch.name}   ·   ${cur.title}"
            infoTime.text = "NOW  ${time.format(Date(cur.start * 1000))} – ${time.format(Date(cur.end * 1000))}" +
                (next?.let { "        NEXT  ${time.format(Date(it.start * 1000))}  ${it.title}" } ?: "")
            infoDesc.text = cur.desc
        }
    }

    override fun onResume() {
        super.onResume()
        val key = Repo.lastOpenedKey ?: return
        val pos = channels.indexOfFirst { it.key == key }
        if (pos < 0) return
        rows.scrollToPosition(pos)
        rows.post { rows.layoutManager?.findViewByPosition(pos)?.requestFocus() }
    }

    private fun startPreview(ch: Item) {
        val p = preview ?: run {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(Repo.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
                .build().also {
                    it.addListener(previewListener)
                    previewView.player = it
                    preview = it
                }
        }
        previewKey = ch.key
        previewCands = Repo.streamCandidates(ch)
        previewAttempt = 0
        previewHint.text = "Loading ${ch.name}…"
        previewHint.visibility = View.VISIBLE
        p.playWhenReady = true
        startPreviewAttempt()
    }

    private fun startPreviewAttempt() {
        val p = preview ?: return
        val (url, hls) = previewCands.getOrNull(previewAttempt) ?: return
        val b = MediaItem.Builder().setUri(url)
        if (hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(b.build())
        p.prepare()
    }

    private fun stopPreview() {
        preview?.release()
        preview = null
        previewView.player = null
        previewKey = null
        previewHint.text = "Press OK on a channel to preview it here.\nPress OK again to watch full screen."
        previewHint.visibility = View.VISIBLE
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    private fun onChannelClick(pos: Int) {
        val ch = channels.getOrNull(pos) ?: return
        if (previewKey == ch.key) play(pos) else startPreview(ch)
    }

    private fun onChannelLong(pos: Int) {
        val ch = channels.getOrNull(pos) ?: return
        val favLabel = if (Prefs.isFav(ch.key)) "Remove from Favorites" else "★ Add to Favorites"
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(arrayOf(favLabel, "Hide this channel")) { _, which ->
                if (which == 0) {
                    val added = Prefs.toggleFav(ch.key)
                    Toast.makeText(this, if (added) "★ Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
                } else hideChannel(pos)
            }
            .show()
    }

    private fun hideChannel(pos: Int) {
        val ch = channels.getOrNull(pos) ?: return
        Prefs.hideChannel(ch.key)
        if (previewKey == ch.key) stopPreview()
        Toast.makeText(this, "Hid \"${ch.name}\". Undo in Settings → Choose categories.", Toast.LENGTH_LONG).show()
        channels = channels.filterIndexed { i, _ -> i != pos }
        rowAdapter.notifyItemRemoved(pos)
        val groups = Repo.groups(Repo.visible(Repo.live))
        if (groups.size == catAdapter.cats.size) {
            catAdapter.replaceLabels(groups)
            catAdapter.notifyItemChanged(catAdapter.selected)
        } else {
            catAdapter.set(groups, 0); showCategory(0)
            return
        }
        val next = pos.coerceAtMost(channels.size - 1)
        if (next >= 0) rows.post { rows.layoutManager?.findViewByPosition(next)?.requestFocus() }
    }

    private fun play(pos: Int) {
        if (channels.isEmpty()) return
        stopPreview()   // free the stream first — many providers allow only one connection
        Repo.lastOpenedKey = channels.getOrNull(pos)?.key
        Repo.queue = channels
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", pos))
    }

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val name: TextView = v.findViewById(R.id.name)
            val timeline: FrameLayout = v.findViewById(R.id.timeline)
            var key = ""
        }

        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false)
            val h = VH(v)
            v.setOnClickListener { val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) onChannelClick(p) }
            v.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onChannelLong(p)
                true
            }
            v.setOnFocusChangeListener { _, f ->
                val p = h.bindingAdapterPosition
                if (f && p != RecyclerView.NO_POSITION) channels.getOrNull(p)?.let { showInfo(it) }
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = channels[position]
            h.key = ch.key
            h.name.text = ch.name
            h.img.load(ch.logo) { error(R.drawable.placeholder); fallback(R.drawable.placeholder) }
            val cached = Repo.epgCached(ch)
            if (cached != null) fill(h, cached) else {
                h.timeline.removeAllViews()
                addCell(h.timeline, if (Repo.hasGuide) "Loading…" else "No guide info", 0, windowMinutes * pxPerMin, false)
                lifecycleScope.launch {
                    val list = Repo.epg(ch)
                    if (h.key == ch.key) fill(h, list)
                }
            }
        }

        private fun fill(h: VH, list: List<EpgEntry>) {
            h.timeline.removeAllViews()
            val winEnd = winStart + windowMinutes * 60
            val now = System.currentTimeMillis() / 1000
            var any = false
            for (e in list) {
                if (e.end <= winStart || e.start >= winEnd) continue
                val s = maxOf(e.start, winStart); val en = minOf(e.end, winEnd)
                val x = ((s - winStart) / 60f) * pxPerMin
                val w = ((en - s) / 60f) * pxPerMin
                addCell(h.timeline, e.title, x.toInt(), w, e.start <= now && e.end > now)
                any = true
            }
            if (!any) addCell(h.timeline, "No guide info", 0, windowMinutes * pxPerMin, false)
        }

        private fun addCell(parent: FrameLayout, text: String, x: Int, w: Float, isNow: Boolean) {
            val tv = TextView(parent.context)
            tv.text = text
            tv.textSize = 14f
            tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setPadding(dp(8), 0, dp(6), 0)
            tv.setTextColor(ContextCompat.getColor(parent.context, R.color.text))
            tv.setBackgroundResource(if (isNow) R.drawable.prog_now_bg else R.drawable.prog_bg)
            val lp = FrameLayout.LayoutParams(maxOf(w.toInt() - dp(3), dp(4)), ViewGroup.LayoutParams.MATCH_PARENT)
            lp.leftMargin = x
            parent.addView(tv, lp)
        }
    }
}
