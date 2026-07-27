import me.heartalborada.commons.comic.model.ComicCategory
import me.heartalborada.commands.parseSearchCommandArguments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchCommandArgumentsTest {
    @Test
    fun `parses page categories stars and official tag syntax`() {
        val arguments = parseSearchCommandArguments(
            input = """
                --page=2 --category=doujinshi,manga --category=artistcg
                --min-stars=4 language:chinese artist:"some artist"
            """.trimIndent(),
            allowGalleryFilters = true,
        )

        assertEquals(2, arguments.page)
        assertEquals(4, arguments.options.minStars)
        assertEquals(
            setOf(ComicCategory.DOUJINSHI, ComicCategory.MANGA, ComicCategory.ARTIST_CG),
            arguments.options.categories,
        )
        assertEquals("""language:chinese artist:"some artist"""", arguments.keyword)
    }

    @Test
    fun `rejects invalid or unsupported filters`() {
        assertFailsWith<IllegalArgumentException> {
            parseSearchCommandArguments(
                "--category=unknown keyword",
                allowGalleryFilters = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseSearchCommandArguments(
                "--category=manga keyword",
                allowGalleryFilters = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseSearchCommandArguments(
                "--min-stars=6 keyword",
                allowGalleryFilters = true,
            )
        }
    }
}
