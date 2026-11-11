package com.richpproductions.tv

enum class Kind { LIVE, MOVIE, SERIES }

data class Item(
    val key: String,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String?,
    val kind: Kind,
    val streamId: String? = null,
    val seriesId: String? = null,
    val epgId: String? = null
)

data class EpgEntry(val title: String, val start: Long, val end: Long)
