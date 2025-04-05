package me.heartalborada.commons.bots.beans

data class FileInfo(
    val name: String,
    val size: Long = -1,
    val id: String? = null,
    val url: String? = null,
)
