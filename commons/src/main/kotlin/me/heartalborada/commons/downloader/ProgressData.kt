package me.heartalborada.commons.downloader

data class ProgressData(
    val name: String,
    var total: Long = -1,
    var downloaded: List<Pair<Long, Long>> = mutableListOf(),
)
