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
    private val defaultConfig = GsonBuilder()
        .create()
        .toJsonTree(ConfigData())
        .asJsonObject

    fun upgrade(root: JsonObject, sourceVersion: Int): Boolean {
        if (sourceVersion >= ConfigData.CURRENT_VERSION) {
            return false
        }

        var version = sourceVersion
        while (version < ConfigData.CURRENT_VERSION) {
            when (version) {
                1 -> migrateV1ToV2(root)
                2 -> migrateV2ToV3(root)
                3 -> migrateV3ToV4(root)
                4 -> Unit
                5 -> Unit
                6 -> Unit
                7 -> migrateV7ToV8(root)
                8 -> Unit
                else -> error("No config migration is available for version $version.")
            }
            version++
        }
        root.addProperty("version", ConfigData.CURRENT_VERSION)
        fillMissingFields(root, defaultConfig)
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

    private fun migrateV2ToV3(root: JsonObject) {
        if (root.has("BlurImages")) {
            return
        }
        val adapter = root.get("Bot")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("Adapter")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
        root.addProperty("BlurImages", !adapter.equals("TELEGRAM", ignoreCase = true))
    }

    private fun migrateV3ToV4(root: JsonObject) {
        val bot = root.get("Bot")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject().also { root.add("Bot", it) }
        val napcat = normalizeSection(bot, "napcat", "Napcat")
        val telegram = normalizeSection(bot, "telegram", "Telegram")
        val adapter = bot.remove("Adapter")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            .orEmpty()
        val legacyBlur = root.remove("BlurImages")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean

        if (!napcat.has("Enabled")) {
            napcat.addProperty("Enabled", !adapter.equals("TELEGRAM", ignoreCase = true))
        }
        if (!telegram.has("Enabled")) {
            telegram.addProperty("Enabled", adapter.equals("TELEGRAM", ignoreCase = true))
        }
        if (!napcat.has("BlurImages")) {
            napcat.addProperty(
                "BlurImages",
                legacyBlur?.takeIf { !adapter.equals("TELEGRAM", ignoreCase = true) } ?: true,
            )
        }
        if (!telegram.has("BlurImages")) {
            telegram.addProperty(
                "BlurImages",
                legacyBlur?.takeIf { adapter.equals("TELEGRAM", ignoreCase = true) } ?: false,
            )
        }
    }

    private fun migrateV7ToV8(root: JsonObject) {
        val telegram = root.get("Bot")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("telegram")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return
        telegram.remove("TelegraphPreview")
        val largeFile = telegram.get("LargeFile")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return
        val policy = largeFile.get("Policy")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
        if (policy.equals("TELEGRAPH", ignoreCase = true)) {
            largeFile.addProperty("Policy", LargeFilePolicy.SPLIT_PDF.name)
        }
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

    private fun fillMissingFields(target: JsonObject, defaults: JsonObject) {
        defaults.entrySet().forEach { (key, defaultValue) ->
            if (!target.has(key)) {
                target.add(key, defaultValue.deepCopy())
                return@forEach
            }
            val existingValue = target.get(key)
            if (existingValue.isJsonObject && defaultValue.isJsonObject) {
                fillMissingFields(existingValue.asJsonObject, defaultValue.asJsonObject)
            }
        }
    }
}
