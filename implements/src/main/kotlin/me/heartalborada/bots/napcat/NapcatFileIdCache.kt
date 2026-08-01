package me.heartalborada.bots.napcat

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.utils.calculateSHA256
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.io.File

private object NapcatFileIdCacheTable : Table("napcat_file_id_cache") {
    val botId = long("bot_id")
    val contentSha256 = varchar("content_sha256", 64)
    val fileId = varchar("file_id", 2048)
    val fileName = varchar("file_name", 512)
    val fileSize = long("file_size")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(botId, contentSha256)
}

class NapcatFileIdCache(private val database: Database) {
    init {
        transaction(database) { SchemaUtils.create(NapcatFileIdCacheTable) }
    }

    fun find(botId: Long, contentSha256: String): CachedNapcatFile? = transaction(database) {
        NapcatFileIdCacheTable.selectAll()
            .where {
                (NapcatFileIdCacheTable.botId eq botId) and
                    (NapcatFileIdCacheTable.contentSha256 eq contentSha256)
            }
            .singleOrNull()
            ?.let { row ->
                CachedNapcatFile(
                    fileId = row[NapcatFileIdCacheTable.fileId],
                    fileName = row[NapcatFileIdCacheTable.fileName],
                    fileSize = row[NapcatFileIdCacheTable.fileSize],
                )
            }
    }

    fun put(botId: Long, contentSha256: String, fileId: String, fileName: String, fileSize: Long) {
        require(fileId.isNotBlank()) { "NapCat file ID must not be blank." }
        transaction(database) {
            val updated = NapcatFileIdCacheTable.update({
                (NapcatFileIdCacheTable.botId eq botId) and
                    (NapcatFileIdCacheTable.contentSha256 eq contentSha256)
            }) {
                it[NapcatFileIdCacheTable.fileId] = fileId
                it[NapcatFileIdCacheTable.fileName] = fileName
                it[NapcatFileIdCacheTable.fileSize] = fileSize
                it[NapcatFileIdCacheTable.updatedAt] = System.currentTimeMillis()
            }
            if (updated == 0) {
                NapcatFileIdCacheTable.insert {
                    it[NapcatFileIdCacheTable.botId] = botId
                    it[NapcatFileIdCacheTable.contentSha256] = contentSha256
                    it[NapcatFileIdCacheTable.fileId] = fileId
                    it[NapcatFileIdCacheTable.fileName] = fileName
                    it[NapcatFileIdCacheTable.fileSize] = fileSize
                    it[NapcatFileIdCacheTable.updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    fun invalidate(botId: Long, contentSha256: String) {
        transaction(database) {
            NapcatFileIdCacheTable.deleteWhere {
                (NapcatFileIdCacheTable.botId eq botId) and
                    (NapcatFileIdCacheTable.contentSha256 eq contentSha256)
            }
        }
    }
}

data class CachedNapcatFile(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
)

data class NapcatFileReceipt(
    val messageId: Long,
    val fileId: String?,
)

interface NapcatFileClient {
    val napcatBotId: Long

    fun uploadFile(type: ChatType, target: Long, name: String, file: File): NapcatFileReceipt

    fun resendFile(type: ChatType, target: Long, name: String, fileId: String): NapcatFileReceipt
}

class NapcatCachedFileSender(
    private val client: NapcatFileClient,
    private val cache: NapcatFileIdCache,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun send(type: ChatType, target: Long, name: String, file: File): Boolean {
        require(file.isFile) { "NapCat file does not exist: ${file.absolutePath}" }
        val botId = client.napcatBotId
        check(botId > 0) { "NapCat bot identity is unavailable before the adapter is connected." }
        val hash = file.calculateSHA256()
        val lock = deliveryLocks[Math.floorMod("$botId:$hash".hashCode(), deliveryLocks.size)]
        synchronized(lock) {
            cache.find(botId, hash)?.let { cached ->
                try {
                    client.resendFile(type, target, name, cached.fileId)
                    return true
                } catch (exception: NapcatApiException) {
                    logger.info("NapCat rejected cached file ID for {}; uploading it again.", hash)
                    cache.invalidate(botId, hash)
                }
            }

            val receipt = client.uploadFile(type, target, name, file)
            receipt.fileId?.takeIf(String::isNotBlank)?.let { fileId ->
                cache.put(botId, hash, fileId, name, file.length())
            }
            return true
        }
    }

    private companion object {
        val deliveryLocks = Array(256) { Any() }
    }
}

class NapcatApiException(
    val action: String,
    val retcode: Int,
    message: String,
) : IllegalStateException("NapCat action $action failed with code $retcode: $message")
