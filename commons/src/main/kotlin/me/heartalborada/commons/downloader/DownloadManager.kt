package me.heartalborada.commons.downloader

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors

class DownloadManager(
    parallelPoolSize: Int = 4,
    private val parentClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val cacheFolder: File = File(System.getProperty("java.io.tmpdir"))
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val downloadDispatcher = Executors.newFixedThreadPool(parallelPoolSize).asCoroutineDispatcher()
    private val okHttpClientDelegate = lazy {
        parentClient.newBuilder()
            .cache(Cache(cacheFolder, 1024L * 1024L * 1024L))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    private val okHttpClient: OkHttpClient by okHttpClientDelegate

    fun downloadFiles(
        urls: List<Pair<String, String?>>,
        destDir: File,
        parallelSize: Int = 4
    ): MutableList<Pair<String, String?>> {
        if (!destDir.exists()) destDir.mkdirs()
        val failedDownloads = mutableListOf<Pair<String, String?>>()
        runBlocking {
            urls.map { url ->
                async(downloadDispatcher) {
                    try {
                        logger.debug("Downloading {}", url.first)
                        val fileName = if (url.second != null) {
                            url.second!!
                        } else {
                            url.first.substring(url.first.lastIndexOf('/') + 1)
                        }
                        val destFile = File(destDir, fileName)
                        val downloadTask = DownloadTask(
                            threadCount = parallelSize,
                            url = url.first,
                            destFile = destFile,
                            progressFile = File(File(cacheFolder, "downloader"), "$fileName.progress"),
                            client = okHttpClient
                        )
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

    fun discardDownload(destDir: File, fileName: String) {
        val destination = File(destDir, fileName)
        val progress = File(File(cacheFolder, "downloader"), "$fileName.progress")
        if (destination.exists() && !destination.delete()) {
            logger.warn("Failed to delete incomplete download: {}", destination.absolutePath)
        }
        if (progress.exists() && !progress.delete()) {
            logger.warn("Failed to delete incomplete download progress: {}", progress.absolutePath)
        }
    }

    override fun close() {
        downloadDispatcher.close()
        if (okHttpClientDelegate.isInitialized()) {
            runCatching { okHttpClient.cache?.close() }
                .onFailure { logger.warn("Failed to close the download HTTP cache.", it) }
        }
    }
}
