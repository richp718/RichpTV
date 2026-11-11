package com.richpproductions.tv

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Repo {
    const val UA = "RichpTV/1.0 (Linux; Android)"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile var live: List<Item> = emptyList()
    @Volatile var movies: List<Item> = emptyList()
    @Volatile var series: List<Item> = emptyList()
    private var order: Map<Kind, List<String>> = emptyMap()
    private var index: Map<String, Item> = emptyMap()

    /** Items the player is working through (channel list or episodes). */
    var queue: List<Item> = emptyList()

    fun byKey(k: String): Item? = index[k]

    suspend fun load() = withContext(Dispatchers.IO) {
        if (Prefs.mode == "xtream") loadXtream() else loadM3u()
        index = (live + movies + series).associateBy { it.key }
    }

    /** Categories with item counts, in the provider's order. */
    fun groups(list: List<Item>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (i in list) counts[i.group] = (counts[i.group] ?: 0) + 1
        val kind = list.firstOrNull()?.kind ?: return emptyList()
        val wanted = order[kind] ?: return counts.toList()
        val out = ArrayList<Pair<String, Int>>()
        for (g in wanted) counts[g]?.let { out.add(g to it) }
        for ((g, c) in counts) if (g !in wanted) out.add(g to c)
        return out
    }

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The server answered with error ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    // ---------------- M3U ----------------

    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val MOVIE_GROUP = Regex("\\b(vod|movies?|films?|cinema)\\b", RegexOption.IGNORE_CASE)
    private val SERIES_GROUP = Regex("\\b(series|tv shows?|seasons?)\\b", RegexOption.IGNORE_CASE)

    private fun guessKind(url: String, group: String): Kind {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return when {
            "/series/" in u -> Kind.SERIES
            "/movie/" in u -> Kind.MOVIE
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ->
                if (SERIES_GROUP.containsMatchIn(group)) Kind.SERIES else Kind.MOVIE
            SERIES_GROUP.containsMatchIn(group) -> Kind.SERIES
            MOVIE_GROUP.containsMatchIn(group) -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    private fun loadM3u() {
        var url = Prefs.m3uUrl.trim()
        if (!url.startsWith("http", true)) url = "http://$url"
        val l = ArrayList<Item>(); val m = ArrayList<Item>(); val s = ArrayList<Item>()
        val groupOrder = HashMap<Kind, LinkedHashSet<String>>()
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The server answered with error ${r.code}")
            val body = r.body ?: throw IOException("The playlist is empty")
            var name: String? = null
            var logo: String? = null
            var group = ""
            var epg = ""
            var sawHeader = false
            body.charStream().buffered().forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTM3U", true) -> sawHeader = true
                    line.startsWith("#EXTINF", true) -> {
                        sawHeader = true
                        val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        val q = line.lastIndexOf('"')
                        val c = line.indexOf(',', if (q >= 0) q else 0)
                        name = if (c >= 0) line.substring(c + 1).trim() else "Channel"
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                        group = attrs["group-title"] ?: group
                        epg = attrs["tvg-id"] ?: ""
                    }
                    line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                    line.isNotEmpty() && !line.startsWith("#") -> {
                        val n = name
                        if (n != null) {
                            val g = group.ifBlank { "Other" }
                            val kind = guessKind(line, g)
                            val item = Item("${kind.name[0]}:$line", n.ifBlank { "Channel" }, logo, g, line, kind, epgId = epg)
                            when (kind) {
                                Kind.LIVE -> l.add(item)
                                Kind.MOVIE -> m.add(item)
                                Kind.SERIES -> s.add(item)
                            }
                            groupOrder.getOrPut(kind) { LinkedHashSet() }.add(g)
                        }
                        name = null; logo = null; group = ""; epg = ""
                    }
                }
            }
            if (!sawHeader) throw IOException("That link didn't return an M3U playlist. Check the link.")
        }
        if (l.isEmpty() && m.isEmpty() && s.isEmpty()) throw IOException("The playlist loaded but has no channels in it.")
        live = l; movies = m; series = s
        order = groupOrder.mapValues { it.value.toList() }
    }

    // ---------------- Xtream ----------------

    private fun base(): String {
        var s = Prefs.server.trim().trimEnd('/')
        if (!s.startsWith("http", true)) s = "http://$s"
        return s
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun u() = Prefs.user.trim()
    private fun p() = Prefs.pass.trim()

    private fun api(action: String? = null, extra: String = ""): String {
        val a = if (action != null) "&action=$action" else ""
        return "${base()}/player_api.php?username=${enc(u())}&password=${enc(p())}$a$extra"
    }

    private fun js(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

    private fun arr(action: String): List<JSONObject> {
        val t = fetch(api(action)).trim()
        if (!t.startsWith("[")) return emptyList()
        val a = JSONArray(t)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun cats(action: String): List<Pair<String, String>> =
        arr(action).map { js(it, "category_id") to js(it, "category_name").ifBlank { "Other" } }

    private fun loadXtream() {
        val authText = try { fetch(api()) } catch (e: IOException) {
            throw IOException("Couldn't reach the server. Check the server address.\n(${e.message})")
        }
        val auth = try { JSONObject(authText) } catch (e: Exception) {
            throw IOException("The server didn't answer like an Xtream server. Check the server address.")
        }
        val ui = auth.optJSONObject("user_info")
        if (ui == null || ui.optInt("auth", 1) == 0) throw IOException("Login failed. Check your username and password.")

        val b = base(); val user = u(); val pass = p(); val fmt = Prefs.liveFormat
        val newOrder = HashMap<Kind, List<String>>()

        val lc = cats("get_live_categories"); val lcm = lc.toMap(); newOrder[Kind.LIVE] = lc.map { it.second }
        live = arr("get_live_streams").map { o ->
            val id = js(o, "stream_id")
            Item("L:$id", js(o, "name"), js(o, "stream_icon").ifBlank { null }, lcm[js(o, "category_id")] ?: "Other",
                "$b/live/$user/$pass/$id.$fmt", Kind.LIVE, streamId = id, epgId = js(o, "epg_channel_id"))
        }

        val mc = cats("get_vod_categories"); val mcm = mc.toMap(); newOrder[Kind.MOVIE] = mc.map { it.second }
        movies = arr("get_vod_streams").map { o ->
            val id = js(o, "stream_id")
            val ext = js(o, "container_extension").ifBlank { "mp4" }
            Item("M:$id", js(o, "name"), js(o, "stream_icon").ifBlank { null }, mcm[js(o, "category_id")] ?: "Other",
                "$b/movie/$user/$pass/$id.$ext", Kind.MOVIE, streamId = id)
        }

        val sc = cats("get_series_categories"); val scm = sc.toMap(); newOrder[Kind.SERIES] = sc.map { it.second }
        series = arr("get_series").map { o ->
            val id = js(o, "series_id")
            Item("S:$id", js(o, "name"), js(o, "cover").ifBlank { null }, scm[js(o, "category_id")] ?: "Other",
                null, Kind.SERIES, seriesId = id)
        }
        order = newOrder
    }

    /** Seasons → episodes for an Xtream series. */
    suspend fun seriesEpisodes(seriesId: String): List<Pair<String, List<Item>>> = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch(api("get_series_info", "&series_id=$seriesId")))
        val eps = o.optJSONObject("episodes") ?: return@withContext emptyList()
        val b = base(); val user = u(); val pass = p()
        eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }.map { season ->
            val a = eps.optJSONArray(season) ?: JSONArray()
            val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
                val id = js(e, "id")
                val ext = js(e, "container_extension").ifBlank { "mp4" }
                val num = js(e, "episode_num")
                val info = e.optJSONObject("info") ?: JSONObject()
                Item("E:$id", "Episode $num  ·  ${js(e, "title")}", js(info, "movie_image").ifBlank { null },
                    "Season $season", "$b/series/$user/$pass/$id.$ext", Kind.MOVIE)
            }
            "Season $season" to list
        }.filter { it.second.isNotEmpty() }
    }

    /** Now / next programme for an Xtream live channel. */
    suspend fun nowNext(streamId: String): List<EpgEntry> = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(fetch(api("get_short_epg", "&stream_id=$streamId&limit=2")))
            val a = o.optJSONArray("epg_listings") ?: return@withContext emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
                EpgEntry(b64(js(e, "title")),
                    js(e, "start_timestamp").toLongOrNull() ?: 0L,
                    js(e, "stop_timestamp").toLongOrNull() ?: 0L)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) { s }

    val isXtream get() = Prefs.mode == "xtream"
}
