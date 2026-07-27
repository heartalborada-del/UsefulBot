package me.heartalborada.telegraph

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max

class TelegraphPublisher(
    private val client: OkHttpClient,
    configuredAccessToken: String = "",
    private val authorName: String = "UsefulBot",
    private val authorUrl: String = "",
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val uploadUrl: String = DEFAULT_UPLOAD_URL,
) {
    private val gson = Gson()
    private val accountLock = Any()
    private val publishedPages = ConcurrentHashMap<String, String>()

    @Volatile
    private var accessToken = configuredAccessToken.trim()

    fun publish(title: String, cacheKey: String, pages: List<File>): String {
        require(pages.isNotEmpty()) { "Telegraph preview requires at least one comic page." }
        val versionedKey = buildString {
            append(cacheKey)
            pages.forEach {
                append(':').append(it.length()).append(':').append(it.lastModified())
            }
        }
        return publishedPages.computeIfAbsent(versionedKey) {
            publishUncached(title, pages)
        }
    }

    private fun publishUncached(title: String, pages: List<File>): String {
        val imageUrls = pages.mapIndexed { index, page ->
            require(page.isFile) { "Comic page does not exist: ${page.absolutePath}" }
            uploadImage(page, index)
        }
        val chunks = imageUrls.chunked(MAX_IMAGES_PER_PAGE)
        var nextPageUrl: String? = null
        for (index in chunks.indices.reversed()) {
            val pageTitle = if (chunks.size == 1) {
                title
            } else {
                "$title (${index + 1}/${chunks.size})"
            }
            nextPageUrl = createPage(
                title = pageTitle,
                imageUrls = chunks[index],
                nextPageUrl = nextPageUrl,
            )
        }
        return checkNotNull(nextPageUrl)
    }

    private fun uploadImage(file: File, index: Int): String {
        val prepared = prepareImage(file)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "page-${index + 1}.${prepared.extension}",
                prepared.body,
            )
            .build()
        val response = executeJson(
            Request.Builder()
                .url(uploadUrl)
                .post(multipart)
                .build()
        )
        val item = response
            .takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: error("Telegraph image upload returned an invalid response: $response")
        val source = item.get("src")?.asString
            ?: error(item.get("error")?.asString ?: "Telegraph image upload did not return a URL.")
        return when {
            source.startsWith("https://") || source.startsWith("http://") -> source
            source.startsWith("/") -> "https://telegra.ph$source"
            else -> "https://telegra.ph/$source"
        }
    }

    private fun createPage(
        title: String,
        imageUrls: List<String>,
        nextPageUrl: String?,
    ): String {
        val content = JsonArray().apply {
            imageUrls.forEach { imageUrl ->
                add(
                    JsonObject().apply {
                        addProperty("tag", "img")
                        add(
                            "attrs",
                            JsonObject().apply {
                                addProperty("src", imageUrl)
                            }
                        )
                    }
                )
            }
            nextPageUrl?.let { url ->
                add(
                    JsonObject().apply {
                        addProperty("tag", "p")
                        add(
                            "children",
                            JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty("tag", "a")
                                        add(
                                            "attrs",
                                            JsonObject().apply {
                                                addProperty("href", url)
                                            }
                                        )
                                        add(
                                            "children",
                                            JsonArray().apply {
                                                add("Continue to the next part →")
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
        val form = FormBody.Builder()
            .add("access_token", getAccessToken())
            .add("title", title.take(MAX_TITLE_LENGTH))
            .add("author_name", authorName.take(MAX_AUTHOR_NAME_LENGTH))
            .add("content", gson.toJson(content))
            .add("return_content", "false")
            .apply {
                authorUrl.takeIf(String::isNotBlank)?.let {
                    add("author_url", it.take(MAX_AUTHOR_URL_LENGTH))
                }
            }
            .build()
        val response = executeJson(
            Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/createPage")
                .post(form)
                .build()
        ).asJsonObject
        check(response.get("ok")?.asBoolean == true) {
            "Telegraph createPage failed: ${response.get("error")?.asString ?: response}"
        }
        return response.getAsJsonObject("result").get("url").asString
    }

    private fun getAccessToken(): String {
        accessToken.takeIf(String::isNotBlank)?.let { return it }
        return synchronized(accountLock) {
            accessToken.takeIf(String::isNotBlank) ?: createAccount().also {
                accessToken = it
            }
        }
    }

    private fun createAccount(): String {
        val form = FormBody.Builder()
            .add("short_name", "UsefulBot")
            .add("author_name", authorName.take(MAX_AUTHOR_NAME_LENGTH))
            .apply {
                authorUrl.takeIf(String::isNotBlank)?.let {
                    add("author_url", it.take(MAX_AUTHOR_URL_LENGTH))
                }
            }
            .build()
        val response = executeJson(
            Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/createAccount")
                .post(form)
                .build()
        ).asJsonObject
        check(response.get("ok")?.asBoolean == true) {
            "Telegraph createAccount failed: ${response.get("error")?.asString ?: response}"
        }
        return response.getAsJsonObject("result").get("access_token").asString
    }

    private fun prepareImage(file: File): PreparedImage {
        val extension = file.extension.lowercase()
        val originalMediaType = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> null
        }
        if (originalMediaType != null && file.length() <= MAX_UPLOAD_BYTES) {
            return PreparedImage(
                extension = if (extension == "jpeg") "jpg" else extension,
                body = file.asRequestBody(originalMediaType.toMediaType()),
            )
        }

        var image = ImageIO.read(file)
            ?: error("Unable to decode comic page for Telegraph: ${file.absolutePath}")
        image = flattenAndResize(image, MAX_IMAGE_DIMENSION)
        var encoded = encodeJpeg(image, JPEG_QUALITY)
        while (encoded.size > MAX_UPLOAD_BYTES && max(image.width, image.height) > MIN_IMAGE_DIMENSION) {
            image = flattenAndResize(image, (max(image.width, image.height) * RESIZE_FACTOR).toInt())
            encoded = encodeJpeg(image, JPEG_QUALITY)
        }
        check(encoded.size <= MAX_UPLOAD_BYTES) {
            "Comic page remains too large for Telegraph after compression: ${file.name}"
        }
        return PreparedImage(
            extension = "jpg",
            body = encoded.toRequestBody("image/jpeg".toMediaType()),
        )
    }

    private fun flattenAndResize(source: BufferedImage, maxDimension: Int): BufferedImage {
        val currentMax = max(source.width, source.height)
        val scale = if (currentMax > maxDimension) maxDimension.toDouble() / currentMax else 1.0
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
            target.createGraphics().use { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                graphics.drawImage(source, 0, 0, width, height, null)
            }
        }
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        return ByteArrayOutputStream().use { output ->
            MemoryCacheImageOutputStream(output).use { imageOutput ->
                writer.output = imageOutput
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(image, null, null), parameters)
            }
            writer.dispose()
            output.toByteArray()
        }
    }

    private fun executeJson(request: Request): JsonElement {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: error("Telegraph returned an empty response.")
            check(response.isSuccessful) {
                "Telegraph HTTP ${response.code}: ${body.take(512)}"
            }
            return JsonParser.parseString(body)
        }
    }

    private data class PreparedImage(
        val extension: String,
        val body: RequestBody,
    )

    companion object {
        private const val DEFAULT_API_BASE_URL = "https://api.telegra.ph"
        private const val DEFAULT_UPLOAD_URL = "https://telegra.ph/upload"
        private const val MAX_IMAGES_PER_PAGE = 200
        private const val MAX_TITLE_LENGTH = 256
        private const val MAX_AUTHOR_NAME_LENGTH = 128
        private const val MAX_AUTHOR_URL_LENGTH = 512
        private const val MAX_UPLOAD_BYTES = 4_800_000
        private const val MAX_IMAGE_DIMENSION = 2_400
        private const val MIN_IMAGE_DIMENSION = 480
        private const val RESIZE_FACTOR = 0.8
        private const val JPEG_QUALITY = 0.82f
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
