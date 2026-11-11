package com.richpproductions.tv

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Hide or show whole categories (countries, languages…). */
class CategoriesActivity : AppCompatActivity() {

    private var kind = Kind.LIVE
    private var groups: List<Pair<String, Int>> = emptyList()
    private lateinit var adapter: CatAdapter
    private lateinit var summary: TextView
    private lateinit var tabs: Map<Kind, Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_categories)
        summary = findViewById(R.id.summary)
        tabs = mapOf(
            Kind.LIVE to findViewById(R.id.tabLive),
            Kind.MOVIE to findViewById(R.id.tabMovies),
            Kind.SERIES to findViewById(R.id.tabSeries)
        )
        for ((k, b) in tabs) b.setOnClickListener { showKind(k) }

        adapter = CatAdapter(onFocus = { }, onClick = click@{ i ->
            val g = groups.getOrNull(i)?.first ?: return@click
            Prefs.toggleHidden(kind, g)
            refresh(keep = true)
            adapter.notifyItemChanged(i)
        })
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter

        findViewById<Button>(R.id.hideAll).setOnClickListener {
            Prefs.setHidden(kind, groups.map { it.first }.toSet()); refresh()
        }
        findViewById<Button>(R.id.showAll).setOnClickListener {
            Prefs.setHidden(kind, emptySet()); refresh()
        }
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
        val hid = Prefs.hidden(kind)
        val labels = groups.map { (g, c) -> (if (g in hid) "✕   $g   — hidden" else "✓   $g") to c }
        if (keep) {
            // update labels in place so focus stays where it is
            adapter.replaceLabels(labels)
        } else adapter.set(labels, -1)
        val shown = groups.count { it.first !in hid }
        summary.text = "Showing $shown of ${groups.size} categories"
    }
}
