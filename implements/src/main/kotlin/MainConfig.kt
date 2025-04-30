import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import me.heartalborada.commons.configurations.AbstractConfiguration
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

data class MainConfigData @JvmOverloads constructor(
    @SerializedName("Bot") val bot: Bot = Bot(),
    @SerializedName("Proxy") val proxy: Proxy = Proxy(),
    @SerializedName("Ehentai") val eHentai: EHentai = EHentai(),
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

class MainConfig(private val configFile: File) : AbstractConfiguration<MainConfigData>() {
    private var config: MainConfigData = MainConfigData()
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val logger = LoggerFactory.getLogger(MainConfig::class.java)
    init {
        this.load()
    }
    @Throws(JsonSyntaxException::class, IOException::class,ClassNotFoundException::class)
    override fun load() {
        if (!configFile.exists()) {
            logger.debug("Config file does not exist: ${configFile.absolutePath}")
            configFile.parentFile.mkdirs()
            configFile.createNewFile()
            save()
        }
        val s = FileUtils.readFileToString(configFile, Charset.defaultCharset())
        try {
            config = gson.fromJson(s,MainConfigData::class.java)
        } catch (_: Exception) {
            save()
        }
    }
    @Throws(IOException::class)
    override fun save() {
        logger.debug("Saving Config File...")
        val s = gson.toJson(config)
        FileUtils.writeStringToFile(configFile, s, Charset.defaultCharset())
    }

    override fun getConfig(): MainConfigData {
        return config
    }
}
