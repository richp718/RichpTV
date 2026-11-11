package com.richpproductions.tv

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Hide or show whole categories (countries, languages…), or single channels inside a Live TV category. */
class CategoriesActivity : AppCompatActivity() {

    private var kind = Kind.LIVE
    private var groups: List<Pair<String, Int>> = emptyList()
    private lateinit var adapter: CatAdapter
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var unhide: Button
    private lateinit var tabs: Map<Kind, Button>

    /** Channel mode: null = showing categories. Otherwise the category whose channels are listed,
     *  or HIDDEN_ONLY for the list of every hidden channel. */
    private var openGroup: String? = null
    private var groupPos = 0
    private var chans: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_categories)
        summary = findViewById(R.id.summary)
        title = findViewById(R.id.title)
        hint = findViewById(R.id.hint)
        unhide = findViewById(R.id.unhideChannels)
        tabs = mapOf(
            Kind.LIVE to findViewById(R.id.tabLive),
            Kind.MOVIE to findViewById(R.id.tabMovies),
            Kind.SERIES to findViewById(R.id.tabSeries)
        )
        for ((k, b) in tabs) b.setOnClickListener { closeGroup(); showKind(k) }

        adapter = CatAdapter(
            onFocus = { },
            onClick = click@{ i ->
                if (openGroup != null) {
                    val ch = chans.getOrNull(i) ?: return@click
                    val hid = Prefs.hiddenChannels()
                    Prefs.setHiddenChannels(if (ch.key in hid) hid - ch.key else hid + ch.key)
                    refreshChannels(keep = true)
                    adapter.notifyItemChanged(i)
                    return@click
                }
                val g = groups.getOrNull(i)?.first ?: return@click
                Prefs.toggleHidden(kind, g)
                refresh(keep = true)
                adapter.notifyItemChanged(i)
            },
            onLong = long@{ i ->
                if (openGroup != null || kind != Kind.LIVE) return@long
                val g = groups.getOrNull(i)?.first ?: return@long
                groupPos = i
                openGroup(g)
            }
        )
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter

        findViewById<Button>(R.id.hideAll).setOnClickListener {
            if (openGroup != null) {
                Prefs.setHiddenChannels(Prefs.hiddenChannels() + chans.map { it.key }); refreshChannels()
            } else { Prefs.setHidden(kind, groups.map { it.first }.toSet()); refresh() }
        }
        findViewById<Button>(R.id.showAll).setOnClickListener {
            if (openGroup != null) {
                Prefs.setHiddenChannels(Prefs.hiddenChannels() - chans.map { it.key }.toSet()); refreshChannels()
            } else { Prefs.setHidden(kind, emptySet()); refresh() }
        }
        unhide.setOnClickListener {
            if (Prefs.hiddenChannels().isEmpty()) {
                Toast.makeText(this, "No hidden channels", Toast.LENGTH_SHORT).show()
            } else openGroup(HIDDEN_ONLY)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openGroup != null) {
                    closeGroup()
                    showKind(Kind.LIVE)
                    list.scrollToPosition(groupPos)
                    list.post { list.layoutManager?.findViewByPosition(groupPos)?.requestFocus() }
                } else finish()
            }
        })
        showKind(Kind.LIVE)
        tabs[Kind.LIVE]?.requestFocus()
    }

    private fun showKind(k: Kind) {
        kind = k
        for ((kk, b) in tabs) b.isSelected = kk == k
        groups = Repo.allGroups(k)
        refresh()
    }

    private fun refresh(keep: Boolean = false) {
        updateUnhide()
        val hid = Prefs.hidden(kind)
        val labels = groups.map { (g, c) -> (if (g in hid) "✕   $g   — hidden" else "✓   $g") to c }
        adapter.showCounts = true
        if (keep) {
            // update labels in place so focus stays where it is
            adapter.replaceLabels(labels)
        } else adapter.set(labels, -1)
        val shown = groups.count { it.first !in hid }
        summary.text = "Showing $shown of ${groups.size} categories"
    }

    private fun openGroup(g: String) {
        openGroup = g
        kind = Kind.LIVE
        for ((kk, b) in tabs) b.isSelected = kk == Kind.LIVE
        val hidNow = Prefs.hiddenChannels()
        chans = if (g == HIDDEN_ONLY) Repo.live.filter { it.key in hidNow } else Repo.live.filter { it.group == g }
        title.text = if (g == HIDDEN_ONLY) "Hidden channels" else g
        hint.text = "Click a channel to hide or show it. Press Back to return to categories."
        refreshChannels()
        list.scrollToPosition(0)
        list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun closeGroup() {
        if (openGroup == null) return
        openGroup = null
        title.text = "Choose categories"
        hint.text = "Click a category to hide or show it. Hold OK on a Live TV category to pick single channels inside it."
    }

    private fun refreshChannels(keep: Boolean = false) {
        updateUnhide()
        val hid = Prefs.hiddenChannels()
        val labels = chans.map { (if (it.key in hid) "✕   ${it.name}   — hidden" else "✓   ${it.name}") to 0 }
        adapter.showCounts = false
        if (keep) adapter.replaceLabels(labels) else adapter.set(labels, -1)
        val shown = chans.count { it.key !in hid }
        val catHidden = openGroup != HIDDEN_ONLY && openGroup in Prefs.hidden(Kind.LIVE)
        summary.text = "Showing $shown of ${chans.size} channels" + if (catHidden) "   ·   (this whole category is hidden)" else ""
    }

    private fun updateUnhide() {
        unhide.text = "Hidden channels (${Prefs.hiddenChannels().size})"
    }

    companion object { private const val HIDDEN_ONLY = "\u0000hidden" }
}
