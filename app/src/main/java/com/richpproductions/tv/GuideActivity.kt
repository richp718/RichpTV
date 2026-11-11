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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grid TV guide: channels down the side, programmes across a 3-hour timeline. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_guide)
        rows = findViewById(R.id.rows)
        infoTitle = findViewById(R.id.infoTitle)
        infoTime = findViewById(R.id.infoTime)
        infoDesc = findViewById(R.id.infoDesc)

        val now = System.currentTimeMillis() / 1000
        winStart = now - (now % 1800)   // round down to the half hour

        val dm = resources.displayMetrics
        val timelinePx = dm.widthPixels - dp(220) - dp(24) - dp(210) - dp(10)
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

    private fun play(pos: Int) {
        if (channels.isEmpty()) return
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
            v.setOnClickListener { val p = h.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) play(p) }
            v.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    val added = Prefs.toggleFav(channels[p].key)
                    Toast.makeText(this@GuideActivity, if (added) "★ Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
                }
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
