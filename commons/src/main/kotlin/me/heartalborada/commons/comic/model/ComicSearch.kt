package me.heartalborada.commons.comic.model

import java.util.Locale

enum class ComicCategory(val optionName: String, val filterMask: Int) {
    MISC("misc", 1),
    DOUJINSHI("doujinshi", 2),
    MANGA("manga", 4),
    ARTIST_CG("artist-cg", 8),
    GAME_CG("game-cg", 16),
    IMAGE_SET("image-set", 32),
    COSPLAY("cosplay", 64),
    ASIAN_PORN("asian-porn", 128),
    NON_H("non-h", 256),
    WESTERN("western", 512);

    companion object {
        private val byNormalizedName = entries.associateBy { normalize(it.optionName) }

        fun fromOptionName(value: String): ComicCategory? = byNormalizedName[normalize(value)]

        private fun normalize(value: String): String = value
            .trim()
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }
}

data class ComicSearchOptions(
    val categories: Set<ComicCategory> = emptySet(),
    val minStars: Int? = null,
) {
    init {
        require(minStars == null || minStars in 0..5) {
            "Minimum stars must be between 0 and 5."
        }
    }
}

data class ComicSearchResult<T>(
    val id: T,
    val title: String,
    val url: String,
    val subtitle: String? = null,
    val cover: String? = null,
    val category: String? = null,
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
