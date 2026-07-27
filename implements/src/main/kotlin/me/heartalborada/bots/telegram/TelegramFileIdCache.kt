package me.heartalborada.bots.telegram

import java.sql.Connection
import javax.sql.DataSource

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

class TelegramFileIdCache(private val dataSource: DataSource) {
    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS telegram_pdf_part_cache (
                            bot_id BIGINT NOT NULL,
                            content_sha256 VARCHAR(64) NOT NULL,
                            maximum_part_bytes BIGINT NOT NULL,
                            part_index INT NOT NULL,
                            part_count INT NOT NULL,
                            start_page INT NOT NULL,
                            end_page INT NOT NULL,
                            file_name VARCHAR(512) NOT NULL,
                            file_id VARCHAR(1024) NOT NULL,
                            file_unique_id VARCHAR(1024),
                            file_size BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (bot_id, content_sha256, maximum_part_bytes, part_index)
                        )
                    """.trimIndent()
                )
            }
        }
    }

    fun findComplete(
        botId: Long,
        contentSha256: String,
        maximumPartBytes: Long,
    ): List<CachedTelegramPdfPart> {
        val parts = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT part_index, part_count, start_page, end_page, file_name,
                           file_id, file_unique_id, file_size
                    FROM telegram_pdf_part_cache
                    WHERE bot_id = ? AND content_sha256 = ? AND maximum_part_bytes = ?
                    ORDER BY part_index
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, botId)
                statement.setString(2, contentSha256)
                statement.setLong(3, maximumPartBytes)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                CachedTelegramPdfPart(
                                    index = result.getInt("part_index"),
                                    partCount = result.getInt("part_count"),
                                    startPage = result.getInt("start_page"),
                                    endPage = result.getInt("end_page"),
                                    fileName = result.getString("file_name"),
                                    fileId = result.getString("file_id"),
                                    fileUniqueId = result.getString("file_unique_id"),
                                    fileSize = result.getLong("file_size"),
                                )
                            )
                        }
                    }
                }
            }
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
        dataSource.connection.use { connection ->
            connection.inTransaction {
                delete(connection, botId, contentSha256, maximumPartBytes)
                connection.prepareStatement(
                    """
                        INSERT INTO telegram_pdf_part_cache (
                            bot_id, content_sha256, maximum_part_bytes, part_index, part_count,
                            start_page, end_page, file_name, file_id, file_unique_id, file_size, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    val now = System.currentTimeMillis()
                    parts.forEach { part ->
                        statement.setLong(1, botId)
                        statement.setString(2, contentSha256)
                        statement.setLong(3, maximumPartBytes)
                        statement.setInt(4, part.index)
                        statement.setInt(5, part.partCount)
                        statement.setInt(6, part.startPage)
                        statement.setInt(7, part.endPage)
                        statement.setString(8, part.fileName)
                        statement.setString(9, part.fileId)
                        statement.setString(10, part.fileUniqueId)
                        statement.setLong(11, part.fileSize)
                        statement.setLong(12, now)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
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
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    UPDATE telegram_pdf_part_cache
                    SET file_id = ?, file_unique_id = ?, file_size = ?, updated_at = ?
                    WHERE bot_id = ? AND content_sha256 = ?
                      AND maximum_part_bytes = ? AND part_index = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, fileId)
                statement.setString(2, fileUniqueId)
                statement.setLong(3, fileSize)
                statement.setLong(4, System.currentTimeMillis())
                statement.setLong(5, botId)
                statement.setString(6, contentSha256)
                statement.setLong(7, maximumPartBytes)
                statement.setInt(8, partIndex)
                check(statement.executeUpdate() == 1) {
                    "Telegram PDF part $partIndex was not present in the file ID cache."
                }
            }
        }
    }

    fun invalidate(botId: Long, contentSha256: String, maximumPartBytes: Long) {
        dataSource.connection.use { connection ->
            delete(connection, botId, contentSha256, maximumPartBytes)
        }
    }

    private fun delete(
        connection: Connection,
        botId: Long,
        contentSha256: String,
        maximumPartBytes: Long,
    ) {
        connection.prepareStatement(
            """
                DELETE FROM telegram_pdf_part_cache
                WHERE bot_id = ? AND content_sha256 = ? AND maximum_part_bytes = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, botId)
            statement.setString(2, contentSha256)
            statement.setLong(3, maximumPartBytes)
            statement.executeUpdate()
        }
    }

    private inline fun <T> Connection.inTransaction(block: () -> T): T {
        val previousAutoCommit = autoCommit
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (exception: Exception) {
            rollback()
            throw exception
        } finally {
            autoCommit = previousAutoCommit
        }
    }
}
