package com.richpproductions.tv

import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser

object Repo {
    const val UA = "VLC/3.0.20 LibVLC/3.0.20"

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
        set(v) { field = v; queueSeriesKey = null }

    /** The channel/movie/series the user last opened, so lists can put the highlight back on it. */
    @Volatile var lastOpenedKey: String? = null

    /** Set when the queue is a series' episodes, so the player can remember progress. */
    var queueSeriesKey: String? = null

    fun byKey(k: String): Item? = index[k]

    // ---------------- Saved copy of the playlist (fast start) ----------------

    private const val CACHE_VERSION = 1
    /** What the data in memory belongs to; empty when nothing is loaded. */
    @Volatile var loadedIdentity = ""
        private set
    @Volatile var loadedAt = 0L
        private set

    fun identity() = listOf(Prefs.mode, Prefs.m3uUrl, Prefs.server, Prefs.user, Prefs.pass).joinToString("|")

    /** True when the playlist in memory is the current one. */
    val isLoaded: Boolean get() = loadedIdentity.isNotEmpty() && loadedIdentity == identity()

    private fun cacheFile(dir: File) = File(dir, "playlist.bin")

    suspend fun saveCache(dir: File) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(dir, "playlist.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { o ->
                o.writeInt(CACHE_VERSION)
                o.writeUTF(loadedIdentity)
                o.writeLong(loadedAt)
                o.writeUTF(m3uEpgUrl)
                for (k in Kind.values()) {
                    val ord = order[k] ?: emptyList()
                    o.writeInt(ord.size); for (g in ord) o.writeUTF(g)
                }
                for (list in listOf(live, movies, series)) {
                    o.writeInt(list.size)
                    for (i in list) {
                        o.writeUTF(i.key); o.writeUTF(i.name); o.writeUTF(i.logo ?: ""); o.writeUTF(i.group)
                        o.writeUTF(i.url ?: ""); o.writeInt(i.kind.ordinal); o.writeUTF(i.streamId ?: "")
                        o.writeUTF(i.seriesId ?: ""); o.writeUTF(i.epgId ?: "")
                    }
                }
            }
            tmp.renameTo(cacheFile(dir))
        } catch (e: Exception) { }
    }

    /** Load the saved playlist if it belongs to the current login. */
    suspend fun loadCache(dir: File): Boolean = withContext(Dispatchers.IO) {
        val f = cacheFile(dir)
        if (!f.exists()) return@withContext false
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { r ->
                if (r.readInt() != CACHE_VERSION) return@withContext false
                val id = r.readUTF()
                if (id != identity()) return@withContext false
                val at = r.readLong()
                val epgUrl = r.readUTF()
                val ord = HashMap<Kind, List<String>>()
                for (k in Kind.values()) { val n = r.readInt(); ord[k] = List(n) { r.readUTF() } }
                val kinds = Kind.values()
                fun e(v: String) = v.ifEmpty { null }
                val lists = List(3) {
                    val n = r.readInt()
                    val out = ArrayList<Item>(n)
                    repeat(n) {
                        val key = r.readUTF(); val name = r.readUTF(); val logo = e(r.readUTF()); val group = r.readUTF()
                        val url = e(r.readUTF()); val kind = kinds[r.readInt()]; val sid = e(r.readUTF())
                        val ser = e(r.readUTF()); val epg = e(r.readUTF())
                        out.add(Item(key, name, logo, group, url, kind, sid, ser, epg))
                    }
                    out
                }
                live = lists[0]; movies = lists[1]; series = lists[2]
                order = ord
                m3uEpgUrl = epgUrl
                index = (live + movies + series).associateBy { it.key }
                epgCache.clear()
                xmltv = null
                loadedIdentity = id
                loadedAt = at
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        epgCache.clear()
        xmltv = null
        m3uEpgUrl = ""
        val id = identity()
        if (Prefs.mode == "xtream") loadXtream() else loadM3u()
        index = (live + movies + series).associateBy { it.key }
        loadedIdentity = id
        loadedAt = System.currentTimeMillis()
    }

    /** Items whose category (or single channel) the user hasn't hidden. */
    fun visible(list: List<Item>): List<Item> {
        val kind = list.firstOrNull()?.kind ?: return list
        val hid = Prefs.hidden(kind)
        val hidCh = if (kind == Kind.LIVE) Prefs.hiddenChannels() else emptySet()
        return if (hid.isEmpty() && hidCh.isEmpty()) list
               else list.filter { it.group !in hid && it.key !in hidCh }
    }

    private fun swapExt(url: String, from: String, to: String): String? {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val rest = if (q >= 0) url.substring(q) else ""
        if (!path.endsWith(from, true)) return null
        return path.substring(0, path.length - from.length) + to + rest
    }

    /** Different ways to open the same stream (url, isHls): TS, HLS, as-is. Tried in order until one plays. */
    fun streamCandidates(item: Item): List<Pair<String, Boolean>> {
        val url = item.url ?: return emptyList()
        val out = LinkedHashSet<Pair<String, Boolean>>()
        if (item.kind == Kind.LIVE) {
            val tsUrl = swapExt(url, ".m3u8", ".ts") ?: (if (swapExt(url, ".ts", ".ts") != null) url else null)
            val hlsUrl = swapExt(url, ".ts", ".m3u8") ?: (if (swapExt(url, ".m3u8", ".m3u8") != null) url else null)
            val ts = tsUrl?.let { it to false }
            val hls = hlsUrl?.let { it to true }
            if (Prefs.liveFormat == "m3u8") { hls?.let { out.add(it) }; ts?.let { out.add(it) } }
            else { ts?.let { out.add(it) }; hls?.let { out.add(it) } }
        }
        out.add(url to false)
        out.add(url to true)
        return out.toList()
    }

    /** Every category for a kind (hidden ones included), in the provider's order. */
    fun allGroups(kind: Kind): List<Pair<String, Int>> = groups(
        when (kind) { Kind.LIVE -> live; Kind.MOVIE -> movies; Kind.SERIES -> series })

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
                    line.startsWith("#EXTM3U", true) -> {
                        sawHeader = true
                        val a = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        m3uEpgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: "").split(',').firstOrNull()?.trim() ?: ""
                    }
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

    // ---------------- Details (movie / series info) ----------------

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (o.isNull(k)) continue
            val a = o.optJSONArray(k)
            if (a != null) { if (a.length() > 0) { val v = a.optString(0, ""); if (v.isNotBlank()) return v }; continue }
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun prettyDuration(raw: String, secs: String = ""): String {
        var total = secs.toLongOrNull() ?: 0L
        if (total <= 0 && raw.contains(":")) {
            val p = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
            total = when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> 0 }
        }
        if (total <= 0) {
            val mins = raw.filter { it.isDigit() }.toLongOrNull() ?: return ""
            total = mins * 60
        }
        val h = total / 3600; val m = (total % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** "2025-03-04" → "Mar 4, 2025" (leaves other formats as they are). */
    private fun prettyDate(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty() || v.startsWith("0000")) return ""
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v.take(10)) ?: return v
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(d)
        } catch (e: Exception) { v }
    }

    private fun metaLine(year: String, genre: String, duration: String, rating: String): String {
        val r = rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) } ?: ""
        return listOf(year.take(4), genre, duration, r).filter { it.isNotBlank() }.joinToString("   ·   ")
    }

    private fun basicDetails(item: Item) = Details(item.name, item.logo, null,
        "No description was provided for this title.", item.group, "")

    suspend fun movieDetails(item: Item): Details = withContext(Dispatchers.IO) {
        if (!isXtream || item.streamId == null) return@withContext basicDetails(item)
        try {
            val o = JSONObject(fetch(api("get_vod_info", "&vod_id=${item.streamId}")))
            val info = o.optJSONObject("info") ?: return@withContext basicDetails(item)
            val plot = firstString(info, "plot", "description").ifBlank { "No description was provided for this title." }
            val cast = firstString(info, "cast", "actors")
            val dir = firstString(info, "director")
            Details(
                title = firstString(info, "name", "o_name").ifBlank { item.name },
                poster = firstString(info, "movie_image", "cover_big").ifBlank { item.logo ?: "" }.ifBlank { null },
                backdrop = firstString(info, "backdrop_path").ifBlank { null },
                plot = plot,
                meta = metaLine(firstString(info, "releasedate", "release_date", "year"), firstString(info, "genre"),
                    prettyDuration(firstString(info, "duration"), firstString(info, "duration_secs")), firstString(info, "rating")),
                cast = listOf(if (cast.isNotBlank()) "Cast: $cast" else "", if (dir.isNotBlank()) "Director: $dir" else "")
                    .filter { it.isNotBlank() }.joinToString("\n")
            )
        } catch (e: Exception) { basicDetails(item) }
    }

    /** Series info plus seasons → episodes (Xtream). */
    suspend fun seriesDetails(item: Item): Pair<Details, List<Pair<String, List<Item>>>> = withContext(Dispatchers.IO) {
        val seriesId = item.seriesId ?: return@withContext basicDetails(item) to emptyList()
        val o = JSONObject(fetch(api("get_series_info", "&series_id=$seriesId")))
        val info = o.optJSONObject("info") ?: JSONObject()
        val cast = firstString(info, "cast")
        val details = Details(
            title = firstString(info, "name").ifBlank { item.name },
            poster = firstString(info, "cover").ifBlank { item.logo ?: "" }.ifBlank { null },
            backdrop = firstString(info, "backdrop_path").ifBlank { null },
            plot = firstString(info, "plot").ifBlank { "No description was provided for this series." },
            meta = metaLine(firstString(info, "releaseDate", "release_date"), firstString(info, "genre"), "", firstString(info, "rating")),
            cast = if (cast.isNotBlank()) "Cast: $cast" else ""
        )
        val eps = o.optJSONObject("episodes") ?: return@withContext details to emptyList()
        val b = base(); val user = u(); val pass = p()
        val seasons = eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }.map { season ->
            val a = eps.optJSONArray(season) ?: JSONArray()
            val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
                val id = js(e, "id")
                val ext = js(e, "container_extension").ifBlank { "mp4" }
                val num = js(e, "episode_num")
                val ei = e.optJSONObject("info") ?: JSONObject()
                val dur = prettyDuration(firstString(ei, "duration"), firstString(ei, "duration_secs"))
                val title = js(e, "title").ifBlank { "Episode $num" }
                val aired = prettyDate(firstString(ei, "air_date", "airdate", "releasedate", "release_date", "releaseDate"))
                val extra = listOf(dur, if (aired.isNotBlank()) "Aired $aired" else "").filter { it.isNotBlank() }
                Item("E:$id", "E$num   ·   $title" + extra.joinToString("") { "   ·   $it" },
                    firstString(ei, "movie_image").ifBlank { null }, "Season $season",
                    "$b/series/$user/$pass/$id.$ext", Kind.MOVIE)
            }
            "Season $season" to list
        }.filter { it.second.isNotEmpty() }
        details to seasons
    }

    // ---------------- TV guide ----------------

    private val epgCache = ConcurrentHashMap<String, List<EpgEntry>>()
    private val epgGate = Semaphore(4)
    @Volatile private var m3uEpgUrl = ""
    @Volatile private var xmltv: Map<String, List<EpgEntry>>? = null
    private val xmltvLock = Mutex()

    val hasGuide: Boolean get() = isXtream || Prefs.epgUrl.isNotBlank() || m3uEpgUrl.isNotBlank()

    fun epgCached(item: Item): List<EpgEntry>? = epgCache[item.key]

    /** Programmes for a live channel, from now on (cached). */
    suspend fun epg(item: Item): List<EpgEntry> {
        epgCache[item.key]?.let { return it }
        val list = epgGate.withPermit {
            if (isXtream && item.streamId != null) shortEpg(item.streamId) else xmltvFor(item)
        }
        val now = System.currentTimeMillis() / 1000
        val upcoming = list.filter { it.end > now }.sortedBy { it.start }
        epgCache[item.key] = upcoming
        return upcoming
    }

    private suspend fun shortEpg(streamId: String): List<EpgEntry> = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(fetch(api("get_short_epg", "&stream_id=$streamId&limit=12")))
            val a = o.optJSONArray("epg_listings") ?: return@withContext emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
                EpgEntry(b64(js(e, "title")),
                    js(e, "start_timestamp").toLongOrNull() ?: 0L,
                    js(e, "stop_timestamp").toLongOrNull() ?: 0L,
                    b64(js(e, "description")))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun xmltvFor(item: Item): List<EpgEntry> {
        val id = item.epgId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val m = loadXmltv()
        return m[id] ?: m[id.lowercase()] ?: emptyList()
    }

    private suspend fun loadXmltv(): Map<String, List<EpgEntry>> = xmltvLock.withLock {
        xmltv?.let { return@withLock it }
        var url = Prefs.epgUrl.ifBlank { m3uEpgUrl }.trim()
        if (url.isBlank()) return@withLock emptyMap()
        if (!url.startsWith("http", true)) url = "http://$url"
        val parsed = withContext(Dispatchers.IO) {
            try { parseXmltv(url) } catch (e: Exception) { emptyMap() }
        }
        xmltv = parsed
        parsed
    }

    private fun parseXmltv(url: String): Map<String, List<EpgEntry>> {
        val wanted = HashSet<String>()
        for (c in live) c.epgId?.takeIf { it.isNotBlank() }?.let { wanted.add(it); wanted.add(it.lowercase()) }
        val fmtZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun t(s: String?): Long {
            val v = s?.trim() ?: return 0L
            return try { fmtZ.parse(v)!!.time / 1000 } catch (e: Exception) {
                try { fmt.parse(v.take(14))!!.time / 1000 } catch (e2: Exception) { 0L }
            }
        }
        val now = System.currentTimeMillis() / 1000
        val from = now - 3 * 3600
        val to = now + 14 * 3600
        val map = HashMap<String, ArrayList<EpgEntry>>()
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return emptyMap()
            var input: InputStream = BufferedInputStream(r.body!!.byteStream())
            input.mark(4)
            val b1 = input.read(); val b2 = input.read()
            input.reset()
            if (b1 == 0x1f && b2 == 0x8b) input = GZIPInputStream(input)
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var ch: String? = null
            var start = 0L; var stop = 0L
            var title = ""; var desc = ""; var tag = ""
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "programme") {
                            ch = parser.getAttributeValue(null, "channel")
                            start = t(parser.getAttributeValue(null, "start"))
                            stop = t(parser.getAttributeValue(null, "stop"))
                            title = ""; desc = ""
                        }
                    }
                    XmlPullParser.TEXT -> if (ch != null) {
                        if (tag == "title" && title.isEmpty()) title = parser.text ?: ""
                        else if (tag == "desc" && desc.isEmpty()) desc = parser.text ?: ""
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme") {
                            val c = ch
                            if (c != null && (c in wanted) && stop > from && start < to)
                                map.getOrPut(c) { ArrayList() }.add(EpgEntry(title.trim(), start, stop, desc.trim()))
                            ch = null
                        }
                        tag = ""
                    }
                }
                ev = parser.next()
            }
        }
        for (l in map.values) l.sortBy { it.start }
        return map
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) { s }

    val isXtream get() = Prefs.mode == "xtream"
}
