package com.richpproductions.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class CatAdapter(
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit,
    private val onLong: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<CatAdapter.VH>() {

    var cats: List<Pair<String, Int>> = emptyList()
        private set
    var selected = 0
        private set

    class VH(val t: TextView) : RecyclerView.ViewHolder(t)

    fun set(list: List<Pair<String, Int>>, sel: Int = 0) {
        cats = list; selected = sel
        notifyDataSetChanged()
    }

    /** Change the text of the rows without rebuilding the list (keeps focus). */
    fun replaceLabels(list: List<Pair<String, Int>>) {
        cats = list
    }

    fun select(i: Int) {
        if (i == selected) return
        val old = selected
        selected = i
        notifyItemChanged(old)
        notifyItemChanged(i)
    }

    override fun getItemCount() = cats.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val t = LayoutInflater.from(parent.context).inflate(R.layout.item_cat, parent, false) as TextView
        val h = VH(t)
        t.setOnFocusChangeListener { _, f ->
            val p = h.bindingAdapterPosition
            if (f && p != RecyclerView.NO_POSITION) onFocus(p)
        }
        t.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        t.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            val cb = onLong
            if (cb != null && p != RecyclerView.NO_POSITION) { cb(p); true } else false
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val (name, count) = cats[position]
        h.t.text = "$name  ($count)"
        h.t.isSelected = position == selected
    }
}

class ItemAdapter(
    private val rows: Boolean,
    private val onClick: (Int) -> Unit,
    private val onLong: (Int) -> Unit,
    private val onFocus: (Int) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
        val star: TextView = v.findViewById(R.id.star)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(if (rows) R.layout.item_channel else R.layout.item_poster, parent, false)
        val h = VH(v)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(p)
            true
        }
        v.setOnFocusChangeListener { view, f ->
            val s = if (f) (if (rows) 1.03f else 1.07f) else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            view.elevation = if (f) 12f else 0f
            val p = h.bindingAdapterPosition
            if (f && p != RecyclerView.NO_POSITION) onFocus(p)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.name.text = it.name
        h.star.visibility = if (Prefs.isFav(it.key)) View.VISIBLE else View.GONE
        h.img.load(it.logo) {
            crossfade(true)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
            fallback(R.drawable.placeholder)
        }
    }
}
