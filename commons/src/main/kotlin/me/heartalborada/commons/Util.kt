package me.heartalborada.commons

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.io.use
import kotlin.random.Random
import kotlin.use

class Util {
    companion object {
        private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        fun randomString(length: Int = 10): String {
            val sb = StringBuilder(length)
            for (i in 0..<length) {
                val randomIndex = Random.nextInt(CHARS.length)
                sb.append(CHARS[randomIndex])
            }
            return sb.toString()
        }
        fun resampleImage(img: BufferedImage, scale: Double): BufferedImage {
            val newWidth = (img.width * scale).toInt()
            val newHeight = (img.height * scale).toInt()
            val resized = BufferedImage(newWidth, newHeight, img.type)
            val g2d: Graphics2D = resized.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.drawImage(img, 0, 0, newWidth, newHeight, null)
            g2d.dispose()
            return resized
        }
        fun bufferedImageToBase64(image: BufferedImage, format: String = "png"): String {
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(image, format, outputStream)
            val imageBytes = outputStream.toByteArray()
            outputStream.close()
            return Base64.getEncoder().encodeToString(imageBytes)
        }
        fun gaussianBlur(inputImage: BufferedImage, radius: Int): BufferedImage {
            val size = radius * 2 + 1
            val weight = 1.0f / (size * size)
            val matrix = FloatArray(size * size) { weight }

            val kernel = Kernel(size, size, matrix)
            val convolveOp = ConvolveOp(kernel, ConvolveOp.EDGE_ZERO_FILL, null)

            val padding = radius
            val paddedWidth = inputImage.width + padding * 2
            val paddedHeight = inputImage.height + padding * 2
            val paddedImage = BufferedImage(paddedWidth, paddedHeight, inputImage.type)

            val g2d: Graphics2D = paddedImage.createGraphics()
            g2d.drawImage(inputImage, padding, padding, null)
            g2d.dispose()

            val blurredImage = convolveOp.filter(paddedImage, null)

            return blurredImage.getSubimage(padding, padding, inputImage.width, inputImage.height)
        }
        fun getFileExtensionFromUrl(url: URL): String? {
            return try {
                val path = url.path
                val lastDotIndex = path.lastIndexOf('.')
                if (lastDotIndex != -1 && lastDotIndex < path.length - 1) {
                    path.substring(lastDotIndex + 1)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
            if (intervals.isEmpty()) return emptyList()
            val sortedIntervals = intervals.sortedBy { it.first }
            val merged = mutableListOf<Pair<Long, Long>>()
            var currentStart = sortedIntervals[0].first
            var currentEnd = sortedIntervals[0].second
            for (i in 1 until sortedIntervals.size) {
                val (start, end) = sortedIntervals[i]

                if (start <= currentEnd + 1) {
                    currentEnd = maxOf(currentEnd, end)
                } else {
                    merged.add(Pair(currentStart, currentEnd))
                    currentStart = start
                    currentEnd = end
                }
            }
            merged.add(Pair(currentStart, currentEnd))
            return merged
        }
        fun unzip(zipFile: File, destDir: File) {
            if (!destDir.exists()) {
                destDir.mkdirs() // 创建目标目录
            }

            ZipInputStream(zipFile.inputStream()).use { zipInputStream ->
                var entry: ZipEntry?
                while (zipInputStream.nextEntry.also { entry = it } != null) {
                    val filePath = destDir.resolve(entry!!.name).canonicalPath

                    if (!filePath.startsWith(destDir.canonicalPath)) {
                        throw SecurityException("Attempt to extract a file outside of the target directory: $filePath")
                    }

                    if (entry.isDirectory) {
                        File(filePath).mkdirs()
                    } else {
                        File(filePath).parentFile?.mkdirs()
                        FileOutputStream(filePath).use { outputStream ->
                            zipInputStream.copyTo(outputStream)
                        }
                    }
                    zipInputStream.closeEntry()
                }
            }
        }
    }
}