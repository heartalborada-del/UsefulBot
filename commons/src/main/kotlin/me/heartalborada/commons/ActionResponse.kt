package me.heartalborada.commons

data class ActionResponse<T>(
    val status: Boolean,
    val code: Int,
    val message: String?,
    val data: T? = null
)
