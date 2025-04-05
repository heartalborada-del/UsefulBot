package me.heartalborada.commons.downloader

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MultiThreadedDownloadManager(
    threadCount: Int,
    private val client:OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
) {

    private val executor = Executors.newFixedThreadPool(threadCount)

    fun downloadFiles(urls: List<String>, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()

        urls.forEach { url ->
            val fileName = url.substring(url.lastIndexOf('/') + 1)
            val destFile = File(destDir, fileName)
            val downloadTask = DownloadTask(url, destFile, client)
            executor.submit(downloadTask)
        }
    }

    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }

    class DownloadTask(
        private val url: String,
        private val destFile: File,
        private val client: OkHttpClient
    ) : Runnable {
        override fun run() {
            try {
                val downloadedLength = if (destFile.exists()) destFile.length() else 0L
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$downloadedLength-")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Failed to download file: $response")

                    saveFile(response, downloadedLength)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @Throws(IOException::class)
        private fun saveFile(response: Response, downloadedLength: Long) {
            val body = response.body ?: throw IOException("Response body is null")
            val totalLength = downloadedLength + body.contentLength()
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(downloadedLength)
                val buffer = ByteArray(1024)
                var len: Int
                body.byteStream().use { input ->
                    while (input.read(buffer).also { len = it } != -1) {
                        raf.write(buffer, 0, len)
                    }
                }
            }
        }
    }
}