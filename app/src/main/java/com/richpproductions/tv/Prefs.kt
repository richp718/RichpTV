package com.richpproductions.tv

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences
    private var favCache: MutableSet<String>? = null

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("richptv", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var mode: String
        get() = str("mode")
        set(v) = put("mode", v)
    var m3uUrl: String
        get() = str("m3u")
        set(v) = put("m3u", v)
    var server: String
        get() = str("server")
        set(v) = put("server", v)
    var user: String
        get() = str("user")
        set(v) = put("user", v)
    var pass: String
        get() = str("pass")
        set(v) = put("pass", v)
    var liveFormat: String
        get() = str("liveFormat", "ts")
        set(v) = put("liveFormat", v)

    val hasPlaylist: Boolean
        get() = (mode == "m3u" && m3uUrl.isNotBlank()) ||
                (mode == "xtream" && server.isNotBlank() && user.isNotBlank())

    fun favorites(): Set<String> {
        val c = favCache ?: (sp.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet().also { favCache = it }
        return c
    }

    fun isFav(key: String) = favorites().contains(key)

    fun toggleFav(key: String): Boolean {
        val s = favorites().toMutableSet()
        val added = if (s.remove(key)) false else { s.add(key); true }
        favCache = s
        sp.edit().putStringSet("favs", HashSet(s)).apply()
        return added
    }

    fun position(key: String): Long = sp.getLong("pos_" + key.hashCode(), 0L)
    fun savePosition(key: String, pos: Long) = sp.edit().putLong("pos_" + key.hashCode(), pos).apply()

    fun recent(): List<String> = str("recent").split("\n").filter { it.isNotBlank() }
    fun addRecent(key: String) {
        val l = recent().toMutableList()
        l.remove(key)
        l.add(0, key)
        put("recent", l.take(40).joinToString("\n"))
    }
}
