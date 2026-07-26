package me.heartalborada.config

import com.google.gson.annotations.SerializedName

data class ConfigData @JvmOverloads constructor(
    @SerializedName("Bot") val bot: Bot = Bot(),
    @SerializedName("Proxy") val proxy: Proxy = Proxy(),
    @SerializedName("Ehentai") val eHentai: EHentai = EHentai(),
    @SerializedName("JMComic") val jmComic: JMComic = JMComic(),
    @SerializedName("ComicParallelCount") val comicParallelCount: Int = 2,
) {
    data class Bot @JvmOverloads constructor(
        @SerializedName("WebsocketURL") val websocketUrl: String = "ws://127.0.0.1:3000",
        @SerializedName("Token") val token: String = "napcat!",
        @SerializedName("CommandOperator") val commandOperator: Char = '/',
        @SerializedName("IsCommandStartWithAt") val isCommandStartWithAt: Boolean = false,
        @SerializedName("Language") val language: String = "en",
        @SerializedName("FileUpload") val fileUpload: FileUpload = FileUpload(),
    ) {
        data class FileUpload @JvmOverloads constructor(
            @SerializedName("ChunkSize") val chunkSize: Int = 512 * 1024,
            @SerializedName("UseStreamAPI") val useStreamAPI: Boolean = false,
            @SerializedName("Stream_ExpireSeconds") val expireSeconds: Long = 600,
            )
    }

    data class Proxy @JvmOverloads constructor(
        @SerializedName("Type") val type: java.net.Proxy.Type = java.net.Proxy.Type.DIRECT,
        @SerializedName("Address") val address: String = "127.0.0.1",
        @SerializedName("Port") val port: Int = 1080,
    )

    data class EHentai @JvmOverloads constructor(
        @SerializedName("ipb_member_id") val ipbMemberId: String = "",
        @SerializedName("ipb_pass_hash") val ipbPassHash: String = "",
        @SerializedName("igneous") val igneous: String = "",
        @SerializedName("star") val star: String = "",
        @SerializedName("sk") val sk: String = "",
        @SerializedName("isExHentai") val isExHentai: Boolean = false,
    )

    data class JMComic @JvmOverloads constructor(
        @SerializedName("ApiDomains") val apiDomains: List<String> = listOf(
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club",
        ),
        @SerializedName("Domains") val domains: List<String> = emptyList(),
        @SerializedName("RedirectURL") val redirectUrl: String = "https://jm365.work/3YeBdF",
        @SerializedName("ImageDomains") val imageDomains: List<String> = listOf(
            "cdn-msp.jmapiproxy1.cc",
            "cdn-msp.jmapiproxy2.cc",
            "cdn-msp2.jmapiproxy2.cc",
            "cdn-msp3.jmapiproxy2.cc",
            "cdn-msp.jmapinodeudzn.net",
            "cdn-msp3.jmapinodeudzn.net",
        ),
        @SerializedName("ImageParallelCount") val imageParallelCount: Int = 8,
    )
}
