package me.heartalborada.comics

import com.google.gson.JsonParser
import java.awt.Color
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JMComicTest {
    private val provider = JMComic(domains = listOf("18comic.example"))

    @Test
    fun `parses JM IDs and supported URL forms`() {
        assertEquals("123456", provider.parseUrl("123456"))
        assertEquals("123456", provider.parseUrl("JM123456"))
        assertEquals("123456", provider.parseUrl("jM123456"))
        assertEquals("123456", provider.parseUrl("https://18comic.vip/album/123456/"))
        assertEquals("654321", provider.parseUrl("https://18comic.vip/photo/654321"))
        assertEquals("123456", provider.parseUrl("https://18comic.vip/album?id=123456"))
        assertFailsWith<IllegalArgumentException> { provider.parseUrl("not-a-jm-id") }
    }

    @Test
    fun `parses album and chapter HTML used by the reference client`() {
        val album = provider.parseAlbumHtml(
            "123456",
            """
                <html>
                <head><meta property="og:image" content="https://cdn.example/media/albums/123456.jpg"></head>
                <body>
                  <h1 id="book-name">Example Album</h1>
                  <h2>敘述：Album description</h2>
                  <span class="pagecount">頁數:3</span>
                  <span>上架日期 : 2025-06-01</span>
                  <span data-type="author"><a>Author A</a></span>
                  <span data-type="tags"><a>Tag A</a><a>Tag B</a></span>
                  <a data-album="123456">第1話 Start</a>
                  <a data-album="123457">第2話 End</a>
                </body>
                </html>
            """.trimIndent()
        )
        assertEquals("Example Album", album.title)
        assertEquals(3, album.pageCount)
        assertEquals(listOf("Author A"), album.authors)
        assertEquals(listOf("Tag A", "Tag B"), album.tags)
        assertEquals(listOf("123456", "123457"), album.episodes.map { it.photoId })

        val photo = provider.parsePhotoHtml(
            "123456",
            """
                <html>
                <head><title>Chapter One | JMComic</title></head>
                <body>
                  <script>
                    var scramble_id = 220980;
                    var page_arr = ["00001.jpg", "00002.webp"];
                  </script>
                  <img data-original="https://cdn.example/media/photos/123456/00001.jpg">
                </body>
                </html>
            """.trimIndent()
        )
        assertEquals(220980, photo.scrambleId)
        assertEquals("cdn.example", photo.imageDomain)
        assertEquals(listOf("00001.jpg", "00002.webp"), photo.fileNames)
    }

    @Test
    fun `parses mobile API album and chapter payloads`() {
        val album = provider.parseApiAlbum(
            JsonParser.parseString(
                """
                    {
                      "id":"123456",
                      "name":"API Album",
                      "description":"Description",
                      "author":["Author A"],
                      "tags":["Tag A","Tag B"],
                      "works":["Work A"],
                      "actors":["Actor A"],
                      "series":[
                        {"id":"123458","name":"Chapter 2","sort":"2"},
                        {"id":"123457","name":"Chapter 1","sort":"1"}
                      ]
                    }
                """.trimIndent()
            ).asJsonObject
        )
        assertEquals("API Album", album.title)
        assertEquals(listOf("Author A"), album.authors)
        assertEquals(listOf("123457", "123458"), album.episodes.map { it.photoId })

        val photo = provider.parseApiPhoto(
            JsonParser.parseString(
                """{"id":"123457","name":"Chapter 1","images":["00001.jpg","00002.webp"]}"""
            ).asJsonObject,
            220980,
        )
        assertEquals(220980, photo.scrambleId)
        assertEquals(listOf("00001.jpg", "00002.webp"), photo.fileNames)
    }

    @Test
    fun `decrypts mobile API payload using the reference algorithm`() {
        val timestamp = "1700000000"
        val plainText = """{"id":"123456","name":"API Album"}"""
        val key = MessageDigest.getInstance("MD5")
            .digest((timestamp + "185Hcomic3PAPP7R").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))

        assertEquals(plainText, provider.decryptApiData(encrypted, timestamp))
    }

    @Test
    fun `ports the reference scramble segment calculation`() {
        assertEquals(0, provider.calculateScrambleSegments(220980, "200000", "00001"))
        assertEquals(10, provider.calculateScrambleSegments(220980, "250000", "00001"))
        assertEquals(16, provider.calculateScrambleSegments(220980, "300000", "00001"))
        assertEquals(12, provider.calculateScrambleSegments(220980, "500000", "00001"))
    }

    @Test
    fun `restores vertically scrambled image segments`() {
        val original = BufferedImage(2, 5, BufferedImage.TYPE_INT_RGB)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        colors.forEachIndexed { y, color ->
            repeat(original.width) { x -> original.setRGB(x, y, color.rgb) }
        }

        val scrambled = BufferedImage(2, 5, BufferedImage.TYPE_INT_RGB)
        val sourceRows = listOf(3, 4, 0, 1, 2)
        sourceRows.forEachIndexed { y, originalY ->
            repeat(original.width) { x -> scrambled.setRGB(x, y, original.getRGB(x, originalY)) }
        }

        val decoded = provider.decodeImage(scrambled, 2)
        repeat(original.height) { y ->
            repeat(original.width) { x ->
                assertEquals(original.getRGB(x, y), decoded.getRGB(x, y))
            }
        }
    }
}
