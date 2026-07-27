package me.heartalborada.telegraph

import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegraphPublisherTest {
    @Test
    fun `uploads comic pages creates an account and caches the preview`() {
        var uploadCount = 0
        var accountCount = 0
        var pageCount = 0
        var pageContent = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val responseBody = when (request.url.encodedPath) {
                    "/upload" -> {
                        uploadCount++
                        """[{"src":"/file/page-$uploadCount.png"}]"""
                    }
                    "/createAccount" -> {
                        accountCount++
                        """{"ok":true,"result":{"access_token":"test-token"}}"""
                    }
                    "/createPage" -> {
                        pageCount++
                        val form = request.body as FormBody
                        val contentIndex = (0 until form.size).first { form.name(it) == "content" }
                        pageContent = form.value(contentIndex)
                        """{"ok":true,"result":{"url":"https://telegra.ph/test-preview"}}"""
                    }
                    else -> error("Unexpected request: ${request.url}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val directory = Files.createTempDirectory("telegraph-publisher-test-")
        val pages = (1..3).map { index ->
            directory.resolve("page-$index.png").also { path ->
                val image = BufferedImage(32, 48, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().also { graphics ->
                    graphics.color = Color(index * 40, 20, 30)
                    graphics.fillRect(0, 0, image.width, image.height)
                    graphics.dispose()
                }
                ImageIO.write(image, "png", path.toFile())
            }.toFile()
        }
        try {
            val publisher = TelegraphPublisher(
                client = client,
                apiBaseUrl = "https://api.telegraph.test",
                uploadUrl = "https://upload.telegraph.test/upload",
            )

            val first = publisher.publish("Test Comic", "comic-1", pages)
            val cached = publisher.publish("Test Comic", "comic-1", pages)

            assertEquals("https://telegra.ph/test-preview", first)
            assertEquals(first, cached)
            assertEquals(3, uploadCount)
            assertEquals(1, accountCount)
            assertEquals(1, pageCount)
            val content = JsonParser.parseString(pageContent).asJsonArray
            assertEquals(3, content.size())
            assertTrue(
                content.all {
                    it.asJsonObject.get("tag").asString == "img" &&
                        it.asJsonObject.getAsJsonObject("attrs").get("src").asString
                            .startsWith("https://telegra.ph/file/page-")
                }
            )
        } finally {
            pages.forEach { it.toPath().deleteIfExists() }
            directory.deleteIfExists()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
