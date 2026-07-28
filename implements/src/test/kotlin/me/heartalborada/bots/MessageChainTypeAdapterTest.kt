package me.heartalborada.bots

import com.google.gson.GsonBuilder
import me.heartalborada.commons.bots.File
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.Location
import me.heartalborada.commons.bots.Markdown
import me.heartalborada.commons.bots.MessageChain
import me.heartalborada.commons.bots.Share
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageChainTypeAdapterTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(MessageChain::class.java, MessageChainTypeAdapter())
        .create()

    @Test
    fun `image without file size uses unknown size`() {
        val chain = parse(
            """
                [
                  {
                    "type": "image",
                    "data": {
                      "file": "image.jpg",
                      "url": "https://example.test/image.jpg"
                    }
                  }
                ]
            """.trimIndent()
        )

        val image = chain.single() as Image
        assertEquals("image.jpg", image.info.name)
        assertEquals(-1, image.info.size)
        assertEquals("image.jpg", image.info.id)
        assertEquals("https://example.test/image.jpg", image.info.url)
    }

    @Test
    fun `nullable message fields may be omitted`() {
        val chain = parse(
            """
                [
                  {"type":"file","data":{"file":"document.pdf"}},
                  {"type":"share","data":{"title":"Example","url":"https://example.test"}},
                  {"type":"location","data":{"lat":"1.0","lon":"2.0"}}
                ]
            """.trimIndent()
        )

        val file = chain[0] as File
        assertEquals(-1, file.info.size)
        assertNull(file.info.id)

        val share = chain[1] as Share
        assertNull(share.content)
        assertNull(share.imageUrl)

        val location = chain[2] as Location
        assertNull(location.title)
        assertNull(location.content)
    }

    @Test
    fun `markdown segment renders plain text`() {
        val content = """
            [](%7B%22version%22%3A2%7D)
            [@someone](mqqapi://markdown/mention?at_type=1&at_tinyid=1194929728)
             **message** with `code`
            ![image #120px #120px](https://example.test/image.png)

            ##### title

            > quoted _text_
        """.trimIndent()
        val json = """[{"type":"markdown","data":{"content":${gson.toJson(content)}}}]"""

        val chain = parse(json)
        val markdown = chain.single() as Markdown

        assertEquals(content, markdown.content)
        assertEquals(
            """
                @someone
                 message with code
                [Image]

                title

                quoted text
            """.trimIndent(),
            markdown.toString()
        )
    }

    @Test
    fun `markdown segment is silently omitted when sending through NapCat`() {
        val chain = MessageChain().apply {
            add(Markdown("**message**"))
        }

        assertEquals("[]", gson.toJson(chain, MessageChain::class.java))
    }

    private fun parse(json: String): MessageChain =
        gson.fromJson(json, MessageChain::class.java)
}
