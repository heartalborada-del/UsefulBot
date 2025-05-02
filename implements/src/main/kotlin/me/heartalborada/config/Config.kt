package me.heartalborada.config

import com.google.gson.GsonBuilder
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
            config = gson.fromJson(s,ConfigData::class.java)
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