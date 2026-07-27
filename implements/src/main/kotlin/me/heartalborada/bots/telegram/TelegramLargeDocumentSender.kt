package me.heartalborada.bots.telegram

import me.heartalborada.commons.ChatType
import me.heartalborada.commons.comic.SizeBoundedPdfSplitter
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

class TelegramLargeDocumentSender(
    private val client: TelegramDocumentClient,
    private val cache: TelegramFileIdCache,
    private val splitter: SizeBoundedPdfSplitter,
    private val tempDirectory: File,
    private val maximumPartBytes: Long,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(maximumPartBytes > 0) { "Maximum Telegram PDF part size must be positive." }
        tempDirectory.mkdirs()
        splitter.cleanupStaleParts(tempDirectory, STALE_PART_AGE_MILLIS)
    }

    fun send(
        type: ChatType,
        target: Long,
        displayName: String,
        source: File,
        password: String?,
    ): Boolean {
        require(source.isFile) { "Telegram PDF does not exist: ${source.absolutePath}" }
        require(displayName.endsWith(".pdf", ignoreCase = true)) {
            "Size-bounded Telegram delivery only supports PDF documents."
        }
        val botId = client.telegramBotId
        check(botId > 0) { "Telegram bot identity is unavailable before the adapter is connected." }
        val contentSha256 = fingerprint(source)
        val lockKey = "$botId:$contentSha256:$maximumPartBytes"
        val lock = deliveryLocks[Math.floorMod(lockKey.hashCode(), deliveryLocks.size)]
        synchronized(lock) {
            val cached = cache.findComplete(botId, contentSha256, maximumPartBytes)
            if (cached.isNotEmpty()) {
                sendCached(
                    type = type,
                    target = target,
                    source = source,
                    password = password,
                    botId = botId,
                    contentSha256 = contentSha256,
                    parts = cached,
                )
            } else {
                splitUploadAndCache(
                    type = type,
                    target = target,
                    displayName = displayName,
                    source = source,
                    password = password,
                    botId = botId,
                    contentSha256 = contentSha256,
                )
            }
        }
        return true
    }

    private fun sendCached(
        type: ChatType,
        target: Long,
        source: File,
        password: String?,
        botId: Long,
        contentSha256: String,
        parts: List<CachedTelegramPdfPart>,
    ) {
        parts.forEach { part ->
            try {
                client.resendDocument(type, target, part.fileId)
            } catch (exception: TelegramApiException) {
                if (!exception.isInvalidFileIdentifier()) throw exception
                logger.info(
                    "Telegram rejected cached file_id for PDF {} part {}; uploading that part again.",
                    contentSha256,
                    part.index,
                )
                replaceRejectedPart(
                    type = type,
                    target = target,
                    source = source,
                    password = password,
                    botId = botId,
                    contentSha256 = contentSha256,
                    part = part,
                )
            }
        }
    }

    private fun replaceRejectedPart(
        type: ChatType,
        target: Long,
        source: File,
        password: String?,
        botId: Long,
        contentSha256: String,
        part: CachedTelegramPdfPart,
    ) {
        val temporaryFile = File.createTempFile(
            "usefulbot-tg-part-repair-",
            ".pdf",
            tempDirectory,
        )
        try {
            splitter.writePart(
                source = source,
                password = password,
                startPage = part.startPage,
                endPage = part.endPage,
                target = temporaryFile,
                outputPassword = null,
            )
            check(temporaryFile.length() <= maximumPartBytes) {
                "Regenerated Telegram PDF part ${part.index} exceeds the configured size limit."
            }
            val receipt = client.uploadDocument(type, target, part.fileName, temporaryFile)
            cache.updateFileId(
                botId = botId,
                contentSha256 = contentSha256,
                maximumPartBytes = maximumPartBytes,
                partIndex = part.index,
                fileId = receipt.fileId,
                fileUniqueId = receipt.fileUniqueId,
                fileSize = temporaryFile.length(),
            )
        } finally {
            temporaryFile.delete()
        }
    }

    private fun splitUploadAndCache(
        type: ChatType,
        target: Long,
        displayName: String,
        source: File,
        password: String?,
        botId: Long,
        contentSha256: String,
    ) {
        data class UploadedPart(
            val index: Int,
            val startPage: Int,
            val endPage: Int,
            val fileName: String,
            val receipt: TelegramDocumentReceipt,
            val fileSize: Long,
        )

        val uploaded = mutableListOf<UploadedPart>()
        splitter.forEachPart(
            source = source,
            password = password,
            tempDirectory = tempDirectory,
            maximumPartBytes = maximumPartBytes,
            outputPassword = null,
        ) { part ->
            val fileName = partFileName(displayName, part.index)
            val receipt = client.uploadDocument(type, target, fileName, part.file)
            uploaded += UploadedPart(
                index = part.index,
                startPage = part.startPage,
                endPage = part.endPage,
                fileName = fileName,
                receipt = receipt,
                fileSize = part.file.length(),
            )
        }
        val partCount = uploaded.size
        cache.replace(
            botId = botId,
            contentSha256 = contentSha256,
            maximumPartBytes = maximumPartBytes,
            parts = uploaded.map { part ->
                CachedTelegramPdfPart(
                    index = part.index,
                    partCount = partCount,
                    startPage = part.startPage,
                    endPage = part.endPage,
                    fileName = part.fileName,
                    fileId = part.receipt.fileId,
                    fileUniqueId = part.receipt.fileUniqueId,
                    fileSize = part.fileSize,
                )
            },
        )
    }

    private fun partFileName(displayName: String, index: Int): String {
        val baseName = displayName.substringBeforeLast('.')
        return "$baseName.part-${index.toString().padStart(3, '0')}.pdf"
    }

    private fun fingerprint(file: File): String {
        val key = FileFingerprintKey(
            canonicalPath = file.canonicalPath,
            size = file.length(),
            lastModified = file.lastModified(),
        )
        synchronized(fingerprintCache) {
            fingerprintCache[key]?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(FINGERPRINT_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(FINGERPRINT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        synchronized(fingerprintCache) {
            fingerprintCache[key] = hash
        }
        return hash
    }

    private data class FileFingerprintKey(
        val canonicalPath: String,
        val size: Long,
        val lastModified: Long,
    )

    private companion object {
        const val FINGERPRINT_BUFFER_SIZE = 1024 * 1024
        const val STALE_PART_AGE_MILLIS = 24L * 60 * 60 * 1000
        val deliveryLocks = Array(256) { Any() }
        val fingerprintCache = object : LinkedHashMap<FileFingerprintKey, String>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<FileFingerprintKey, String>?,
            ): Boolean = size > 256
        }
    }
}
