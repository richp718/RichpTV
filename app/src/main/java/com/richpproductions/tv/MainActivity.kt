package com.richpproductions.tv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    enum class Section { LIVE, MOVIES, SERIES, FAVS, SEARCH, SETTINGS }

    private var section: Section? = null
    private lateinit var navs: Map<Section, TextView>
    private lateinit var cats: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var header: TextView
    private lateinit var epg: TextView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var contentBox: View
    private lateinit var settingsBox: ScrollView
    private lateinit var setInfo: TextView
    private lateinit var btnFormat: Button

    private lateinit var catAdapter: CatAdapter
    private lateinit var rowAdapter: ItemAdapter
    private lateinit var posterAdapter: ItemAdapter

    private var sourceList: List<Item> = emptyList()
    private var favLists: List<List<Item>> = emptyList()
    private var shown: List<Item> = emptyList()
    private var catJob: Job? = null
    private var epgJob: Job? = null
    private var searchJob: Job? = null
    private var loaded = false

    private val time = SimpleDateFormat("h:mm a", Locale.US)

    private val setupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Prefs.hasPlaylist) loadPlaylist() else if (!loaded) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_main)

        navs = mapOf(
            Section.LIVE to findViewById(R.id.navLive),
            Section.MOVIES to findViewById(R.id.navMovies),
            Section.SERIES to findViewById(R.id.navSeries),
            Section.FAVS to findViewById(R.id.navFavs),
            Section.SEARCH to findViewById(R.id.navSearch),
            Section.SETTINGS to findViewById(R.id.navSettings)
        )
        cats = findViewById(R.id.cats)
        grid = findViewById(R.id.grid)
        header = findViewById(R.id.header)
        epg = findViewById(R.id.epg)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        contentBox = findViewById(R.id.contentBox)
        settingsBox = findViewById(R.id.settingsBox)
        setInfo = findViewById(R.id.setInfo)
        btnFormat = findViewById(R.id.btnFormat)

        for ((s, v) in navs) {
            v.setOnFocusChangeListener { _, f -> if (f && loaded) selectSection(s) }
            v.setOnClickListener {
                if (!loaded && s != Section.SETTINGS) return@setOnClickListener
                selectSection(s)
                moveRightFromNav()
            }
        }

        catAdapter = CatAdapter(
            onFocus = { i ->
                catJob?.cancel()
                catJob = lifecycleScope.launch {
                    delay(250)
                    catAdapter.select(i)
                    showCategory(i)
                }
            },
            onClick = { i ->
                catJob?.cancel()
                catAdapter.select(i)
                showCategory(i)
                focusGrid()
            }
        )
        cats.layoutManager = LinearLayoutManager(this)
        cats.itemAnimator = null
        cats.adapter = catAdapter

        rowAdapter = ItemAdapter(true, ::onItemClick, ::onItemLong, ::onItemFocus)
        posterAdapter = ItemAdapter(false, ::onItemClick, ::onItemLong, ::onItemFocus)
        grid.itemAnimator = null

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(350)
                    doSearch(s?.toString()?.trim() ?: "")
                }
            }
        })

        findViewById<Button>(R.id.btnPlaylist).setOnClickListener { openSetup() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { loadPlaylist() }
        btnFormat.setOnClickListener {
            Prefs.liveFormat = if (Prefs.liveFormat == "ts") "m3u8" else "ts"
            Toast.makeText(this, "Live format set to ${Prefs.liveFormat.uppercase()}. Reloading…", Toast.LENGTH_SHORT).show()
            loadPlaylist()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    grid.hasFocus() && cats.visibility == View.VISIBLE -> focusCats()
                    grid.hasFocus() || search.hasFocus() || cats.hasFocus() || settingsBox.hasFocus() ->
                        navs[section ?: Section.LIVE]?.requestFocus()
                    else -> finish()
                }
            }
        })

        if (Prefs.hasPlaylist) loadPlaylist() else openSetup()
    }

    override fun onResume() {
        super.onResume()
        // refresh favourite stars after returning from the player
        if (loaded && section == Section.FAVS) selectSection(Section.FAVS, true)
        else { rowAdapter.notifyDataSetChanged(); posterAdapter.notifyDataSetChanged() }
    }

    private fun openSetup() = setupLauncher.launch(Intent(this, SetupActivity::class.java))

    private fun loadPlaylist() {
        loaded = false
        status.text = "Loading your playlist…\n\nBig playlists can take up to a minute."
        status.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Repo.load()
                loaded = true
                status.visibility = View.GONE
                selectSection(Section.LIVE, true)
                navs[Section.LIVE]?.requestFocus()
            } catch (e: Exception) {
                status.visibility = View.GONE
                loaded = true
                selectSection(Section.SETTINGS, true)
                setInfo.text = "Couldn't load your playlist.\n\n${e.message ?: e.javaClass.simpleName}\n\n" + playlistInfo()
                findViewById<Button>(R.id.btnPlaylist).requestFocus()
                loaded = false
            }
        }
    }

    private fun selectSection(s: Section, force: Boolean = false) {
        if (s == section && !force) return
        section = s
        for ((k, v) in navs) v.isSelected = k == s
        epg.visibility = View.GONE
        settingsBox.visibility = if (s == Section.SETTINGS) View.VISIBLE else View.GONE
        contentBox.visibility = if (s == Section.SETTINGS) View.INVISIBLE else View.VISIBLE
        search.visibility = if (s == Section.SEARCH) View.VISIBLE else View.GONE
        cats.visibility = if (s == Section.SEARCH || s == Section.SETTINGS) View.GONE else View.VISIBLE
        when (s) {
            Section.LIVE -> setCats(Repo.live)
            Section.MOVIES -> setCats(Repo.movies)
            Section.SERIES -> setCats(Repo.series)
            Section.FAVS -> setFavCats()
            Section.SEARCH -> doSearch(search.text.toString().trim())
            Section.SETTINGS -> updateSettings()
        }
    }

    private fun setCats(list: List<Item>) {
        sourceList = list
        val groups = listOf("All" to list.size) + Repo.groups(list)
        catAdapter.set(if (list.isEmpty()) emptyList() else groups, 0)
        showCategory(0)
    }

    private fun setFavCats() {
        val favs = Prefs.favorites()
        val recent = Prefs.recent().mapNotNull { Repo.byKey(it) }
        val names = listOf("Recently watched", "Favorite channels", "Favorite movies", "Favorite series")
        favLists = listOf(
            recent,
            Repo.live.filter { it.key in favs },
            Repo.movies.filter { it.key in favs },
            Repo.series.filter { it.key in favs }
        )
        catAdapter.set(names.zip(favLists.map { it.size }), 0)
        showCategory(0)
    }

    private fun showCategory(i: Int) {
        if (section == Section.FAVS) {
            val name = catAdapter.cats.getOrNull(i)?.first ?: return
            showItems(name, favLists.getOrElse(i) { emptyList() })
            return
        }
        if (sourceList.isEmpty()) {
            showItems(if (section == Section.LIVE) "No live channels" else "Nothing here", emptyList())
            return
        }
        val name = catAdapter.cats.getOrNull(i)?.first ?: return
        showItems(name, if (i == 0) sourceList else sourceList.filter { it.group == name })
    }

    private fun showItems(title: String, items: List<Item>) {
        shown = items
        header.text = if (items.isEmpty()) "$title  ·  empty" else "$title  ·  ${NumberFormat.getInstance().format(items.size)}"
        epg.visibility = View.GONE
        val rows = items.isEmpty() || items.count { it.kind == Kind.LIVE } * 2 >= items.size
        val adapter = if (rows) rowAdapter else posterAdapter
        val span = if (rows) 2 else 4
        val lm = grid.layoutManager
        if (grid.adapter !== adapter || lm !is GridLayoutManager || lm.spanCount != span) {
            grid.layoutManager = GridLayoutManager(this, span)
            grid.adapter = adapter
        }
        adapter.items = items
        grid.scrollToPosition(0)
    }

    private fun doSearch(q: String) {
        if (section != Section.SEARCH) return
        if (q.length < 2) { showItems("Type at least 2 letters", emptyList()); return }
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) {
                (Repo.live.asSequence() + Repo.movies.asSequence() + Repo.series.asSequence())
                    .filter { it.name.contains(q, ignoreCase = true) }
                    .take(500).toList()
            }
            showItems("Results for \"$q\"", results)
        }
    }

    private fun onItemClick(pos: Int) {
        val item = shown.getOrNull(pos) ?: return
        if (item.kind == Kind.SERIES && item.seriesId != null) {
            startActivity(Intent(this, SeriesActivity::class.java)
                .putExtra("id", item.seriesId)
                .putExtra("name", item.name)
                .putExtra("cover", item.logo))
            return
        }
        if (item.url == null) return
        val queue = if (item.kind == Kind.LIVE) shown.filter { it.kind == Kind.LIVE }
                    else shown.filter { it.kind != Kind.LIVE && it.url != null }
        Repo.queue = queue
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", queue.indexOf(item).coerceAtLeast(0)))
    }

    private fun onItemLong(pos: Int) {
        val item = shown.getOrNull(pos) ?: return
        val added = Prefs.toggleFav(item.key)
        Toast.makeText(this, if (added) "★ Added to Favorites" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        grid.adapter?.notifyItemChanged(pos)
    }

    private fun onItemFocus(pos: Int) {
        val item = shown.getOrNull(pos) ?: return
        epgJob?.cancel()
        if (item.kind != Kind.LIVE || !Repo.isXtream || item.streamId == null) { epg.visibility = View.GONE; return }
        epgJob = lifecycleScope.launch {
            delay(450)
            val list = Repo.nowNext(item.streamId)
            if (list.isEmpty()) { epg.visibility = View.GONE; return@launch }
            epg.text = list.mapIndexed { i, e ->
                val label = if (i == 0) "NOW" else "NEXT"
                val t = if (e.start > 0) "${time.format(Date(e.start * 1000))} – ${time.format(Date(e.end * 1000))}" else ""
                "$label   $t   ${e.title}"
            }.joinToString("\n")
            epg.visibility = View.VISIBLE
        }
    }

    private fun playlistInfo(): String {
        return if (Prefs.mode == "xtream") "Playlist: Xtream login\nServer: ${Prefs.server}\nUser: ${Prefs.user}"
        else "Playlist: M3U link\n${Prefs.m3uUrl}"
    }

    private fun updateSettings() {
        val nf = NumberFormat.getInstance()
        setInfo.text = playlistInfo() +
            "\n\n${nf.format(Repo.live.size)} channels  ·  ${nf.format(Repo.movies.size)} movies  ·  ${nf.format(Repo.series.size)} series" +
            "\n\nVersion ${packageManager.getPackageInfo(packageName, 0).versionName}"
        btnFormat.visibility = if (Repo.isXtream) View.VISIBLE else View.GONE
        btnFormat.text = "Live stream format: ${Prefs.liveFormat.uppercase()}  (switch if channels won't play)"
    }

    private fun moveRightFromNav() {
        when (section) {
            Section.SEARCH -> search.requestFocus()
            Section.SETTINGS -> findViewById<Button>(R.id.btnPlaylist).requestFocus()
            else -> focusCats()
        }
    }

    private fun focusCats() {
        val lm = cats.layoutManager as LinearLayoutManager
        val v = lm.findViewByPosition(catAdapter.selected)
        if (v != null) v.requestFocus() else {
            cats.scrollToPosition(catAdapter.selected)
            cats.post { lm.findViewByPosition(catAdapter.selected)?.requestFocus() ?: cats.requestFocus() }
        }
    }

    private fun focusGrid() {
        grid.post { grid.layoutManager?.findViewByPosition(0)?.requestFocus() ?: grid.requestFocus() }
    }
}
