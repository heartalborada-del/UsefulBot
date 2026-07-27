package me.heartalborada.commons.bots.dto

data class InlineQueryResult(
    val id: String,
    val title: String,
    val message: String,
    val description: String? = null,
    val url: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Inline query result ID must not be blank." }
        require(title.isNotBlank()) { "Inline query result title must not be blank." }
        require(message.isNotBlank()) { "Inline query result message must not be blank." }
    }
}
