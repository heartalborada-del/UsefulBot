package me.heartalborada.commons.comic.model

data class ComicSearchResult<T>(
    val id: T,
    val title: String,
    val url: String,
    val subtitle: String? = null,
    val cover: String? = null,
    val tags: List<String> = emptyList(),
    val pages: Int? = null,
    val rating: Double? = null,
)

data class ComicSearchPage<T>(
    val results: List<ComicSearchResult<T>>,
    val page: Int,
    val total: Int? = null,
    val hasNextPage: Boolean = false,
) {
    init {
        require(page > 0) { "Search page must be greater than zero." }
    }
}
