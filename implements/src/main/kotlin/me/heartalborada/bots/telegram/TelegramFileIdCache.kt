package me.heartalborada.bots.telegram

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class CachedTelegramPdfPart(
    val index: Int,
    val partCount: Int,
    val startPage: Int,
    val endPage: Int,
    val fileName: String,
    val fileId: String,
    val fileUniqueId: String?,
    val fileSize: Long,
)

private object TelegramPdfPartCacheTable : Table("telegram_pdf_part_cache") {
    val botId = long("bot_id")
    val contentSha256 = varchar("content_sha256", 64)
    val maximumPartBytes = long("maximum_part_bytes")
    val partIndex = integer("part_index")
    val partCount = integer("part_count")
    val startPage = integer("start_page")
    val endPage = integer("end_page")
    val fileName = varchar("file_name", 512)
    val fileId = varchar("file_id", 1024)
    val fileUniqueId = varchar("file_unique_id", 1024).nullable()
    val fileSize = long("file_size")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(botId, contentSha256, maximumPartBytes, partIndex)
}

class TelegramFileIdCache(private val database: Database) {
    init {
        transaction(database) {
            SchemaUtils.create(TelegramPdfPartCacheTable)
        }
    }

    fun findComplete(
        botId: Long,
        contentSha256: String,
        maximumPartBytes: Long,
    ): List<CachedTelegramPdfPart> {
        val parts = transaction(database) {
            TelegramPdfPartCacheTable
                .selectAll()
                .where {
                    (TelegramPdfPartCacheTable.botId eq botId) and
                        (TelegramPdfPartCacheTable.contentSha256 eq contentSha256) and
                        (TelegramPdfPartCacheTable.maximumPartBytes eq maximumPartBytes)
                }
                .orderBy(TelegramPdfPartCacheTable.partIndex to SortOrder.ASC)
                .map(::toCachedPart)
        }
        val expectedCount = parts.firstOrNull()?.partCount ?: return emptyList()
        return parts.takeIf { candidate ->
            candidate.size == expectedCount &&
                candidate.map(CachedTelegramPdfPart::index) == (1..expectedCount).toList() &&
                candidate.all { it.partCount == expectedCount }
        }.orEmpty()
    }

    fun replace(
        botId: Long,
        contentSha256: String,
        maximumPartBytes: Long,
        parts: List<CachedTelegramPdfPart>,
    ) {
        require(parts.isNotEmpty()) { "At least one Telegram PDF part is required." }
        require(parts.map(CachedTelegramPdfPart::index) == (1..parts.size).toList()) {
            "Telegram PDF part indexes must be contiguous and one-based."
        }
        require(parts.all { it.partCount == parts.size }) {
            "Telegram PDF part count must match the number of cached parts."
        }
        transaction(database) {
            delete(botId, contentSha256, maximumPartBytes)
            val now = System.currentTimeMillis()
            TelegramPdfPartCacheTable.batchInsert(parts) { part ->
                this[TelegramPdfPartCacheTable.botId] = botId
                this[TelegramPdfPartCacheTable.contentSha256] = contentSha256
                this[TelegramPdfPartCacheTable.maximumPartBytes] = maximumPartBytes
                this[TelegramPdfPartCacheTable.partIndex] = part.index
                this[TelegramPdfPartCacheTable.partCount] = part.partCount
                this[TelegramPdfPartCacheTable.startPage] = part.startPage
                this[TelegramPdfPartCacheTable.endPage] = part.endPage
                this[TelegramPdfPartCacheTable.fileName] = part.fileName
                this[TelegramPdfPartCacheTable.fileId] = part.fileId
                this[TelegramPdfPartCacheTable.fileUniqueId] = part.fileUniqueId
                this[TelegramPdfPartCacheTable.fileSize] = part.fileSize
                this[TelegramPdfPartCacheTable.updatedAt] = now
            }
        }
    }

    fun updateFileId(
        botId: Long,
        contentSha256: String,
        maximumPartBytes: Long,
        partIndex: Int,
        fileId: String,
        fileUniqueId: String?,
        fileSize: Long,
    ) {
        val updatedRows = transaction(database) {
            TelegramPdfPartCacheTable.update({
                (TelegramPdfPartCacheTable.botId eq botId) and
                    (TelegramPdfPartCacheTable.contentSha256 eq contentSha256) and
                    (TelegramPdfPartCacheTable.maximumPartBytes eq maximumPartBytes) and
                    (TelegramPdfPartCacheTable.partIndex eq partIndex)
            }) {
                it[TelegramPdfPartCacheTable.fileId] = fileId
                it[TelegramPdfPartCacheTable.fileUniqueId] = fileUniqueId
                it[TelegramPdfPartCacheTable.fileSize] = fileSize
                it[TelegramPdfPartCacheTable.updatedAt] = System.currentTimeMillis()
            }
        }
        check(updatedRows == 1) {
            "Telegram PDF part $partIndex was not present in the file ID cache."
        }
    }

    fun invalidate(botId: Long, contentSha256: String, maximumPartBytes: Long) {
        transaction(database) {
            delete(botId, contentSha256, maximumPartBytes)
        }
    }

    private fun delete(botId: Long, contentSha256: String, maximumPartBytes: Long) {
        TelegramPdfPartCacheTable.deleteWhere {
            (TelegramPdfPartCacheTable.botId eq botId) and
                (TelegramPdfPartCacheTable.contentSha256 eq contentSha256) and
                (TelegramPdfPartCacheTable.maximumPartBytes eq maximumPartBytes)
        }
    }

    private fun toCachedPart(row: ResultRow) = CachedTelegramPdfPart(
        index = row[TelegramPdfPartCacheTable.partIndex],
        partCount = row[TelegramPdfPartCacheTable.partCount],
        startPage = row[TelegramPdfPartCacheTable.startPage],
        endPage = row[TelegramPdfPartCacheTable.endPage],
        fileName = row[TelegramPdfPartCacheTable.fileName],
        fileId = row[TelegramPdfPartCacheTable.fileId],
        fileUniqueId = row[TelegramPdfPartCacheTable.fileUniqueId],
        fileSize = row[TelegramPdfPartCacheTable.fileSize],
    )
}
