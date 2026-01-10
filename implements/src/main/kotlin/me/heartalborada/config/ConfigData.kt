package me.heartalborada.config

import com.google.gson.annotations.SerializedName

data class ConfigData @JvmOverloads constructor(
    @SerializedName("Bot") val bot: Bot = Bot(),
    @SerializedName("Proxy") val proxy: Proxy = Proxy(),
    @SerializedName("Ehentai") val eHentai: EHentai = EHentai(),
    @SerializedName("ComicParallelCount") val comicParallelCount: Int = 2,
) {
    data class Bot @JvmOverloads constructor(
        @SerializedName("WebsocketURL") val websocketUrl: String = "ws://127.0.0.1:3000",
        @SerializedName("Token") val token: String = "napcat!",
        @SerializedName("CommandOperator") val commandOperator: Char = '/',
        @SerializedName("IsCommandStartWithAt") val isCommandStartWithAt: Boolean = false,
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
}