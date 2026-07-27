package me.heartalborada.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import me.heartalborada.commons.configurations.AbstractConfiguration
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

class Config(private val configFile: File) : AbstractConfiguration<ConfigData>() {
    private var config: ConfigData = ConfigData()
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        this.load()
    }

    @Throws(JsonSyntaxException::class, IOException::class, ClassNotFoundException::class)
    override fun load() {
        if (!configFile.exists()) {
            logger.debug("Config file does not exist: ${configFile.absolutePath}")
            configFile.parentFile.mkdirs()
            configFile.createNewFile()
            save()
        }
        val s = FileUtils.readFileToString(configFile, Charset.defaultCharset())
        try {
            val root = JsonParser.parseString(s).asJsonObject
            val sourceVersion = root.get("version")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
                ?: 1
            if (sourceVersion > ConfigData.CURRENT_VERSION) {
                logger.warn(
                    "Config version {} is newer than supported version {}; leaving the file unchanged.",
                    sourceVersion,
                    ConfigData.CURRENT_VERSION,
                )
            } else if (ConfigMigration.upgrade(root, sourceVersion)) {
                logger.info(
                    "Upgraded config from version {} to version {}.",
                    sourceVersion,
                    ConfigData.CURRENT_VERSION,
                )
                FileUtils.writeStringToFile(configFile, gson.toJson(root), Charset.defaultCharset())
            }
            config = gson.fromJson(root, ConfigData::class.java)
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

    override fun getConfig(): ConfigData {
        return config
    }
}

internal object ConfigMigration {
    fun upgrade(root: JsonObject, sourceVersion: Int): Boolean {
        if (sourceVersion >= ConfigData.CURRENT_VERSION) {
            return false
        }

        var version = sourceVersion
        while (version < ConfigData.CURRENT_VERSION) {
            when (version) {
                1 -> migrateV1ToV2(root)
                else -> error("No config migration is available for version $version.")
            }
            version++
        }
        root.addProperty("version", ConfigData.CURRENT_VERSION)
        return true
    }

    private fun migrateV1ToV2(root: JsonObject) {
        val bot = root.get("Bot")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject().also { root.add("Bot", it) }

        val napcat = normalizeSection(bot, "napcat", "Napcat")
        moveIfMissing(bot, napcat, "WebsocketURL")
        moveIfMissing(bot, napcat, "Token")
        moveIfMissing(bot, napcat, "FileUpload")
        normalizeSection(bot, "telegram", "Telegram")
    }

    private fun normalizeSection(parent: JsonObject, canonicalName: String, legacyName: String): JsonObject {
        val canonical = parent.get(canonicalName)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject().also { parent.add(canonicalName, it) }
        val legacy = parent.remove(legacyName)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        legacy?.entrySet()?.forEach { (key, value) ->
            if (!canonical.has(key)) {
                canonical.add(key, value)
            }
        }
        return canonical
    }

    private fun moveIfMissing(source: JsonObject, target: JsonObject, key: String) {
        val value = source.remove(key) ?: return
        if (!target.has(key)) {
            target.add(key, value)
        }
    }
}
