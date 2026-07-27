package me.heartalborada.commons.comic

import me.heartalborada.commons.Util.Companion.randomString
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.Splitter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File

data class GeneratedPdfPart(
    val index: Int,
    val startPage: Int,
    val endPage: Int,
    val file: File,
)

data class PdfPartRange(
    val index: Int,
    val startPage: Int,
    val endPage: Int,
)

class PdfPartTooLargeException(
    val page: Int,
    val size: Long,
    val maximumSize: Long,
) : IllegalStateException(
    "PDF page $page produces a $size-byte part, exceeding the $maximumSize-byte limit."
)

/**
 * Copies pages from an existing PDF into one temporary part at a time.
 *
 * Page numbers exposed by this class are one-based. The temporary part is
 * deleted immediately after [consume] returns, including when it throws.
 */
class SizeBoundedPdfSplitter {
    fun forEachPart(
        source: File,
        password: String?,
        tempDirectory: File,
        maximumPartBytes: Long,
        outputPassword: String? = password,
        consume: (GeneratedPdfPart) -> Unit,
    ): List<PdfPartRange> {
        require(source.isFile) { "Source PDF does not exist: ${source.absolutePath}" }
        require(maximumPartBytes > 0) { "Maximum PDF part size must be positive." }
        tempDirectory.mkdirs()

        val ranges = mutableListOf<PdfPartRange>()
        load(source, password).use { document ->
            var startPageIndex = 0
            var partIndex = 1
            while (startPageIndex < document.numberOfPages) {
                val target = File.createTempFile(
                    "${TEMP_FILE_PREFIX}${source.nameWithoutExtension}-",
                    ".pdf",
                    tempDirectory,
                )
                try {
                    val endPageIndex = findLargestEndPage(
                        source = document,
                        startPageIndex = startPageIndex,
                        target = target,
                        outputPassword = outputPassword,
                        maximumPartBytes = maximumPartBytes,
                    )
                    val part = GeneratedPdfPart(
                        index = partIndex,
                        startPage = startPageIndex + 1,
                        endPage = endPageIndex + 1,
                        file = target,
                    )
                    consume(part)
                    ranges += PdfPartRange(part.index, part.startPage, part.endPage)
                    startPageIndex = endPageIndex + 1
                    partIndex++
                } finally {
                    target.delete()
                }
            }
        }
        return ranges
    }

    fun writePart(
        source: File,
        password: String?,
        startPage: Int,
        endPage: Int,
        target: File,
        outputPassword: String? = password,
    ) {
        require(startPage > 0) { "Start page must be positive." }
        require(endPage >= startPage) { "End page must not precede start page." }
        target.parentFile?.mkdirs()
        load(source, password).use { document ->
            require(endPage <= document.numberOfPages) {
                "Requested pages $startPage-$endPage, but the PDF has ${document.numberOfPages} pages."
            }
            writeRange(document, startPage - 1, endPage - 1, target, outputPassword)
        }
    }

    fun writeUnlockedCopy(source: File, password: String?, target: File) {
        require(source.isFile) { "Source PDF does not exist: ${source.absolutePath}" }
        target.parentFile?.mkdirs()
        load(source, password).use { document ->
            document.isAllSecurityToBeRemoved = true
            document.save(target)
        }
    }

    fun cleanupStaleParts(directory: File, olderThanMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (!directory.isDirectory || olderThanMillis < 0) return
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(TEMP_FILE_PREFIX) && file.extension == "pdf"
        }.orEmpty().forEach { file ->
            if (nowMillis - file.lastModified() >= olderThanMillis) {
                file.delete()
            }
        }
    }

    private fun findLargestEndPage(
        source: PDDocument,
        startPageIndex: Int,
        target: File,
        outputPassword: String?,
        maximumPartBytes: Long,
    ): Int {
        writeRange(source, startPageIndex, startPageIndex, target, outputPassword)
        if (target.length() > maximumPartBytes) {
            throw PdfPartTooLargeException(
                page = startPageIndex + 1,
                size = target.length(),
                maximumSize = maximumPartBytes,
            )
        }

        var bestEnd = startPageIndex
        var lastWrittenEnd = startPageIndex
        var step = 1
        var firstRejectedEnd: Int? = null
        while (bestEnd < source.numberOfPages - 1) {
            val candidateEnd = minOf(source.numberOfPages - 1, bestEnd + step)
            writeRange(source, startPageIndex, candidateEnd, target, outputPassword)
            lastWrittenEnd = candidateEnd
            if (target.length() <= maximumPartBytes) {
                bestEnd = candidateEnd
                if (bestEnd == source.numberOfPages - 1) break
                step = (step * 2).coerceAtMost(source.numberOfPages)
            } else {
                firstRejectedEnd = candidateEnd
                break
            }
        }

        var low = bestEnd + 1
        var high = (firstRejectedEnd ?: bestEnd) - 1
        while (low <= high) {
            val middle = low + (high - low) / 2
            writeRange(source, startPageIndex, middle, target, outputPassword)
            lastWrittenEnd = middle
            if (target.length() <= maximumPartBytes) {
                bestEnd = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (lastWrittenEnd != bestEnd) {
            writeRange(source, startPageIndex, bestEnd, target, outputPassword)
        }
        return bestEnd
    }

    private fun writeRange(
        source: PDDocument,
        startPageIndex: Int,
        endPageIndex: Int,
        target: File,
        password: String?,
    ) {
        val splitter = Splitter().apply {
            setStartPage(startPageIndex + 1)
            setEndPage(endPageIndex + 1)
            setSplitAtPage(Int.MAX_VALUE)
        }
        val parts = splitter.split(source)
        check(parts.size == 1) {
            "Expected one PDF part for pages ${startPageIndex + 1}-${endPageIndex + 1}, got ${parts.size}."
        }
        parts.single().use { part ->
            if (password != null) {
                part.protect(
                    StandardProtectionPolicy(
                        randomString(20),
                        password,
                        AccessPermission(),
                    )
                )
            }
            part.save(target)
        }
    }

    private fun load(source: File, password: String?): PDDocument =
        if (password == null) Loader.loadPDF(source) else Loader.loadPDF(source, password)

    private companion object {
        const val TEMP_FILE_PREFIX = "usefulbot-tg-part-"
    }
}
