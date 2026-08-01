package me.heartalborada.bots

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import me.heartalborada.commons.bots.*
import me.heartalborada.commons.bots.dto.FileInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `serializes all directly sendable NapCat message segments`() {
        val chain = MessageChain().apply {
            add(Image(FileInfo("cover.jpg", id = "image-id", url = "https://ignored.test"), "cover", "0"))
            add(Record(FileInfo("voice.amr", id = "record-id")))
            add(Video(FileInfo("video.mp4", url = "https://example.test/video.mp4"), "thumb-id"))
            add(Music.Standard(Music.Source.NETEASE, "123"))
            add(Music.Custom("https://example.test", "https://example.test/a.mp3", "Song", singer = "Singer"))
            add(MarketFace("emoji", "package", "key", "face"))
            add(File(FileInfo("document.pdf", id = "reusable-file-id", url = "https://ignored.test")))
            add(Forward("forward-id"))
        }

        val segments = JsonParser.parseString(gson.toJson(chain, MessageChain::class.java)).asJsonArray

        assertEquals("image-id", segments[0].asJsonObject.getAsJsonObject("data")["file"].asString)
        assertEquals("record-id", segments[1].asJsonObject.getAsJsonObject("data")["file"].asString)
        assertEquals("https://example.test/video.mp4", segments[2].asJsonObject.getAsJsonObject("data")["file"].asString)
        assertEquals("163", segments[3].asJsonObject.getAsJsonObject("data")["type"].asString)
        assertEquals("custom", segments[4].asJsonObject.getAsJsonObject("data")["type"].asString)
        assertEquals("mface", segments[5].asJsonObject["type"].asString)
        assertEquals("reusable-file-id", segments[6].asJsonObject.getAsJsonObject("data")["file"].asString)
        assertEquals("forward-id", segments[7].asJsonObject.getAsJsonObject("data")["id"].asString)
    }

    @Test
    fun `parses NapCat media music market face and node metadata`() {
        val chain = parse(
            """
                [
                  {"type":"record","data":{"name":"voice.amr","file":"voice","file_id":"record-id","url":"https://voice","path":"/voice","file_size":"12","file_unique":"unique-record"}},
                  {"type":"video","data":{"name":"video.mp4","file":"video","file_id":"video-id","thumb":"thumb-id"}},
                  {"type":"shake","data":{}},
                  {"type":"music","data":{"type":"qq","id":"123"}},
                  {"type":"music","data":{"type":"custom","url":"https://page","audio":"https://audio","title":"Song","image":"https://image","singer":"Singer"}},
                  {"type":"mface","data":{"emoji_id":"emoji","emoji_package_id":"package","key":"key","summary":"face"}},
                  {"type":"node","data":{"user_id":"42","nickname":"Alice","content":[{"type":"text","data":{"text":"hello"}}]}},
                  {"type":"file","data":{"name":"document.pdf","file":"legacy","file_id":"file-id","path":"/file","url":"https://file","file_size":"34","file_unique":"unique-file"}}
                ]
            """.trimIndent(),
        )

        val record = assertIs<Record>(chain[0])
        assertEquals("record-id", record.info.id)
        assertEquals("/voice", record.info.path)
        assertEquals("unique-record", record.info.uniqueId)
        assertEquals("thumb-id", assertIs<Video>(chain[1]).thumbnail)
        assertIs<Shake>(chain[2])
        assertEquals(Music.Source.QQ, assertIs<Music.Standard>(chain[3]).source)
        assertEquals("Singer", assertIs<Music.Custom>(chain[4]).singer)
        assertEquals("package", assertIs<MarketFace>(chain[5]).emojiPackageId)
        val node = assertIs<ForwardNode>(chain[6])
        assertEquals(42L, node.userId)
        assertEquals("hello", assertIs<PlainText>(node.content!!.single()).text)
        val file = assertIs<File>(chain[7])
        assertEquals("file-id", file.info.id)
        assertEquals("unique-file", file.info.uniqueId)
    }

    @Test
    fun `markdown is available inside merged forward content only`() {
        val forwardGson = GsonBuilder()
            .registerTypeAdapter(MessageChain::class.java, MessageChainTypeAdapter(allowMarkdown = true))
            .create()
        val chain = MessageChain().apply { add(Markdown("**message**")) }

        val segment = JsonParser.parseString(forwardGson.toJson(chain, MessageChain::class.java))
            .asJsonArray.single().asJsonObject

        assertEquals("markdown", segment["type"].asString)
        assertEquals("**message**", segment.getAsJsonObject("data")["content"].asString)
    }

    @Test
    fun `receive-only message segments are not serialized for direct sending`() {
        val chain = MessageChain().apply {
            add(Shake())
            add(Poke(1, 2))
            add(Share("Example", "https://example.test", null, null))
            add(Location("1", "2", null, null))
        }

        assertEquals("[]", gson.toJson(chain, MessageChain::class.java))
    }

    private fun parse(json: String): MessageChain =
        gson.fromJson(json, MessageChain::class.java)
}
