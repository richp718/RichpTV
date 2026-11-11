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

    var epgUrl: String
        get() = str("epgUrl")
        set(v) = put("epgUrl", v)

    // ----- hidden categories -----
    private val hiddenCache = HashMap<Kind, MutableSet<String>>()

    fun hidden(kind: Kind): Set<String> =
        hiddenCache.getOrPut(kind) { (sp.getStringSet("hid_" + kind.name, emptySet()) ?: emptySet()).toMutableSet() }

    fun setHidden(kind: Kind, groups: Set<String>) {
        hiddenCache[kind] = groups.toMutableSet()
        sp.edit().putStringSet("hid_" + kind.name, HashSet(groups)).apply()
    }

    fun toggleHidden(kind: Kind, group: String): Boolean {
        val s = hidden(kind).toMutableSet()
        val nowHidden = if (s.remove(group)) false else { s.add(group); true }
        setHidden(kind, s)
        return nowHidden
    }

    fun isHidden(item: Item) = hidden(item.kind).contains(item.group) || hiddenChannels().contains(item.key)

    // ----- hidden single channels -----
    private var hidChCache: MutableSet<String>? = null

    fun hiddenChannels(): Set<String> =
        hidChCache ?: (sp.getStringSet("hid_channels", emptySet()) ?: emptySet()).toMutableSet().also { hidChCache = it }

    fun setHiddenChannels(keys: Set<String>) {
        hidChCache = keys.toMutableSet()
        sp.edit().putStringSet("hid_channels", HashSet(keys)).apply()
    }

    fun hideChannel(key: String) = setHiddenChannels(hiddenChannels() + key)

    // ----- last episode watched per series -----
    fun lastEpisode(seriesKey: String): String = str("last_$seriesKey")
    fun setLastEpisode(seriesKey: String, episodeKey: String) = put("last_$seriesKey", episodeKey)

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
