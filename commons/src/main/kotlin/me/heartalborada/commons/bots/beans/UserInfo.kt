package me.heartalborada.commons.bots.beans

data class UserInfo(
    val userID: Long,
    val username: String,
    val role: String,
    val card: String? = null,
)
