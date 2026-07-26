package me.heartalborada.comics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import me.heartalborada.commons.comic.AbstractComicProvider
import me.heartalborada.commons.comic.model.ArchiveInformation
import me.heartalborada.commons.comic.model.ComicInformation
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

class JMComic(
    parentClient: OkHttpClient = OkHttpClient(),
    apiDomains: List<String> = DEFAULT_API_DOMAINS,
    domains: List<String> = emptyList(),
    private val redirectUrl: String = DEFAULT_REDIRECT_URL,
    private val imageDomains: List<String> = DEFAULT_IMAGE_DOMAINS,
    private val imageParallelCount: Int = 8,
) : AbstractComicProvider<String>() {
    private val gson = Gson()
    private val configuredApiDomains = apiDomains.mapNotNull(::normalizeDomain).distinct()
    private val configuredDomains = domains.mapNotNull(::normalizeDomain).distinct()
    private val configuredImageDomains = imageDomains.mapNotNull(::normalizeDomain).distinct()
    private val albumCache = ConcurrentHashMap<String, JmAlbum>()
    private val photoCache = ConcurrentHashMap<String, JmPhoto>()
    private val apiCookieLock = Any()
    @Volatile
    private var apiCookiesInitialized = false
    @Volatile
    private var apiCookieHeader = ""

    private val client = parentClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val builder = request.newBuilder()
            if (host in configuredApiDomains) {
                builder
                    .header("User-Agent", API_USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
            } else if (request.url.encodedPath.contains("/media/")) {
                builder
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://${configuredApiDomains.first()}/")
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .header("X-Requested-With", "com.JMComic3.app")
            } else {
                builder
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://$host/")
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                    )
            }
            chain.proceed(builder.build())
        }
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val resolvedDomains: List<String> by lazy {
        val dynamicDomain = runCatching {
            client.newCall(Request.Builder().url(redirectUrl).build()).execute().use {
                normalizeDomain(it.request.url.toString())
            }
        }.getOrNull()
        (configuredDomains + listOfNotNull(dynamicDomain) + DEFAULT_WEB_DOMAINS).distinct()
    }

    init {
        require(imageParallelCount > 0) { "JMComic image parallel count must be greater than zero." }
        require(configuredApiDomains.isNotEmpty()) { "At least one valid JMComic API domain is required." }
        require(configuredImageDomains.isNotEmpty()) { "At least one valid JMComic image domain is required." }
    }

    override fun parseUrl(url: String): String {
        val input = url.trim()
        if (input.matches(Regex("(?i)(?:JM)?\\d+"))) {
            return if (input.startsWith("JM", ignoreCase = true)) input.drop(2) else input
        }
        val match = TARGET_ID_REGEX.find(input)
            ?: throw IllegalArgumentException("Invalid JMComic target: $url")
        return match.groupValues.drop(1).first(String::isNotEmpty)
    }

    override fun getTargetInformation(target: String): ComicInformation<String> {
        val album = getAlbum(target)
        val pageCount = album.pageCount.takeIf { it > 0 }
            ?: album.episodes.sumOf { getPhoto(it.photoId).fileNames.size }
        return ComicInformation(
            id = album.id,
            title = album.title,
            subtitle = null,
            tags = mapOf(
                "author" to album.authors,
                "works" to album.works,
                "character" to album.actors,
                "tag" to album.tags,
            ).filterValues(List<String>::isNotEmpty),
            category = ComicInformation.Category.Manga,
            cover = album.cover,
            pages = pageCount,
            rating = 0.0,
            uploader = album.authors.joinToString(", ").ifBlank { "JMComic" },
            uploadTime = parseDate(album.publishedAt),
            extra = mapOf(
                "chapterCount" to album.episodes.size.toString(),
                "source" to "JMComic",
                "description" to album.description,
            )
        )
    }

    override fun getAllPages(target: String): Map<Int, String> {
        return getAlbumPages(parseUrl(target))
            .mapIndexed { index, page -> index + 1 to page.url }
            .toMap(linkedMapOf())
    }

    override fun getPageImageUrl(target: String, pages: Map<Int, String>): Map<Int, String> {
        return pages.toSortedMap()
    }

    override fun getArchiveDownloadUrl(target: String, type: ArchiveInformation): String {
        throw UnsupportedOperationException("JMComic does not provide archive downloads.")
    }

    override fun getArchiveInformation(target: String): Array<ArchiveInformation> = emptyArray()

    fun downloadCover(target: String, output: File): File {
        val coverUrl = getAlbum(target).cover
        output.parentFile?.mkdirs()
        Files.write(output.toPath(), requestImage(coverUrl))
        return output
    }

    fun downloadAlbum(target: String, destination: File): List<File> {
        val albumId = parseUrl(target)
        val pages = getAlbumPages(albumId)
        check(pages.isNotEmpty()) { "No downloadable images were found for JM$albumId." }
        destination.mkdirs()

        val executor = Executors.newFixedThreadPool(imageParallelCount)
        return try {
            val futures = pages.mapIndexed { index, page ->
                executor.submit<File> {
                    val suffix = page.fileName.substringAfterLast(".", "jpg")
                        .lowercase()
                        .takeIf { it in SUPPORTED_IMAGE_SUFFIXES }
                        ?: "jpg"
                    val output = File(destination, "%05d.%s".format(index + 1, suffix))
                    if (!output.isFile || output.length() == 0L) {
                        downloadAndDecode(page, output)
                    }
                    output
                }
            }
            futures.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    internal fun getAlbum(target: String): JmAlbum {
        val albumId = parseUrl(target)
        return albumCache.computeIfAbsent(albumId) {
            runCatching { fetchApiAlbum(albumId) }
                .getOrElse { apiError ->
                    runCatching { parseAlbumHtml(albumId, requestHtml("/album/$albumId")) }
                        .getOrElse { htmlError ->
                            htmlError.addSuppressed(apiError)
                            throw htmlError
                        }
                }
        }
    }

    private fun getPhoto(photoId: String): JmPhoto {
        return photoCache.computeIfAbsent(photoId) {
            runCatching { fetchApiPhoto(photoId) }
                .getOrElse { apiError ->
                    runCatching { parsePhotoHtml(photoId, requestHtml("/photo/$photoId")) }
                        .getOrElse { htmlError ->
                            htmlError.addSuppressed(apiError)
                            throw htmlError
                        }
                }
        }
    }

    private fun getAlbumPages(albumId: String): List<JmPage> {
        val album = getAlbum(albumId)
        return album.episodes.flatMap { episode ->
            val photo = getPhoto(episode.photoId)
            photo.fileNames.map { fileName ->
                JmPage(
                    url = "https://${photo.imageDomain}/media/photos/${photo.id}/$fileName",
                    photoId = photo.id,
                    scrambleId = photo.scrambleId,
                    fileName = fileName,
                )
            }
        }
    }

    private fun requestHtml(path: String): String {
        var lastError: Exception? = null
        for (domain in resolvedDomains) {
            try {
                client.newCall(Request.Builder().url("https://$domain$path").build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("JMComic returned HTTP ${response.code} from $domain")
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) throw IOException("JMComic returned an empty response from $domain")
                    if ("Restricted Access!" in body) {
                        throw IOException("JMComic rejected access from the current network.")
                    }
                    return decodeEmbeddedHtml(body)
                }
            } catch (exception: Exception) {
                lastError = exception
            }
        }
        throw IOException("All JMComic domains failed.", lastError)
    }

    private fun fetchApiAlbum(albumId: String): JmAlbum {
        return parseApiAlbum(requestApi("/album?id=$albumId"))
    }

    private fun fetchApiPhoto(photoId: String): JmPhoto {
        val photo = requestApi("/chapter?id=$photoId")
        return parseApiPhoto(photo, requestScrambleId(photoId))
    }

    private fun requestScrambleId(photoId: String): Int {
        val timestamp = System.currentTimeMillis() / 1000
        val path = "/chapter_view_template?id=$photoId&mode=vertical&page=0" +
            "&app_img_shunt=1&express=off&v=$timestamp"
        return runCatching {
            SCRAMBLE_ID_REGEX.find(requestApiText(path, APP_TOKEN_SECRET_2))
                ?.groupValues?.get(1)?.toInt()
                ?: DEFAULT_SCRAMBLE_ID
        }.getOrDefault(DEFAULT_SCRAMBLE_ID)
    }

    private fun requestApi(path: String): JsonObject {
        ensureApiCookies()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val response = requestApiText(path, APP_TOKEN_SECRET, timestamp)
        val envelope = JsonParser.parseString(response).asJsonObject
        val code = envelope.get("code")?.asInt
        if (code != 200) {
            throw IOException("JMComic API returned code ${code ?: "unknown"}.")
        }
        val encryptedData = envelope.get("data")?.asString
            ?: throw IOException("JMComic API response did not contain data.")
        return JsonParser.parseString(decryptApiData(encryptedData, timestamp)).asJsonObject
    }

    private fun requestApiText(
        path: String,
        tokenSecret: String,
        timestamp: String = (System.currentTimeMillis() / 1000).toString(),
        includeCookies: Boolean = true,
    ): String {
        var lastError: Exception? = null
        val token = md5Hex(timestamp + tokenSecret)
        for (domain in configuredApiDomains) {
            try {
                val requestBuilder = Request.Builder()
                    .url("https://$domain$path")
                    .header("token", token)
                    .header("tokenparam", "$timestamp,$APP_VERSION")
                if (includeCookies && apiCookieHeader.isNotBlank()) {
                    requestBuilder.header("Cookie", apiCookieHeader)
                }
                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("JMComic API returned HTTP ${response.code} from $domain")
                    }
                    val cookies = Cookie.parseAll(response.request.url, response.headers)
                    if (cookies.isNotEmpty()) {
                        apiCookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) throw IOException("JMComic API returned an empty response from $domain")
                    return body
                }
            } catch (exception: Exception) {
                lastError = exception
            }
        }
        throw IOException("All JMComic API domains failed.", lastError)
    }

    private fun ensureApiCookies() {
        if (apiCookiesInitialized) return
        synchronized(apiCookieLock) {
            if (apiCookiesInitialized) return
            requestApiText("/setting", APP_TOKEN_SECRET, includeCookies = false)
            apiCookiesInitialized = true
        }
    }

    internal fun decryptApiData(data: String, timestamp: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val key = SecretKeySpec(md5Hex(timestamp + APP_DATA_SECRET).toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8)
    }

    internal fun parseApiAlbum(json: JsonObject): JmAlbum {
        val albumId = json.requiredString("id")
        val title = json.requiredString("name")
        val episodes = json.arrayObjects("series")
            .mapIndexed { position, item ->
                JmEpisode(
                    photoId = item.requiredString("id"),
                    index = item.string("sort")?.toIntOrNull() ?: position + 1,
                    title = item.string("name").orEmpty(),
                )
            }
            .sortedWith(compareBy(JmEpisode::index, JmEpisode::photoId))
            .ifEmpty { listOf(JmEpisode(albumId, 1, title)) }
        return JmAlbum(
            id = albumId,
            title = title,
            description = json.string("description").orEmpty(),
            pageCount = json.string("page_count")?.toIntOrNull()
                ?: json.string("total")?.toIntOrNull()
                ?: 0,
            cover = "https://${configuredImageDomains.first()}/media/albums/$albumId.jpg",
            authors = json.stringList("author"),
            tags = json.stringList("tags"),
            works = json.stringList("works"),
            actors = json.stringList("actors"),
            publishedAt = json.string("pub_date").orEmpty(),
            episodes = episodes,
        )
    }

    internal fun parseApiPhoto(json: JsonObject, scrambleId: Int): JmPhoto {
        val photoId = json.requiredString("id")
        return JmPhoto(
            id = photoId,
            title = json.string("name").orEmpty(),
            scrambleId = scrambleId,
            imageDomain = configuredImageDomains.first(),
            fileNames = json.stringList("images"),
        )
    }

    private fun downloadAndDecode(page: JmPage, output: File) {
        val bytes = requestImage(page.url)
        if (page.fileName.endsWith(".gif", ignoreCase = true)) {
            Files.write(output.toPath(), bytes)
            return
        }
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IOException("JMComic returned an unsupported image: ${page.url}")
        val segments = calculateScrambleSegments(page.scrambleId, page.photoId, page.fileName.substringBeforeLast("."))
        val decoded = decodeImage(image, segments)
        if (!ImageIO.write(decoded, output.extension, output)) {
            ImageIO.write(decoded, "jpg", output)
        }
    }

    private fun requestImage(url: String): ByteArray {
        var lastError: Exception? = null
        val original = url.toHttpUrl()
        val candidates = (listOf(original.host) + configuredImageDomains)
            .distinct()
            .map { domain -> original.newBuilder().host(domain).build() }
        for (candidate in candidates) {
            try {
                client.newCall(Request.Builder().url(candidate).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Image request returned HTTP ${response.code}")
                    val bytes = response.body?.bytes() ?: throw IOException("Image response was empty")
                    if (bytes.isEmpty()) throw IOException("Image response was empty")
                    return bytes
                }
            } catch (exception: Exception) {
                lastError = exception
            }
        }
        throw IOException("Failed to download JMComic image from all configured domains: $url", lastError)
    }

    internal fun parseAlbumHtml(albumId: String, html: String): JmAlbum {
        val decodedHtml = decodeEmbeddedHtml(html)
        val document = Jsoup.parse(decodedHtml)
        val title = document.selectFirst("#book-name")?.text()
            ?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
            ?: throw IllegalStateException("JMComic album title was not found.")
        val pageCount = document.selectFirst(".pagecount")?.text()
            ?.let { NUMBER_REGEX.find(it)?.value?.toIntOrNull() }
            ?: 0
        val authors = extractTags(document, "author")
        val tags = extractTags(document, "tags")
        val works = extractTags(document, "works")
        val actors = extractTags(document, "actor")
        val episodes = document.select("[data-album]")
            .mapNotNull { element ->
                val photoId = element.attr("data-album").takeIf { it.matches(NUMERIC_REGEX) } ?: return@mapNotNull null
                val text = element.text().trim()
                val index = EPISODE_INDEX_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
                JmEpisode(photoId, index, text)
            }
            .distinctBy(JmEpisode::photoId)
            .sortedWith(compareBy(JmEpisode::index, JmEpisode::photoId))
            .ifEmpty { listOf(JmEpisode(albumId, 1, title)) }
        val rawCover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { "/media/albums/" in it }
            ?: document.select("[data-original*=media/albums/]").firstOrNull()?.attr("data-original")
        val cover = when {
            rawCover.isNullOrBlank() -> "https://${configuredImageDomains.first()}/media/albums/$albumId.jpg"
            rawCover.startsWith("https://") -> rawCover
            rawCover.startsWith("//") -> "https:$rawCover"
            rawCover.startsWith("/") -> "https://${configuredImageDomains.first()}$rawCover"
            else -> "https://${configuredImageDomains.first()}/$rawCover"
        }

        return JmAlbum(
            id = albumId,
            title = title,
            description = DESCRIPTION_REGEX.find(decodedHtml)?.groupValues?.get(1)
                ?.let { Jsoup.parse(it).text() }
                .orEmpty(),
            pageCount = pageCount,
            cover = cover,
            authors = authors,
            tags = tags,
            works = works,
            actors = actors,
            publishedAt = PUBLISHED_DATE_REGEX.find(decodedHtml)?.groupValues?.get(1).orEmpty(),
            episodes = episodes,
        )
    }

    internal fun parsePhotoHtml(photoId: String, html: String): JmPhoto {
        val decodedHtml = decodeEmbeddedHtml(html)
        val document = Jsoup.parse(decodedHtml)
        val pageArrayJson = PAGE_ARRAY_REGEX.find(decodedHtml)?.groupValues?.get(1)
            ?: throw IllegalStateException("JMComic page list was not found for photo $photoId.")
        val fileNames: List<String> = gson.fromJson(
            pageArrayJson,
            object : TypeToken<List<String>>() {}.type
        )
        val scrambleId = SCRAMBLE_ID_REGEX.find(decodedHtml)?.groupValues?.get(1)?.toIntOrNull()
            ?: DEFAULT_SCRAMBLE_ID
        val firstImage = document.select("[data-original*=media/photos/]").firstOrNull()?.attr("data-original")
        val imageDomain = firstImage?.let {
            runCatching { URI(it).host }.getOrNull()
        } ?: IMAGE_DOMAIN_REGEX.find(decodedHtml)?.groupValues?.get(1)
            ?: configuredImageDomains.first()

        return JmPhoto(
            id = photoId,
            title = document.title().substringBefore("|").trim(),
            scrambleId = scrambleId,
            imageDomain = imageDomain,
            fileNames = fileNames,
        )
    }

    internal fun decodeImage(source: BufferedImage, segments: Int): BufferedImage {
        if (segments <= 1) return source
        val decoded = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = decoded.createGraphics()
        try {
            val remainder = source.height % segments
            repeat(segments) { index ->
                var height = source.height / segments
                val sourceY = source.height - height * (index + 1) - remainder
                var destinationY = height * index
                if (index == 0) {
                    height += remainder
                } else {
                    destinationY += remainder
                }
                graphics.drawImage(
                    source,
                    0,
                    destinationY,
                    source.width,
                    destinationY + height,
                    0,
                    sourceY,
                    source.width,
                    sourceY + height,
                    null
                )
            }
        } finally {
            graphics.dispose()
        }
        return decoded
    }

    internal fun calculateScrambleSegments(scrambleId: Int, photoId: String, fileName: String): Int {
        val id = photoId.toInt()
        if (id < scrambleId) return 0
        if (id < SCRAMBLE_268850) return 10
        val modulus = if (id < SCRAMBLE_421926) 10 else 8
        val digest = MessageDigest.getInstance("MD5")
            .digest("$id$fileName".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest.last().code % modulus * 2 + 2
    }

    private fun extractTags(document: Document, type: String): List<String> {
        return document.select("[data-type=$type] a")
            .map { it.text().trim() }
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun decodeEmbeddedHtml(html: String): String {
        val encoded = EMBEDDED_HTML_REGEX.find(html)?.groupValues?.get(1) ?: return html
        return runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault(html)
    }

    private fun parseDate(date: String): Long {
        if (date.isBlank()) return -1L
        return runCatching {
            LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond()
        }.getOrDefault(-1L)
    }

    private fun normalizeDomain(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val uri = URI(if ("://" in trimmed) trimmed else "https://$trimmed")
            uri.host
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonNull) null else runCatching { value.asString }.getOrNull()
    }

    private fun JsonObject.requiredString(name: String): String {
        return string(name)?.takeIf(String::isNotBlank)
            ?: throw IOException("JMComic API field '$name' was missing.")
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (value.isJsonArray) {
            return value.asJsonArray.mapNotNull { item ->
                when {
                    item.isJsonPrimitive -> item.asString
                    item.isJsonObject -> item.asJsonObject.string("name")
                    else -> null
                }?.trim()?.takeIf(String::isNotEmpty)
            }.distinct()
        }
        return string(name)?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
    }

    private fun JsonObject.arrayObjects(name: String): List<JsonObject> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
    }

    private fun md5Hex(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal data class JmAlbum(
        val id: String,
        val title: String,
        val description: String,
        val pageCount: Int,
        val cover: String,
        val authors: List<String>,
        val tags: List<String>,
        val works: List<String>,
        val actors: List<String>,
        val publishedAt: String,
        val episodes: List<JmEpisode>,
    )

    internal data class JmEpisode(
        val photoId: String,
        val index: Int,
        val title: String,
    )

    internal data class JmPhoto(
        val id: String,
        val title: String,
        val scrambleId: Int,
        val imageDomain: String,
        val fileNames: List<String>,
    )

    private data class JmPage(
        val url: String,
        val photoId: String,
        val scrambleId: Int,
        val fileName: String,
    )

    companion object {
        const val DEFAULT_REDIRECT_URL = "https://jm365.work/3YeBdF"
        val DEFAULT_API_DOMAINS = listOf(
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club",
        )
        val DEFAULT_IMAGE_DOMAINS = listOf(
            "cdn-msp.jmapiproxy1.cc",
            "cdn-msp.jmapiproxy2.cc",
            "cdn-msp2.jmapiproxy2.cc",
            "cdn-msp3.jmapiproxy2.cc",
            "cdn-msp.jmapinodeudzn.net",
            "cdn-msp3.jmapinodeudzn.net",
        )
        private val DEFAULT_WEB_DOMAINS = listOf("18comic.vip")
        private const val DEFAULT_SCRAMBLE_ID = 220980
        private const val SCRAMBLE_268850 = 268850
        private const val SCRAMBLE_421926 = 421926
        private const val APP_VERSION = "2.0.28"
        private const val APP_TOKEN_SECRET = "185Hcomic3PAPP7R"
        private const val APP_TOKEN_SECRET_2 = "18comicAPPContent"
        private const val APP_DATA_SECRET = "185Hcomic3PAPP7R"
        private const val API_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/91.0.4472.114 Safari/537.36"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val TARGET_ID_REGEX = Regex("(?i)(?:photos?|albums?)/(\\d+)|[?&]id=(\\d+)")
        private val NUMERIC_REGEX = Regex("\\d+")
        private val NUMBER_REGEX = Regex("\\d+")
        private val EPISODE_INDEX_REGEX = Regex("第\\s*(\\d+)\\s*[话話]")
        private val DESCRIPTION_REGEX = Regex("[叙敘]述：([\\s\\S]*?)</h2>")
        private val PUBLISHED_DATE_REGEX = Regex(">上架日期\\s*:\\s*(.*?)</span>")
        private val PAGE_ARRAY_REGEX = Regex("var\\s+page_arr\\s*=\\s*(\\[[\\s\\S]*?])\\s*;")
        private val SCRAMBLE_ID_REGEX = Regex("var\\s+scramble_id\\s*=\\s*(\\d+)\\s*;")
        private val IMAGE_DOMAIN_REGEX = Regex("https://([^/\"']+)/media/(?:albums|photos)/")
        private val EMBEDDED_HTML_REGEX = Regex("const\\s+html\\s*=\\s*base64DecodeUtf8\\(\"(.*?)\"\\)")
        private val SUPPORTED_IMAGE_SUFFIXES = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}
