package me.heartalborada.commons.downloader

import kotlinx.coroutines.*
import me.heartalborada.commons.okhttp.DoH
import me.heartalborada.commons.okhttp.RetryInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class MultiThreadedDownloadManager(
    threadCount: Int,
    private val parentClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val cacheFolder: File = File(System.getProperty("java.io.tmpdir"))
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val downloadDispatcher = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
    private val okHttpClient: OkHttpClient by lazy {
        parentClient.newBuilder()
            .cache(Cache(cacheFolder, 1024L * 1024L * 1024L))
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(DoH()).build()
    }
    fun downloadFiles(urls: List<Pair<String,String?>>, destDir: File): MutableList<Pair<String, String?>> {
        if (!destDir.exists()) destDir.mkdirs()
        val failedDownloads = mutableListOf<Pair<String, String?>>()
        runBlocking {
            urls.map { url ->
                async(downloadDispatcher) {
                    try {
                        logger.debug("Downloading {}", url)
                        val fileName = if (url.second != null) {
                            url.second!!
                        } else {
                            url.first.substring(url.first.lastIndexOf('/') + 1)
                        }
                        val destFile = File(destDir, fileName)
                        val downloadTask = DownloadTask(url.first, destFile, okHttpClient)
                        downloadTask.download()
                    } catch (e: Exception) {
                        logger.debug("Failed to download {}", url, e)
                        failedDownloads.add(url)
                    }
                }
            }.awaitAll()
        }
        return failedDownloads
    }
}