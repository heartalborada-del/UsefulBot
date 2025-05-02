package me.heartalborada.comics

import com.google.common.cache.CacheBuilder
import com.google.gson.JsonParser
import me.heartalborada.commons.comic.AbstractComicProvider
import me.heartalborada.commons.comic.ArchiveInformation
import me.heartalborada.commons.comic.ComicInformation
import me.heartalborada.commons.okhttp.CookieStorageProvider
import me.heartalborada.commons.okhttp.RetryInterceptor
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration.Companion.milliseconds


class EHentai(
    private val cookieStorage: CookieJar = CookieStorageProvider(),
    private val isEx: Boolean = false,
    parentClient: OkHttpClient = OkHttpClient(),
    private val cacheFolder: File = File(System.getProperty("java.io.tmpdir"))
) : AbstractComicProvider<Pair<String, String>>() {
    private val baseUrl: String
        get() {
            if (isEx) return "https://exhentai.org"
            return "https://e-hentai.org"
        }
    private val apiUrl: String
        get() {
            if (isEx) return "https://exhentai.org/api.php"
            return "https://e-hentai.org/api.php"
        }
    private val logger = LoggerFactory.getLogger(this::class.java)

    private inner class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            chain.request().newBuilder()
                .addHeader("Referer", baseUrl)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0"
                )
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .addHeader("Host", baseUrl)
                .build()
            return chain.proceed(chain.request())
        }
    }

    private val okHttpClient: OkHttpClient by lazy {
        parentClient.newBuilder().cookieJar(cookieStorage)
            .cache(Cache(cacheFolder, 1024L * 1024L * 1024L))
            .addNetworkInterceptor(HeaderInterceptor())
            .addInterceptor(
                RetryInterceptor(
                    delay429 = 5000.milliseconds,
                    maxRetries = 10,
                    waitHosts = arrayOf(apiUrl)
                )
            )
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val infoCache = CacheBuilder.newBuilder()
        .expireAfterWrite(24, java.util.concurrent.TimeUnit.HOURS)
        .maximumSize(1024)
        .build<Pair<String, String>, ComicInformation<Pair<String, String>>>()

    private val galleryUrlRegex = Regex("(https?://(exhentai|e-hentai)\\.org/|)g/([0-9a-zA-Z]+)/([0-9a-zA-Z]+)")
    private val pageKeyUrlRegex = Regex("https?://(exhentai|e-hentai)\\.org/s/([0-9a-zA-Z]+)/([0-9a-zA-Z]+)-(\\d+)(/|)")
    private val numberRegex = Regex("\\d+")
    private val coverUrlRegex = Regex("https?://([-a-zA-Z0-9.]+(/\\S*)?\\.(?:jpg|jpeg|gif|png|webp))")
    private val showKeyRegex = Regex("showkey=\"(.*?)\"")
    private val variableRegex = Regex("var\\s+(\\w+)\\s*=\\s*(.*?);")
    private val imageUrlRegex =
        Regex("https?://([0-9a-zA-Z.]+)hath.network(:\\d+|)([0-9a-zA-Z-=;_/]+)\\.(?:jpg|jpeg|gif|png|webp)")

    override fun parseUrl(url: String): Pair<String, String> {
        val matchResult = galleryUrlRegex.find(url)
        return if (matchResult != null) {
            val galleryId = matchResult.groupValues[3]
            val token = matchResult.groupValues[4]
            Pair(galleryId, token)
        } else {
            throw IllegalArgumentException("Invalid url $url")
        }
    }

    /**
     * Get the archive download URL from the target, <bold>Must set cookies before calling this method</bold>
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    override fun getArchiveDownloadUrl(target: Pair<String, String>, type: ArchiveInformation): String {
        if (!isLogin())
            throw IllegalArgumentException("Cookies not set or expired")
        val archiveURL = "${baseUrl}/archiver.php?gid=${target.first}&token=${target.second}"
        val req = Request.Builder()
            .url(archiveURL)
            .post(
                let {
                    val b = FormBody.Builder()
                    if (type.name == ArchiveType.ORIGINAL.name) {
                        b.add("dltype", "org")
                        b.add("dlcheck", "Download+Original+Archive")
                    } else {
                        b.add("dltype", "res")
                        b.add("dlcheck", "Download+Resample+Archive")
                    }
                    b.build()
                }
            ).build()
        okHttpClient.newCall(req).execute().use { resp ->
            val pre = resp.documentPrecheck()
            return pre.select("a").first()?.attr("href").toString() + "?start=1"
        }
    }

    /**
     * Get the archive information from the target, <bold>Must set cookies before calling this method</bold>
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    override fun getArchiveInformation(target: Pair<String, String>): Array<ArchiveInformation> {
        if (!isLogin())
            throw IllegalArgumentException("Cookies not set or expired")
        val info = mutableListOf<ArchiveInformation>()
        okHttpClient.newCall(Request.Builder().url(getArchiveUrl(target)).build())
            .execute().use { resp ->
                val document = resp.documentPrecheck()
                document.select("#db").first()?.child(if (isEx) 1 else 3)?.children()?.select(":has(form)")
                    ?.forEach { it ->
                        val type = if (it.select("input[name=dltype]").attr("value") == "res") {
                            ArchiveType.RESAMPLE
                        } else {
                            ArchiveType.ORIGINAL
                        }
                        val size = it.select("p>strong").text()
                        val cost = it.select("div>strong").text()
                        info.add(
                            ArchiveInformation(
                                name = type.name,
                                size = size,
                                cost = cost
                            )
                        )
                    }
            }
        return info.toTypedArray()
    }

    private fun getArchiveUrl(target: Pair<String, String>): String {
        val archiveURL = "${baseUrl}/archiver.php?gid=${target.first}&token=${target.second}"
        return archiveURL
    }

    @Throws(IllegalStateException::class)
    private fun parseShowKey(document: Document): String {
        var script: String? = null
        for (element in document.body().select("script")) {
            if (element.data().contains("showkey=")) {
                script = element.data()
                break
            }
        }
        if (script == null)
            throw IllegalStateException("The 'showkey' not found")
        val matchResult = showKeyRegex.find(script)
        return if (matchResult != null) {
            matchResult.groupValues[1]
        } else {
            throw IllegalStateException("The 'showkey' not found")
        }
    }

    @Throws(IllegalStateException::class, IOException::class)
    override fun getTargetInformation(target: Pair<String, String>): ComicInformation<Pair<String, String>> {
        val cached = infoCache.getIfPresent(target)
        if (cached != null) return cached
        okHttpClient.newCall(
            Request.Builder()
                .url("$baseUrl/g/${target.first}/${target.second}/")
                .build()
        ).execute().use { resp ->
            val document = resp.documentPrecheck()
            val tags = mutableMapOf<String, MutableList<String>>()
            document.select("div#taglist > table > tbody > tr > td > div").forEach {
                val tag = it.id().split(":", limit = 2).toMutableList()
                tag[0] = tag[0].substring(3)
                if (!tags.containsKey(tag[0]))
                    tags[tag[0]] = mutableListOf()
                tags[tag[0]]?.add(tag[1])
            }
            var page: Int? = -1
            for (ele in document.select("td.gdt2")) {
                if (ele.text().contains("page")) {
                    val result = numberRegex.find(ele.text())
                    page = result?.groupValues?.get(0)?.toIntOrNull()
                    break
                }
            }
            val category = ComicInformation.Category.fromValue(document.select("div.cs").text())
            var subtitle: String? = document.select("div#gn").text()
            if (subtitle != null && subtitle.isEmpty()) subtitle = null
            val coverStyle = document.select("div#gleft > div#gd1 > div").attr("style")
            val cover = coverUrlRegex.find(coverStyle)?.groupValues?.get(0)
            val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            var uploadTime: Long = -1
            try {
                for (ele in document.select("div#gdd > table > tbody > tr")) {
                    if (ele.child(0).text() == "Posted:") {
                        uploadTime = LocalDateTime.parse("${ele.child(1).text()}:00", timeFormatter)
                            .toEpochSecond(ZoneOffset.UTC)
                        break
                    }
                }
            } catch (_: DateTimeParseException) {
            }
            val variables: MutableMap<String, String> = mutableMapOf()
            var varScript = ""
            for (ele in document.select("script")) {
                if (ele.data().contains("var token")) {
                    varScript = ele.data()
                    break
                }
            }
            variableRegex.findAll(varScript).iterator().forEach {
                if (it.groupValues[1] != "popbase")
                    variables[it.groupValues[1]] = it.groupValues[2].replace("\"", "")
            }
            variables["imagePerPage"] = document.getElementById("gdt")?.childrenSize().toString()
            val info = ComicInformation(
                id = target,
                tags = tags,
                category = category,
                title = document.select("h1#gn").text(),
                subtitle = subtitle,
                pages = page ?: -1,
                cover = cover ?: "",
                uploader = document.getElementById("gdn")?.child(0)?.text() ?: "",
                uploadTime = uploadTime,
                rating = document.select("td#rating_label").text().substring(9).toDoubleOrNull() ?: 0.0,
                extra = variables
            )
            infoCache.put(target, info)
            logger.debug("Resolved information: {}", info.toString())
            return info
        }
    }

    @Throws(IllegalStateException::class, IOException::class)
    override fun getAllPages(target: Pair<String, String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var isEnd = false
        var page = 0
        while (!isEnd) {
            okHttpClient.newCall(Request.Builder().url("$baseUrl/g/${target.first}/${target.second}/?p=$page").build())
                .execute().use { resp ->
                    val document = resp.documentPrecheck()
                    document.select("#gdt > a").forEach {
                        //url: https://e-hentai.org/s/3dc9c29de8/3302182-40
                        val link = it.attr("href")
                        pageKeyUrlRegex.find(link)?.let { its ->
                            val p = its.groupValues[4].toIntOrNull() ?: return@let
                            if (result.containsKey(p)) {
                                isEnd = true
                                return@forEach
                            }
                            result[p] = its.groupValues[2]
                        }
                    }
                }
            page++
        }
        return result
    }

    @Throws(IllegalStateException::class, IOException::class)
    override fun getPageImageUrl(target: Pair<String, String>, pages: Map<Int, String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (page in pages) {
            result[page.key] = getSinglePageImageUrl(target, page.toPair())
        }
        return result
    }

    @Throws(IllegalStateException::class, IOException::class)
    private fun getShowKey(gallery: Pair<String, String>, page: Pair<Int, String>): String {
        return okHttpClient.newCall(
            Request.Builder().url("${baseUrl}/s/${page.second}/${gallery.first}-${page.first}").build()
        ).execute().use { resp ->
            val body = resp.precheck()
            val key = parseShowKey(Jsoup.parse(body))
            return@use key
        }
    }

    @Throws(IllegalStateException::class, IOException::class)
    private fun getSinglePageImageUrl(target: Pair<String, String>, page: Pair<Int, String>): String {
        okHttpClient.newCall(
            Request.Builder().url(apiUrl).post(
                """
                {
                    "gid": "${target.first}",
                    "imgkey": "${page.second}",
                    "method": "showpage",
                    "page": ${page.first},
                    "showkey": "${getShowKey(target, page)}"
                }
            """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())
            ).build()
        ).execute().use { resp ->
            val body = resp.precheck()
            JsonParser.parseString(body).asJsonObject.let { json ->
                val value = imageUrlRegex.find(json.getAsJsonPrimitive("i3").asString)?.groupValues
                return value!![0]
            }
        }
    }

    private fun isLogin(): Boolean {
        okHttpClient.newCall(Request.Builder().url("${baseUrl}/mytags").build()).execute().use { resp ->
            logger.trace("isLogin: {}", resp.request.url.toUri().path == "/mytags")
            return resp.request.url.toUri().path == "/mytags"
        }
    }

    private fun Response.precheck(): String {
        if (!this.isSuccessful || this.code != 200) throw IllegalStateException("Invalid status code: ${this.code}")
        val body = this.body?.string() ?: throw IllegalStateException("Failed to load page")
        if (body.trim().isEmpty()) throw IllegalStateException("Failed to load page")
        return body
    }

    private fun Response.documentPrecheck(): Document {
        val body = this.precheck()
        if (!body.startsWith("<")) {
            if (body.contains("IP")) {
                throw IllegalStateException("The IP address has been banned")
            }
            throw IllegalStateException("Failed to load page")
        }
        return Jsoup.parse(body)
    }

    enum class ArchiveType {
        ORIGINAL,
        RESAMPLE
    }
}