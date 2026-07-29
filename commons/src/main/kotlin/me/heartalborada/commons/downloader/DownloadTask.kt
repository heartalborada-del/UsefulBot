package me.heartalborada.commons.downloader

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.heartalborada.commons.Util.Companion.mergeIntervals
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext

class DownloadTask(
    private val url: String,
    destFile: File,
    private val progressFile: File,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    threadCount: Int = 1,
) {
    private var logger = LoggerFactory.getLogger(this::class.java)
    private val dispatcher = Dispatchers.IO.limitedParallelism(threadCount)
    private val ctx: CoroutineContext by lazy {
        SupervisorJob() + dispatcher + CoroutineName("DownloaderTaskScope")
    }
    private val randomAccessFile = RandomAccessFile(destFile, "rw")
    private val mutex = Mutex()
    private var progress: ProgressData = ProgressData(
        name = destFile.name,
        downloaded = mutableListOf(),
    )
    private val chunkSize = 2 * 1024 * 1024L // Block size

    fun download() {
        try {
            downloadInternal()
        } finally {
            randomAccessFile.close()
        }
    }

    private fun downloadInternal() {
        logger.debug("Starting download...")
        val size = getSize()
            ?: throw IOException("The server did not provide a Content-Length for $url")
        require(size >= 0) { "Invalid content length: $size" }
        progress.total = size
        randomAccessFile.setLength(size)
        logger.debug("File Size: $size")

        if (size > 0 && !supportsRangeRequests(size)) {
            logger.debug("Server does not support range requests; using a single stream")
            downloadSingleStream(size)
            progress.downloaded = mutableListOf(0L to size - 1)
            finishProgress()
            return
        }

        if (progressFile.exists()) {
            val l = FileUtils.readFileToString(progressFile, Charsets.UTF_8)
            try {
                val np = Gson().fromJson(l, ProgressData::class.java)
                if (np.total == size && np.downloaded.all { it.first >= 0 && it.second < size }) {
                    progress = np
                    logger.debug("Resuming Last Download Progress")
                }
            } catch (e: Exception) {
                logger.error("Failed to parse progress file: ${progressFile.absolutePath}", e)
            }
        }

        val tasks = mutableListOf<Deferred<Pair<Long, Long>>>()
        val unDownloadedRanges = getUnDownloadedRanges(progress, size)
        runBlocking {
            unDownloadedRanges.forEach { (from, to) ->
                val task = DownloadTask(url, from, to)
                tasks.add(async(dispatcher) { task.start() })
            }
            val finish = tasks.awaitAll()
            withContext(NonCancellable) {
                val d = mergeIntervals(progress.downloaded + finish)
                progress.downloaded = d
                saveProgress()
            }
        }
        if (progress.downloaded != listOf(0L to size - 1) && size > 0) {
            throw IOException("Download did not cover the complete file: ${progress.downloaded}")
        }
        finishProgress()
    }

    private fun finishProgress() {
        if (progressFile.exists() && !progressFile.delete()) {
            logger.warn("Failed to delete completed download progress file: {}", progressFile.absolutePath)
        }
    }

    private fun supportsRangeRequests(totalSize: Long): Boolean {
        val request = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 200) return false
            if (response.code != 206) {
                throw IOException("Failed to probe range support: HTTP ${response.code}")
            }
            return response.header("Content-Range") == "bytes 0-0/$totalSize"
        }
    }

    private fun downloadSingleStream(expectedSize: Long) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download file: HTTP ${response.code}")
            }
            val body = response.body
            var position = 0L
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (position < expectedSize) {
                    val remaining = expectedSize - position
                    val bytesRead = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (bytesRead == -1) {
                        throw IOException("Truncated download at byte $position of $expectedSize")
                    }
                    randomAccessFile.seek(position)
                    randomAccessFile.write(buffer, 0, bytesRead)
                    position += bytesRead
                }
                if (input.read() != -1) {
                    throw IOException("Download exceeded expected size $expectedSize")
                }
            }
        }
    }

    fun getSize(): Long? {
        client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
            if (!resp.isSuccessful || resp.code != 200) throw IllegalStateException("Invalid status code: ${resp.code}")
            return resp.header("Content-Length")?.toLongOrNull()
        }
    }

    inner class DownloadTask(
        val url: String,
        val from: Long,
        val to: Long,
    ) {
        private var position = from
        suspend fun start(): Pair<Long, Long> =
            withContext(ctx) {
                logger.debug("Starting download from $from to $to")
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$from-$to")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 206) {
                        throw IOException("Server did not honor Range bytes=$from-$to: HTTP ${response.code}")
                    }
                    val expectedContentRange = "bytes $from-$to/${progress.total}"
                    val contentRange = response.header("Content-Range")
                    if (contentRange != expectedContentRange) {
                        throw IOException(
                            "Invalid Content-Range for bytes=$from-$to: ${contentRange ?: "<missing>"}"
                        )
                    }
                    val body = response.body
                    val buffer = ByteArray(8192)
                    body.byteStream().use { input ->
                        while (position <= to) {
                            ensureActive()
                            val remaining = to - position + 1
                            val bytesRead = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (bytesRead == -1) {
                                throw IOException(
                                    "Truncated range bytes=$from-$to at byte $position"
                                )
                            }
                            save(buffer, bytesRead)
                        }
                    }
                    return@withContext Pair(from, to)
                }
            }

        suspend fun save(bytes: ByteArray, length: Int) {
            mutex.withLock {
                randomAccessFile.seek(position)
                randomAccessFile.write(bytes, 0, length)
                position += length.toLong()
            }
        }
    }

    private fun getUnDownloadedRanges(progress: ProgressData, totalSize: Long): List<Pair<Long, Long>> {
        val downloadedRanges = mergeIntervals(progress.downloaded.toList())
        val unDownloaded = mutableListOf<Pair<Long, Long>>()

        var start = 0L
        for ((rangeStart, rangeEnd) in downloadedRanges) {
            if (start < rangeStart) {
                unDownloaded.add(Pair(start, rangeStart - 1))
            }
            start = rangeEnd + 1
        }
        if (start < totalSize) {
            unDownloaded.add(Pair(start, totalSize - 1))
        }

        val chunkedRanges = mutableListOf<Pair<Long, Long>>()
        for ((rangeStart, rangeEnd) in unDownloaded) {
            var chunkStart = rangeStart
            while (chunkStart <= rangeEnd) {
                val chunkEnd = minOf(chunkStart + chunkSize - 1, rangeEnd)
                chunkedRanges.add(Pair(chunkStart, chunkEnd))
                chunkStart = chunkEnd + 1
            }
        }
        return chunkedRanges
    }

    private fun saveProgress() {
        progress.downloaded = mergeIntervals(progress.downloaded)
        FileUtils.writeStringToFile(progressFile, Gson().toJson(progress), Charsets.UTF_8)
        logger.debug("Progress saved: {}", progress.downloaded)
    }
}
