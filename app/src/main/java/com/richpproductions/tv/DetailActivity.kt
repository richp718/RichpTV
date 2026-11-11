package com.richpproductions.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Info page for a movie or a series: poster, description, Play. */
class DetailActivity : AppCompatActivity() {

    private lateinit var item: Item
    private lateinit var btnPlay: Button
    private lateinit var btnRestart: Button
    private lateinit var btnFav: Button
    private lateinit var seasonAdapter: CatAdapter
    private lateinit var episodeAdapter: ItemAdapter
    private lateinit var episodes: RecyclerView
    private var seasons: List<Pair<String, List<Item>>> = emptyList()
    private var currentSeason = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_detail)
        val found = Repo.byKey(intent.getStringExtra("key") ?: "")
        if (found == null) { finish(); return }
        item = found

        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnFav = findViewById(R.id.btnFav)
        episodes = findViewById(R.id.episodes)

        show(Details(item.name, item.logo, null, "Loading details…", "", ""))
        updateFav()
        btnFav.setOnClickListener {
            val added = Prefs.toggleFav(item.key)
            updateFav()
            Toast.makeText(this, if (added) "★ Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        }

        if (item.kind == Kind.SERIES && item.seriesId != null) setupSeries() else setupMovie()
        btnPlay.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (::item.isInitialized) {
            if (item.kind == Kind.SERIES && item.seriesId != null) updateSeriesPlay() else updateMoviePlay()
        }
    }

    private fun show(d: Details) {
        findViewById<TextView>(R.id.title).text = d.title
        findViewById<TextView>(R.id.meta).apply { text = d.meta; visibility = if (d.meta.isBlank()) View.GONE else View.VISIBLE }
        findViewById<TextView>(R.id.plot).text = d.plot
        findViewById<TextView>(R.id.cast).apply { text = d.cast; visibility = if (d.cast.isBlank()) View.GONE else View.VISIBLE }
        findViewById<ImageView>(R.id.poster).load(d.poster) {
            crossfade(true); error(R.drawable.placeholder); fallback(R.drawable.placeholder)
        }
        val bd = findViewById<ImageView>(R.id.backdrop)
        if (d.backdrop != null || d.poster != null) bd.load(d.backdrop ?: d.poster) { crossfade(true) }
    }

    private fun updateFav() {
        btnFav.text = if (Prefs.isFav(item.key)) "★  In Favorites" else "☆  Add to Favorites"
    }

    // ---------- Movie ----------

    private fun setupMovie() {
        btnPlay.setOnClickListener { playMovie(fromStart = false) }
        btnRestart.setOnClickListener { playMovie(fromStart = true) }
        updateMoviePlay()
        lifecycleScope.launch { show(Repo.movieDetails(item)) }
    }

    private fun updateMoviePlay() {
        val resume = Prefs.position(item.key) > 0
        btnPlay.text = if (resume) "▶  Resume" else "▶  Play"
        btnRestart.visibility = if (resume) View.VISIBLE else View.GONE
    }

    private fun playMovie(fromStart: Boolean) {
        if (item.url == null) return
        if (fromStart) Prefs.savePosition(item.key, 0)
        Repo.queue = listOf(item)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", 0))
    }

    // ---------- Series ----------

    private fun setupSeries() {
        val box = findViewById<View>(R.id.seriesBox)
        val seasonList = findViewById<RecyclerView>(R.id.seasons)
        btnPlay.text = "▶  Play"
        btnPlay.isEnabled = false

        seasonAdapter = CatAdapter(
            onFocus = { i -> seasonAdapter.select(i); showSeason(i) },
            onClick = { i ->
                seasonAdapter.select(i); showSeason(i)
                episodes.post { episodes.layoutManager?.findViewByPosition(0)?.requestFocus() }
            }
        )
        seasonList.layoutManager = LinearLayoutManager(this)
        seasonList.itemAnimator = null
        seasonList.adapter = seasonAdapter

        episodeAdapter = ItemAdapter(true,
            onClick = { pos -> playEpisode(currentSeason, pos) },
            onLong = { pos ->
                val e = episodeAdapter.items[pos]
                Prefs.savePosition(e.key, 0)
                Toast.makeText(this, "This episode will start from the beginning", Toast.LENGTH_SHORT).show()
            },
            onFocus = { }
        )
        episodes.layoutManager = LinearLayoutManager(this)
        episodes.itemAnimator = null
        episodes.adapter = episodeAdapter

        btnPlay.setOnClickListener {
            val (s, e) = continuePoint()
            playEpisode(s, e)
        }

        lifecycleScope.launch {
            try {
                val (d, list) = Repo.seriesDetails(item)
                show(d)
                seasons = list
                if (list.isEmpty()) {
                    btnPlay.text = "No episodes yet"
                    return@launch
                }
                box.visibility = View.VISIBLE
                seasonAdapter.set(list.map { it.first to it.second.size }, 0)
                val (s, _) = continuePoint()
                seasonAdapter.select(s)
                showSeason(s)
                btnPlay.isEnabled = true
                updateSeriesPlay()
            } catch (e: Exception) {
                findViewById<TextView>(R.id.plot).text = "Couldn't load this series.\n${e.message ?: ""}"
            }
        }
    }

    /** Season and episode to continue from (last watched, else the very first). */
    private fun continuePoint(): Pair<Int, Int> {
        val last = Prefs.lastEpisode(item.key)
        if (last.isNotBlank()) {
            seasons.forEachIndexed { si, (_, eps) ->
                val ei = eps.indexOfFirst { it.key == last }
                if (ei >= 0) return si to ei
            }
        }
        return 0 to 0
    }

    private fun updateSeriesPlay() {
        if (seasons.isEmpty()) return
        val last = Prefs.lastEpisode(item.key)
        if (last.isBlank()) { btnPlay.text = "▶  Play Season 1"; return }
        val (s, e) = continuePoint()
        val num = seasons[s].first.removePrefix("Season ").trim()
        btnPlay.text = "▶  Continue  S$num  E${e + 1}"
    }

    private fun showSeason(i: Int) {
        val list = seasons.getOrNull(i)?.second ?: return
        currentSeason = i
        if (episodeAdapter.items !== list) episodeAdapter.items = list
    }

    private fun playEpisode(season: Int, index: Int) {
        val list = seasons.getOrNull(season)?.second ?: return
        if (index !in list.indices) return
        Prefs.setLastEpisode(item.key, list[index].key)
        Repo.queue = list
        Repo.queueSeriesKey = item.key
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", index))
    }
}
