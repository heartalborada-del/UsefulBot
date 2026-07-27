package me.heartalborada.commands

import me.heartalborada.commons.comic.model.ComicCategory
import me.heartalborada.commons.comic.model.ComicSearchOptions

internal data class SearchCommandArguments(
    val keyword: String,
    val page: Int,
    val options: ComicSearchOptions,
)

internal fun parseSearchCommandArguments(
    input: String,
    allowGalleryFilters: Boolean,
): SearchCommandArguments {
    var page: Int? = null
    var minStars: Int? = null
    val categories = linkedSetOf<ComicCategory>()
    val keywordParts = mutableListOf<String>()

    input.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .forEach { argument ->
            when {
                argument.startsWith("--page=", ignoreCase = true) -> {
                    require(page == null) { "The page option may only be specified once." }
                    val value = argument.substringAfter('=').toIntOrNull()
                    require(value != null && value > 0) { "Search page must be greater than zero." }
                    page = value
                }

                argument.startsWith("--category=", ignoreCase = true) -> {
                    require(allowGalleryFilters) { "Category filtering is only supported by E-Hentai." }
                    val values = argument.substringAfter('=').split(',')
                    require(values.isNotEmpty() && values.none(String::isBlank)) {
                        "At least one category is required."
                    }
                    values.forEach { value ->
                        categories += ComicCategory.fromOptionName(value)
                            ?: throw IllegalArgumentException("Unknown category: $value")
                    }
                }

                argument.startsWith("--min-stars=", ignoreCase = true) -> {
                    require(allowGalleryFilters) { "Star filtering is only supported by E-Hentai." }
                    require(minStars == null) { "The minimum-stars option may only be specified once." }
                    val value = argument.substringAfter('=').toIntOrNull()
                    require(value != null && value in 0..5) { "Minimum stars must be between 0 and 5." }
                    minStars = value
                }

                argument.startsWith("--") -> {
                    throw IllegalArgumentException("Unknown search option: $argument")
                }

                else -> keywordParts += argument
            }
        }

    val keyword = keywordParts.joinToString(" ").trim()
    require(keyword.isNotEmpty()) { "Search keyword must not be blank." }
    return SearchCommandArguments(
        keyword = keyword,
        page = page ?: 1,
        options = ComicSearchOptions(
            categories = categories,
            minStars = minStars,
        ),
    )
}
