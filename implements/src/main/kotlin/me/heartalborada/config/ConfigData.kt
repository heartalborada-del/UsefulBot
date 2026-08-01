package me.heartalborada.config

import com.google.gson.annotations.SerializedName

data class ConfigData @JvmOverloads constructor(
    @SerializedName("version") val version: Int = CURRENT_VERSION,
    @SerializedName("Bot") val bot: Bot = Bot(),
    @SerializedName("Proxy") val proxy: Proxy = Proxy(),
    @SerializedName("Ehentai") val eHentai: EHentai = EHentai(),
    @SerializedName("JMComic") val jmComic: JMComic = JMComic(),
    @SerializedName("ComicParallelCount") val comicParallelCount: Int = 2,
    @SerializedName("Access") val access: Access = Access(),
    @SerializedName("Tasks") val tasks: Tasks = Tasks(),
    @SerializedName("Cache") val cache: Cache = Cache(),
    @SerializedName("DeliveryRetry") val deliveryRetry: DeliveryRetry = DeliveryRetry(),
    @SerializedName("Batch") val batch: Batch = Batch(),
    @SerializedName("Plugins") val plugins: Plugins = Plugins(),
) {
    data class Access @JvmOverloads constructor(
        @SerializedName("CommandsPerMinute") val commandsPerMinute: Int = 20,
        @SerializedName("DailyDownloadLimit") val dailyDownloadLimit: Int = 20,
    )

    data class Tasks @JvmOverloads constructor(
        @SerializedName("UserCapacity") val userCapacity: Int = 5,
    )

    data class Cache @JvmOverloads constructor(
        @SerializedName("MaxSizeMiB") val maxSizeMiB: Long = 10_240,
        @SerializedName("TtlDays") val ttlDays: Long = 30,
        @SerializedName("CleanupIntervalMinutes") val cleanupIntervalMinutes: Long = 60,
        @SerializedName("MinimumFreeSpaceMiB") val minimumFreeSpaceMiB: Long = 1_024,
    )

    data class DeliveryRetry @JvmOverloads constructor(
        @SerializedName("Enabled") val enabled: Boolean = true,
        @SerializedName("IntervalSeconds") val intervalSeconds: Long = 60,
        @SerializedName("MaxAttempts") val maxAttempts: Int = 10,
    )

    data class Batch @JvmOverloads constructor(
        @SerializedName("Enabled") val enabled: Boolean = false,
        @SerializedName("MaxItems") val maxItems: Int = 10,
    )

    data class Plugins @JvmOverloads constructor(
        @SerializedName("Enabled") val enabled: Boolean = true,
        @SerializedName("Directory") val directory: String = "plugins",
        @SerializedName("Disabled") val disabled: List<String> = emptyList(),
    )

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
            @SerializedName("UploadTimeoutMinutes") val uploadTimeoutMinutes: Long = 60,
            @SerializedName("EnableInlineMode") val enableInlineMode: Boolean = true,
            @SerializedName("LargeFile") val largeFile: LargeFile = LargeFile(),
        ) {
            data class LargeFile @JvmOverloads constructor(
                @SerializedName("Policy") val policy: LargeFilePolicy = LargeFilePolicy.SPLIT_PDF,
                @SerializedName("MaxPartSizeMiB") val maxPartSizeMiB: Int = 48,
                @SerializedName("TempDirectory") val tempDirectory: String = "data/telegram/temp",
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
        @SerializedName("MaxArchiveSizeMiB") val maxArchiveSizeMiB: Long = 0,
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
        const val CURRENT_VERSION = 14
    }
}

enum class LargeFilePolicy {
    SPLIT_PDF,
    FAIL,
}
