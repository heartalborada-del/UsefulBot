package me.heartalborada.comics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EHentaiSearchTest {
    private val provider = EHentai()

    @Test
    fun `parses extended gallery search results`() {
        val result = provider.parseSearchHtml(
            """
                <html>
                <body>
                  <table class="itg glte"><tbody>
                    <tr>
                      <td class="gl1e">
                        <div><a href="/g/123456/abcdef/"><img data-src="https://img.example/cover.jpg"></a></div>
                      </td>
                      <td class="gl2e">
                        <div>
                          <a href="/g/123456/abcdef/"><div><div class="glink">Example Gallery</div></div></a>
                          <div class="gl3e">
                            <div><a href="/?f_search=uploader%3Atester">tester</a></div>
                            <div class="ir" style="background-position:-16px -21px"></div>
                            <div>42 pages</div>
                          </div>
                          <div class="gt" title="language:chinese"></div>
                          <div class="gtl" title="artist:someone"></div>
                        </div>
                      </td>
                    </tr>
                  </tbody></table>
                  <a id="dnext" href="/?next=123">Next</a>
                </body>
                </html>
            """.trimIndent(),
        )

        assertEquals(1, result.results.size)
        assertEquals("123456" to "abcdef", result.results.single().id)
        assertEquals("Example Gallery", result.results.single().title)
        assertEquals("tester", result.results.single().subtitle)
        assertEquals(42, result.results.single().pages)
        assertEquals(3.5, result.results.single().rating)
        assertEquals(listOf("language:chinese", "artist:someone"), result.results.single().tags)
        assertTrue(result.hasNextPage)
    }
}
