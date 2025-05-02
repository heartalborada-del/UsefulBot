package me.heartalborada.config

import com.google.gson.annotations.SerializedName

data class ConfigData @JvmOverloads constructor(
    @SerializedName("Bot") val bot: Bot = Bot(),
    @SerializedName("Proxy") val proxy: Proxy = Proxy(),
    @SerializedName("Ehentai") val eHentai: EHentai = EHentai(),
    @SerializedName("ComicParallelCount") val comicParallelCount: Int = 2,
) {
    data class Bot @JvmOverloads constructor(
        @SerializedName("websocketURL") val websocketUrl: String = "ws://127.0.0.1:3000",
        @SerializedName("token") val token: String = "napcat!",
        @SerializedName("commandOperator") val commandOperator: Char = '/',
        @SerializedName("isCommandStartWithAt") val isCommandStartWithAt: Boolean = false,
        @SerializedName("fileRelativePath") val fileRelativePath: String? = null
    )

    data class Proxy @JvmOverloads constructor(
        @SerializedName("type") val type: java.net.Proxy.Type = java.net.Proxy.Type.DIRECT,
        @SerializedName("address") val address: String = "127.0.0.1",
        @SerializedName("port") val port: Int = 1080,
    )

    data class EHentai @JvmOverloads constructor(
        @SerializedName("ipb_member_id") val ipbMemberId: String = "",
        @SerializedName("ipb_pass_hash") val ipbPassHash: String = "",
        @SerializedName("igneous") val igneous: String = "",
        @SerializedName("isExHentai") val isExHentai: Boolean = false,
    )
}