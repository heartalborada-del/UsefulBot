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
import kotlin.coroutines.cancellation.CancellationException

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
        logger.debug("Starting download...")
        val size = getSize()
        if (size != null) {
            progress.total = size
            randomAccessFile.setLength(size)
        }
        logger.debug("File Size: $size")

        if (progressFile.exists()) {
            val l = FileUtils.readFileToString(progressFile, Charsets.UTF_8)
            try {
                val np = Gson().fromJson(l, ProgressData::class.java)
                if (np.total == size) progress = np
                logger.debug("Resuming Last Download Progress")
            } catch (e: Exception) {
                logger.error("Failed to parse progress file: ${progressFile.absolutePath}", e)
            }
        }

        val tasks = mutableListOf<Deferred<Pair<Long, Long>>>()
        val unDownloadedRanges = getUnDownloadedRanges(progress, size ?: 0L)
        runBlocking {
            unDownloadedRanges.forEachIndexed { index, (from, to) ->
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
                try {
                    logger.debug("Starting download from $from to $to")
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Range", "bytes=$from-$to")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Failed to download file: $response")
                        }
                        val buffer = ByteArray(8192)
                        response.body?.byteStream()?.use {
                            it
                            while (true) {
                                try {
                                    ensureActive()
                                    val bytesRead = it.read(buffer, 0, buffer.size)
                                    if (bytesRead == -1) break
                                    save(buffer, bytesRead)
                                } catch (_: CancellationException) {
                                    return@withContext Pair(from, position)
                                }
                            }
                        }
                        return@withContext Pair(from, position)
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to download file: $url", e)
                    return@withContext Pair(from, position)
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