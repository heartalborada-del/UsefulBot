import me.heartalborada.commons.comic.model.ComicSearchResult
import me.heartalborada.i18n.PropertiesTranslator
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchResultFormattingTest {
    @Test
    fun `formats a localized result and groups tag namespaces`() {
        val text = formatSearchResult(
            source = "eh",
            result = ComicSearchResult(
                id = "4078683" to "d02aff1a3e",
                title = "Example",
                url = "https://e-hentai.org/g/4078683/d02aff1a3e/",
                subtitle = "uploader",
                category = "Doujinshi",
                pages = 6,
                rating = 1.5,
                tags = listOf(
                    "parody:blue archive",
                    "female:big breasts",
                    "female:sole female",
                ),
            ),
            index = 9,
            commandOperator = '/',
            translator = PropertiesTranslator("zh-CN"),
        )

        assertEquals(
            """
                #9
                标题：Example
                类型：Doujinshi
                页数：6
                评分：1.5

                标签：
                  parody: blue archive
                  female: big breasts, sole female

                链接：https://e-hentai.org/g/4078683/d02aff1a3e/
                获取：`/get eh https://e-hentai.org/g/4078683/d02aff1a3e/`
            """.trimIndent(),
            text,
        )
    }
}
