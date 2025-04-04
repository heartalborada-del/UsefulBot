package me.heartalborada.commons.bots.beans

data class UserInfo(
    val userID: Long,
    val username: String,
    val role: String? = null,
    val card: String? = null,
)
