package com.richpproductions.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private var seasons: List<Pair<String, List<Item>>> = emptyList()
    private lateinit var seasonAdapter: CatAdapter
    private lateinit var episodeAdapter: ItemAdapter
    private lateinit var episodes: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_series)
        val id = intent.getStringExtra("id") ?: return finish()
        findViewById<TextView>(R.id.title).text = intent.getStringExtra("name") ?: ""
        findViewById<ImageView>(R.id.cover).load(intent.getStringExtra("cover")) {
            crossfade(true); error(R.drawable.placeholder); fallback(R.drawable.placeholder)
        }
        val status = findViewById<TextView>(R.id.status)
        val seasonList = findViewById<RecyclerView>(R.id.seasons)
        episodes = findViewById(R.id.episodes)

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
            onClick = { pos ->
                val list = episodeAdapter.items
                Repo.queue = list
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", pos))
            },
            onLong = { pos ->
                val it = episodeAdapter.items[pos]
                Prefs.savePosition(it.key, 0)
                Toast.makeText(this, "Will start from the beginning", Toast.LENGTH_SHORT).show()
            },
            onFocus = { }
        )
        episodes.layoutManager = LinearLayoutManager(this)
        episodes.itemAnimator = null
        episodes.adapter = episodeAdapter

        lifecycleScope.launch {
            try {
                seasons = Repo.seriesEpisodes(id)
                if (seasons.isEmpty()) { status.text = "No episodes found for this series."; return@launch }
                status.visibility = View.GONE
                seasonAdapter.set(seasons.map { it.first to it.second.size }, 0)
                showSeason(0)
                seasonList.post { seasonList.layoutManager?.findViewByPosition(0)?.requestFocus() }
            } catch (e: Exception) {
                status.text = "Couldn't load episodes.\n${e.message ?: ""}"
            }
        }
    }

    private fun showSeason(i: Int) {
        val list = seasons.getOrNull(i)?.second ?: return
        if (episodeAdapter.items !== list) episodeAdapter.items = list
    }
}
