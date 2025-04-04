package me.heartalborada.comics

import me.heartalborada.commons.ActionResponse
import me.heartalborada.commons.comic.AbstractComicProvider
import me.heartalborada.commons.comic.ComicInformation
import me.heartalborada.commons.okhttp.CookieStorageProvider
import me.heartalborada.commons.okhttp.DoH
import okhttp3.*
import org.jsoup.nodes.Document
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class EHentai(
    private val cookieStorage: CookieJar = CookieStorageProvider(),
    private val isEx:Boolean = false,
    parentClient: OkHttpClient = OkHttpClient(),
    private val cacheFolder: File = File(System.getProperty("java.io.tmpdir"))
) : AbstractComicProvider<Pair<String,String>>() {
    private val baseUrl:String
        get() {
            if (isEx) return "https://exhentai.org"
            return "https://e-hentai.org"
        }
    private val apiUrl:String
        get() {
            if (isEx) return "https://exhentai.org/api.php"
            return "https://e-hentai.org/api.php"
        }

    private inner class HeaderInterceptor: Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            chain.request().newBuilder()
                .addHeader("referer", baseUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0")
                .build()
            return chain.proceed(chain.request())
        }
    }

    private val okHttpClient: OkHttpClient by lazy {

        parentClient.newBuilder().cookieJar(cookieStorage)
            .cache(Cache(cacheFolder, 1024L * 1024L * 1024L))
            .addInterceptor(HeaderInterceptor()).dns(DoH()).build()
    }

    private val galleryUrlRegex = Regex("https?://(exhentai|e-hentai)\\.org/g/([0-9a-zA-Z]+)/([0-9a-zA-Z]+)")
    private val numberRegex = Regex("\\d+")
    private val coverUrlRegex = Regex("https?://([-a-zA-Z0-9.]+(/\\S*)?\\.(?:jpg|jpeg|gif|png|webp))")
    private val showKeyRegex = Regex("showkey=\"(.*?)\"")
    private val variableRegex = Regex("var\\s+(\\w+)\\s*=\\s*(.*?);")
    fun parseGalleryUrl(url: String): Pair<String, String> {
        val matchResult = galleryUrlRegex.find(url)
        return if (matchResult != null) {
            val galleryId = matchResult.groupValues[2]
            val token = matchResult.groupValues[3]
            Pair(galleryId, token)
        } else {
            throw IllegalArgumentException("Invalid url $url")
        }
    }

    private fun parseShowKey(document: Document):String {
        var script:String? = null
        for (element in document.body().select("script")) {
            if (element.text().contains("showkey=")) {
                script = element.text()
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

    override fun getTargetInformation(target: Pair<String, String>): ActionResponse<ComicInformation<Pair<String, String>>> {
        okHttpClient.newCall(
            okhttp3.Request.Builder()
                .url("$baseUrl/g/${target.first}/${target.second}/")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful || response.code != 200) return ActionResponse(false, -1, "Invalid status code: ${response.code}", null)
            val body = response.body?.string()
            if (body == null || body.trim().isEmpty()) return ActionResponse(false, -1, "Failed to load page", null)
            if (!body.startsWith("<")) {
                if (body.contains("IP")) {
                    return ActionResponse(false, -1, "The IP address has been banned", null)
                }
                return ActionResponse(false, -1, "Failed to load page", null)
            }
            val document = org.jsoup.Jsoup.parse(body)
            val tags = mutableMapOf<String,MutableList<String>>()
            document.select("div#taglist > table > tbody > tr > td > div").forEach {
                val tag = it.id().split(":", limit = 2).toMutableList()
                tag[0] = tag[0].substring(3)
                if (!tags.containsKey(tag[0]))
                    tags[tag[0]] = mutableListOf()
                tags[tag[0]]?.add(tag[1])
            }
            var page: Int? = -1
            for (ele in document.select("td.gdt2")) {
                if(ele.text().contains("page")) {
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
                    if(ele.child(0).text() == "Posted:") {
                        uploadTime = LocalDateTime.parse("${ele.child(1).text()}:00", timeFormatter)
                            .toEpochSecond(ZoneOffset.UTC)
                        break
                    }
                }
            } catch (_: DateTimeParseException) {}
            val variables: MutableMap<String,String> = mutableMapOf()
            var varScript = ""
            for (ele in document.select("script")) {
                if(ele.data().contains("var token")) {
                    varScript = ele.data()
                    break
                }
            }
            variableRegex.findAll(varScript).iterator().forEach {
                if (it.groupValues[1] != "popbase")
                    variables[it.groupValues[1]] = it.groupValues[2].replace("\"","")
            }
            variables["imagePerPage"] = document.getElementById("gdt")?.childrenSize().toString()
            return ActionResponse(
                true,0,"Success",
                ComicInformation(
                    id = target,
                    tags = tags,
                    category = category,
                    title = document.select("h1#gn").text(),
                    subtitle = subtitle,
                    pages = page ?: -1,
                    cover = cover ?:"",
                    uploader = document.getElementById("gdn")?.child(0)?.text() ?: "",
                    uploadTime = uploadTime,
                    rating = document.select("td#rating_label").text().substring(9).toDoubleOrNull() ?: 0.0,
                    extra = variables
                )
            )
        }
    }

    private fun getAllPages(target: Pair<String, String>): Map<Int,String> {
        TODO("Not yet implemented")
    }
}

fun main() {
    val a = EHentai(parentClient = OkHttpClient.Builder()
        .proxy(
          Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1",7890))
        )
        .build())
    println(a.getTargetInformation(a.parseGalleryUrl("https://e-hentai.org/g/3302182/c7abad0526/")))
}