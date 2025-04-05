package me.heartalborada.commons.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.net.ssl.SSLException

class DownloadTask(
    private val url: String,
    private val destFile: File,
    private val client: OkHttpClient,
    private val maxRetries: Int = 8,
    private val retryDelay: Long = 100L
) {
    private var downloadedLength = if (destFile.exists()) destFile.length() else 0L
    private var logger = LoggerFactory.getLogger(this::class.java)
    @Throws(IOException::class)
    suspend fun download() = coroutineScope {
        withContext(Dispatchers.IO) {
            var retryCount = 0
            while (retryCount <= maxRetries) {
                try {
                    val totalLength = getFileSize()
                    if (totalLength == downloadedLength) return@withContext

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Range", "bytes=$downloadedLength-")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (retryCount < maxRetries) {
                                retryCount++
                                delayRetry(retryCount)
                                return@use
                            }
                            throw IOException("Failed to download file: $response")
                        }
                        saveFile(response)
                        return@withContext
                    }
                } catch (e: SSLException) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        logger.info("SSL error occurred, retrying ($retryCount/$maxRetries)...")
                        delayRetry(retryCount)
                        continue
                    }
                    throw IOException("SSL error after $maxRetries retries", e)
                } catch (e: IOException) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        logger.info("IO error occurred, retrying ($retryCount/$maxRetries)...")
                        delayRetry(retryCount)
                        continue
                    }
                    throw e
                }
            }
        }
    }

    private suspend fun delayRetry(retryCount: Int) {
        val delay = retryDelay * retryCount // 指数退避
        kotlinx.coroutines.delay(delay)
    }

    @Throws(IOException::class)
    private fun saveFile(response: Response) {
        val body = response.body ?: throw IOException("Response body is null")
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(downloadedLength)
            val buffer = ByteArray(8192) // 增大缓冲区大小
            var len: Int
            body.byteStream().use { input ->
                while (input.read(buffer).also { len = it } != -1) {
                    raf.write(buffer, 0, len)
                    downloadedLength += len
                }
            }
        }
    }

    private fun getFileSize(): Long {
        var retryCount = 0
        while (retryCount <= maxRetries) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (retryCount < maxRetries) {
                            retryCount++
                            Thread.sleep(retryDelay * retryCount)
                            return@use
                        }
                        throw IOException("Failed to get file size: $response")
                    }
                    return response.header("Content-Length")?.toLongOrNull()
                        ?: throw IOException("Content-Length header is missing")
                }
            } catch (e: SSLException) {
                if (retryCount < maxRetries) {
                    retryCount++
                    Thread.sleep(retryDelay * retryCount)
                    continue
                }
                throw IOException("SSL error after $maxRetries retries", e)
            }
        }
        throw IOException("Failed to get file size after $maxRetries retries")
    }
}