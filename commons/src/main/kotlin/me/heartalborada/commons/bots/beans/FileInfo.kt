package me.heartalborada.commons.bots.beans

data class FileInfo(
    val name: String,
    val size: Long,
    val id: String,
    val url: String? = null,
)
