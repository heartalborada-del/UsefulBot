package me.heartalborada.commons.comic

import me.heartalborada.commons.bots.RpsResult

data class ComicInformation<T>(
    val id: T,
    val title: String,
    val subtitle: String?,
    val tags: Map<String,List<String>>,
    val category: Category,
    val cover: String,
    val pages: Int,
    val rating: Double,
    val uploader: String,
    val uploadTime: Long,
    val extra: Map<String, String>? = null,
) {
    enum class Category(val s: String) {
        Doujinshi("Doujinshi"),
        Manga("Manga"),
        ArtistCG("Artist CG"),
        GameCG("Game CG"),
        NonH("Non-H"),
        ImageSet("Image Set"),
        Western("Western"),
        Cosplay("Cosplay"),
        Miscellaneous("Misc"),
        Private("Private");

        companion object {
            fun fromValue(value: String): Category {
                return Category.entries.first { it.s == value }
            }
        }
    }
}
