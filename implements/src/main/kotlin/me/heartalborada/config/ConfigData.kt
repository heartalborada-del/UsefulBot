package me.heartalborada.config

import com.google.gson.annotations.SerializedName

data class ConfigData @JvmOverloads constructor(
    @SerializedName("version") val version: Int = CURRENT_VERSION,
    @SerializedName("Bot") val bot: Bot = Bot(),
    @SerializedName("Proxy") val proxy: Proxy = Proxy(),
    @SerializedName("Ehentai") val eHentai: EHentai = EHentai(),
    @SerializedName("JMComic") val jmComic: JMComic = JMComic(),
    @SerializedName("ComicParallelCount") val comicParallelCount: Int = 2,
) {
    data class Bot @JvmOverloads constructor(
        @SerializedName("CommandOperator") val commandOperator: Char = '/',
        @SerializedName("IsCommandStartWithAt") val isCommandStartWithAt: Boolean = false,
        @SerializedName("Language") val language: String = "en",
        @SerializedName(value = "napcat", alternate = ["Napcat"]) val napcat: Napcat = Napcat(),
        @SerializedName(value = "telegram", alternate = ["Telegram"]) val telegram: Telegram = Telegram(),
    ) {
        data class Napcat @JvmOverloads constructor(
            @SerializedName("Enabled") val enabled: Boolean = true,
            @SerializedName("BlurImages") val blurImages: Boolean = true,
            @SerializedName("WebsocketURL") val websocketUrl: String = "ws://127.0.0.1:3000",
            @SerializedName("Token") val token: String = "napcat!",
            @SerializedName("FileUpload") val fileUpload: FileUpload = FileUpload(),
        ) {
            data class FileUpload @JvmOverloads constructor(
                @SerializedName("ChunkSize") val chunkSize: Int = 512 * 1024,
                @SerializedName("UseStreamAPI") val useStreamAPI: Boolean = false,
                @SerializedName("Stream_ExpireSeconds") val expireSeconds: Long = 600,
            )
        }

        data class Telegram @JvmOverloads constructor(
            @SerializedName("Enabled") val enabled: Boolean = false,
            @SerializedName("BlurImages") val blurImages: Boolean = false,
            @SerializedName("Token") val token: String = "",
            @SerializedName("ApiBaseURL") val apiBaseUrl: String = "https://api.telegram.org",
            @SerializedName("EnableInlineMode") val enableInlineMode: Boolean = true,
            @SerializedName("LargeFile") val largeFile: LargeFile = LargeFile(),
            @SerializedName("TelegraphPreview") val telegraphPreview: TelegraphPreview = TelegraphPreview(),
        ) {
            data class LargeFile @JvmOverloads constructor(
                @SerializedName("Policy") val policy: LargeFilePolicy = LargeFilePolicy.SPLIT_PDF,
                @SerializedName("MaxPartSizeMiB") val maxPartSizeMiB: Int = 48,
                @SerializedName("TempDirectory") val tempDirectory: String = "data/telegram/temp",
            )

            data class TelegraphPreview @JvmOverloads constructor(
                @SerializedName("Enabled") val enabled: Boolean = true,
                @SerializedName("AccessToken") val accessToken: String = "",
                @SerializedName("AuthorName") val authorName: String = "UsefulBot",
                @SerializedName("AuthorURL") val authorUrl: String = "",
            )
        }
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

    companion object {
        const val CURRENT_VERSION = 6
    }
}

enum class LargeFilePolicy {
    SPLIT_PDF,
    TELEGRAPH,
    FAIL,
}
