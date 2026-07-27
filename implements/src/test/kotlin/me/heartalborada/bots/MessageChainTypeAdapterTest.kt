package me.heartalborada.bots

import com.google.gson.GsonBuilder
import me.heartalborada.commons.bots.File
import me.heartalborada.commons.bots.Image
import me.heartalborada.commons.bots.Location
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

    private fun parse(json: String): MessageChain =
        gson.fromJson(json, MessageChain::class.java)
}
